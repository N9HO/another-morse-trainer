package app.anothermorsetrainer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseTiming
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates and plays the sound of a Morse character/word/prosign.
 *
 * Ported from the iOS MorseTrainerApp/MorsePlayer.swift. The *rendering* (clean
 * sine tones with short raised-cosine ramps so they don't click) is a direct
 * translation; the *output* swaps Apple's AVAudioEngine/AVAudioSourceNode for
 * Android's [AudioTrack] in static-buffer mode. The "finished" signal stays
 * time-based — scheduled for the exact known duration — so the quiz loop can
 * never get stuck waiting on the audio system.
 */
class MorsePlayer {

    private val sampleRate = 44_100
    private val rampSeconds = 0.005
    private val amplitude = 0.9

    private val mainHandler = Handler(Looper.getMainLooper())
    private var current: AudioTrack? = null

    /** Distinguishes completion callbacks so a previous tone's timer can't fire for the current one. */
    private var generation = 0

    private data class Segment(val tone: Double, val gap: Double)

    // ---- Playing ----

    /** Play one character (convenience). */
    fun play(character: Char, frequency: Double, timing: MorseTiming, onFinished: () -> Unit) =
        play(MorseItem.Playable.Text(character.toString()), frequency, timing, onFinished)

    /**
     * Play a [playable] and call [onFinished] (on the main thread) after its exact
     * duration. This drives the time-to-recognize clock.
     */
    fun play(playable: MorseItem.Playable, frequency: Double, timing: MorseTiming, onFinished: () -> Unit) {
        val floats = render(playable, timing, frequency)
        if (floats.isEmpty()) { onFinished(); return }

        generation += 1
        val token = generation
        playFloats(floats)

        val durationMs = (floats.size.toDouble() / sampleRate * 1000).toLong()
        mainHandler.postDelayed({ if (generation == token) onFinished() }, durationMs)
    }

    /**
     * Replay the current sound without affecting the finished-timer (used by the
     * optional replay button, which must not disturb the TTR clock). Returns the
     * sound's duration in seconds (0 if nothing to play).
     */
    fun replaySound(playable: MorseItem.Playable, frequency: Double, timing: MorseTiming): Double {
        val floats = render(playable, timing, frequency)
        if (floats.isEmpty()) return 0.0
        playFloats(floats)
        return floats.size.toDouble() / sampleRate
    }

    // ---- Pileup (multiple simultaneous transmissions) ----

    /**
     * One station's transmission in a pileup. Rendered at its own pitch/speed
     * and summed with the others, offset by [startDelay], so callers overlap —
     * zero-beat (same tone) or split (different tone), just like a real pileup.
     */
    data class PileupVoice(
        val text: String,
        val frequency: Double,
        val timing: MorseTiming,
        val gain: Float,              // 0…1 relative loudness
        val startDelay: Double,       // seconds
        val qsbRate: Double?          // slow-fade rate in Hz; null = steady signal
    )

    /**
     * Mix [voices] into one buffer and play it. Optional [qrn] adds atmospheric
     * hiss across the whole band. [onFinished] fires after the longest voice.
     */
    fun playPileup(voices: List<PileupVoice>, qrn: Float = 0f, onFinished: () -> Unit) {
        val mixed = mixPileup(voices, qrn)
        if (mixed.isEmpty()) { onFinished(); return }
        generation += 1
        val token = generation
        playFloats(mixed)
        val durationMs = (mixed.size.toDouble() / sampleRate * 1000).toLong()
        mainHandler.postDelayed({ if (generation == token) onFinished() }, durationMs)
    }

    private class Rendered(val samples: FloatArray, val offset: Int, val gain: Float, val qsb: Double?)

    private fun mixPileup(voices: List<PileupVoice>, qrn: Float): FloatArray {
        val rendered = voices.map { v ->
            Rendered(
                render(MorseItem.Playable.Text(v.text), v.timing, v.frequency),
                maxOf(0, (v.startDelay * sampleRate).toInt()), v.gain, v.qsbRate
            )
        }
        val total = rendered.maxOfOrNull { it.offset + it.samples.size } ?: 0
        if (total <= 0) return FloatArray(0)
        val out = FloatArray(total)

        for (r in rendered) {
            val qsbOmega = r.qsb?.let { 2.0 * PI * it / sampleRate }
            for (i in r.samples.indices) {
                var a = r.samples[i] * r.gain
                if (qsbOmega != null) {
                    // Gentle 0.35…1.0 fade so some signals swell and dip.
                    val env = 0.675 + 0.325 * sin(qsbOmega * (r.offset + i))
                    a *= env.toFloat()
                }
                out[r.offset + i] += a
            }
        }

        if (qrn > 0) {
            var st = 0x2545F4914F6CDD1DuL
            for (i in 0 until total) {
                st = st * 6364136223846793005uL + 1442695040888963407uL
                val n = (st shr 33).toInt() / Int.MAX_VALUE.toFloat()
                out[i] += n * qrn
            }
        }

        // Sum can exceed ±1 with several loud callers — scale down to avoid hard clipping.
        var peak = 0f
        for (v in out) { val a = abs(v); if (a > peak) peak = a }
        if (peak > 1f) { val inv = 1f / peak; for (i in 0 until total) out[i] *= inv }
        return out
    }

    fun stop() {
        mainHandler.removeCallbacksAndMessages(null)
        releaseCurrent()
    }

    /** Free the audio resources. Call from the owner's onDispose/onDestroy. */
    fun release() = stop()

    private fun playFloats(floats: FloatArray) {
        // A new sound can arrive while the previous one is still playing —
        // answers are accepted mid-audio, so the auto-advance often lands
        // before the old word finishes. Stopping that track outright cuts it
        // mid-sine, an audible click/crackle on every such transition
        // (issue #63). Mute it first (the mixer ramps volume changes), let the
        // blocking write of the new buffer give that ramp a beat to land, and
        // only then release it.
        val previous = current
        current = null
        previous?.let { runCatching { it.setVolume(0f) } }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(floats.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(floats, 0, floats.size, AudioTrack.WRITE_BLOCKING)
        previous?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        track.play()
        current = track
    }

    private fun releaseCurrent() {
        current?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        current = null
    }

    // ---- Rendering to float samples ----

    private fun render(playable: MorseItem.Playable, timing: MorseTiming, frequency: Double): FloatArray {
        val segments = segments(playable, timing)
        val out = ArrayList<Float>()
        val rampSamples = maxOf(1, (rampSeconds * sampleRate).toInt())
        val omega = 2.0 * PI * frequency / sampleRate

        for (segment in segments) {
            val toneCount = (segment.tone * sampleRate).toInt()
            if (toneCount > 0) {
                out.ensureCapacity(out.size + toneCount)
                for (n in 0 until toneCount) {
                    var amp = amplitude
                    if (n < rampSamples) {
                        amp *= 0.5 * (1 - cos(PI * n / rampSamples))
                    } else if (n >= toneCount - rampSamples) {
                        val m = toneCount - n
                        amp *= 0.5 * (1 - cos(PI * m / rampSamples))
                    }
                    out.add((amp * sin(omega * n)).toFloat())
                }
            }
            val gapCount = (segment.gap * sampleRate).toInt()
            repeat(gapCount) { out.add(0f) }
        }
        return out.toFloatArray()
    }

    private fun segments(playable: MorseItem.Playable, timing: MorseTiming): List<Segment> {
        return when (playable) {
            is MorseItem.Playable.Pattern -> {
                val els = playable.value.map { if (it == '.') MorseCode.Element.DIT else MorseCode.Element.DAH }
                withGaps(els, timing, interElement = timing.elementGap, trailing = 0.0)
            }
            is MorseItem.Playable.Text -> {
                val chars = playable.value.toList()
                val result = ArrayList<Segment>()
                for ((ci, ch) in chars.withIndex()) {
                    // A space is a word gap: stretch the previous character's trailing
                    // gap to a full word gap. Single tokens are unaffected.
                    if (ch == ' ') {
                        if (result.isNotEmpty()) {
                            val last = result.removeAt(result.size - 1)
                            result.add(last.copy(gap = timing.wordGap))
                        }
                        continue
                    }
                    val els = MorseCode.elements(ch)
                    if (els.isEmpty()) continue
                    val afterChar = if (ci == chars.size - 1) 0.0 else timing.characterGap
                    result.addAll(withGaps(els, timing, interElement = timing.elementGap, trailing = afterChar))
                }
                result
            }
        }
    }

    private fun withGaps(
        elements: List<MorseCode.Element>,
        timing: MorseTiming,
        interElement: Double,
        trailing: Double
    ): List<Segment> = elements.mapIndexed { i, el ->
        val tone = if (el == MorseCode.Element.DIT) timing.dit else timing.dah
        val gap = if (i == elements.size - 1) trailing else interElement
        Segment(tone, gap)
    }
}
