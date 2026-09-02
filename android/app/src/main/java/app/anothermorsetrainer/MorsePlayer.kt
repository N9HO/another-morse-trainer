package app.anothermorsetrainer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseSynth
import app.anothermorsetrainer.morsekit.MorseTiming
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Generates and plays the sound of a Morse character/word/prosign.
 *
 * Ported from the iOS MorseTrainerApp/MorsePlayer.swift, and now streaming like
 * it. The *synthesis* lives in [MorseSynth] (morsekit) on both sides; what is
 * left here is the platform half: Android's [AudioTrack] where Apple has
 * `AVAudioSourceNode`.
 *
 * **This used to pre-render.** Every sound was built into a whole `FloatArray`,
 * handed to a fresh `MODE_STATIC` track, and blocking-written — all on the
 * calling thread, which is the main thread. A Code Exam at novice speed is
 * roughly 22 million samples, ~88 MB, so Story and Exam at slow effective
 * speeds froze the UI before a single note played. Now a single persistent
 * `MODE_STREAM` track is fed by one background thread that synthesises a chunk
 * at a time from a segment list of a few hundred entries, whatever the passage
 * length. The same shape [SidetoneGenerator] and [BackgroundNoise] already use.
 *
 * The "finished" signal stays **time-based** — scheduled for the exact known
 * duration — so the quiz loop can never get stuck waiting on the audio system.
 *
 * On swapping sounds mid-tone (answers are accepted while audio is still
 * playing, so this is routine) the outgoing program is **faded out over the
 * same 5 ms the envelope uses** rather than cut. The old code muted the
 * previous track and leaned on a blocking write to give the mixer a beat to
 * apply it, which was the fix for issue #63; a real cross-fade is what that was
 * approximating.
 */
class MorsePlayer {

    private val sampleRate = 44_100
    private val rampSeconds = 0.005
    private val amplitude = 0.9f

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Distinguishes completion callbacks so a previous tone's timer can't fire for the current one. */
    private var generation = 0

    /**
     * What the feeder should be playing. Immutable and swapped whole, so the
     * feeder only ever reads a consistent pair; [generation] tells it when the
     * program changed without having to compare contents.
     *
     * Exactly one of [synth] and [buffer] is set. The pileup path still
     * materialises — several voices summed, each with its own pitch, speed, QSB
     * envelope and gain, then band noise and a peak normalisation over the
     * finished mix, none of which can be decided one sample ahead. Pileups are
     * callsigns and short exchanges, so that is bounded by what a pileup *is*,
     * unlike the Story and Exam passages this rewrite was for.
     */
    private class Program(val generation: Int, val synth: MorseSynth?, val buffer: FloatArray?)

    @Volatile private var program = Program(0, null, null)
    private var programGeneration = 0

    @Volatile private var running = false
    private var feeder: Thread? = null
    private var track: AudioTrack? = null

    // ---- Playing ----

    /** Play one character (convenience). */
    fun play(character: Char, frequency: Double, timing: MorseTiming, onFinished: () -> Unit) =
        play(MorseItem.Playable.Text(character.toString()), frequency, timing, onFinished)

    /**
     * Play a [playable] and call [onFinished] (on the main thread) after its exact
     * duration. This drives the time-to-recognize clock.
     */
    fun play(playable: MorseItem.Playable, frequency: Double, timing: MorseTiming, onFinished: () -> Unit) {
        // Built here, on the calling thread: a few hundred segments, not
        // millions of samples. The feeder thread does the synthesis.
        val synth = synthFor(playable, timing, frequency)
        if (synth.totalSamples == 0) { onFinished(); return }

        generation += 1
        val token = generation
        startProgram(Program(nextProgramGeneration(), synth, null))

        val durationMs = (synth.totalSamples.toDouble() / sampleRate * 1000).toLong()
        mainHandler.postDelayed({ if (generation == token) onFinished() }, durationMs)
    }

    /**
     * Replay the current sound without affecting the finished-timer (used by the
     * optional replay button, which must not disturb the TTR clock). Returns the
     * sound's duration in seconds (0 if nothing to play).
     */
    fun replaySound(playable: MorseItem.Playable, frequency: Double, timing: MorseTiming): Double {
        val synth = synthFor(playable, timing, frequency)
        if (synth.totalSamples == 0) return 0.0
        startProgram(Program(nextProgramGeneration(), synth, null))
        return synth.totalSamples.toDouble() / sampleRate
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
        startProgram(Program(nextProgramGeneration(), null, mixed))
        val durationMs = (mixed.size.toDouble() / sampleRate * 1000).toLong()
        mainHandler.postDelayed({ if (generation == token) onFinished() }, durationMs)
    }

    private class Rendered(val samples: FloatArray, val offset: Int, val gain: Float, val qsb: Double?)

    private fun mixPileup(voices: List<PileupVoice>, qrn: Float): FloatArray {
        val rendered = voices.map { v ->
            Rendered(
                synthFor(MorseItem.Playable.Text(v.text), v.timing, v.frequency).renderAll(),
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
        // A silent program rather than a torn-down track: the feeder cross-fades
        // out of whatever was sounding, so stopping mid-tone is as quiet as
        // starting.
        startProgram(Program(nextProgramGeneration(), null, null))
    }

    /**
     * Free the audio resources. Call from the owner's onDispose/onDestroy.
     *
     * This — not [stop] — is where audio focus is given back and the feeder
     * ends. [stop] runs between every item in a drill, and tearing the track
     * down in those gaps is exactly what this rewrite removed.
     */
    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        running = false
        startProgram(Program(nextProgramGeneration(), null, null))
        feeder?.let { runCatching { it.join(250) } }
        feeder = null
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
        AudioFocus.release(this)
    }

    private fun synthFor(playable: MorseItem.Playable, timing: MorseTiming, frequency: Double): MorseSynth =
        MorseSynth.forPlayable(
            playable, timing, sampleRate.toDouble(), frequency, amplitude, rampSeconds
        )

    @Synchronized
    private fun nextProgramGeneration(): Int {
        programGeneration += 1
        return programGeneration
    }

    /** Hand the feeder a new program, starting it if this is the first sound. */
    private fun startProgram(next: Program) {
        program = next
        if (next.synth == null && next.buffer == null) return   // silence needs no feeder
        // Taken on first sound rather than at construction: a screen that builds
        // a player and never plays has no business pausing anyone's music.
        AudioFocus.acquire(this)
        ensureFeeder()
    }

    @Synchronized
    private fun ensureFeeder() {
        if (running && feeder != null) return
        val t = openTrack() ?: return   // no route: practice carries on silently
        track = t
        running = true
        feeder = Thread { feed(t) }.apply { isDaemon = true; name = "amt-morse-feeder"; start() }
    }

    /**
     * Owns the streaming track. Writes are blocking, so the loop paces itself
     * against playback and the thread spends nearly all of its time parked —
     * the same shape as [BackgroundNoise].
     *
     * A chunk is ~12 ms, which is also the worst case for how long an
     * already-queued sound keeps playing after [stop]. That is a tail of real
     * audio faded to nothing, not a click, and it is well under the gap between
     * drills.
     */
    private fun feed(t: AudioTrack) {
        // Parked in a blocking write nearly all the time; the priority is for
        // the moments it wakes, so a chunk is not queued behind a Compose frame
        // and the track never runs dry mid-character. A refused priority is
        // the old behaviour, not a failure.
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: SecurityException) {}
        val buf = FloatArray(CHUNK)
        val rampSamples = maxOf(1, (rampSeconds * sampleRate).toInt())

        var playing: Program? = null
        var cursor = MorseSynth.Cursor()
        var bufferIndex = 0

        // The outgoing program, still sounding while it fades.
        var fading: Program? = null
        var fadeCursor = MorseSynth.Cursor()
        var fadeIndex = 0
        var fadeLeft = 0

        // Deliberately not a helper returning a pair of (sample, newIndex):
        // that allocates once per sample, 44,100 times a second, on the thread
        // least able to afford GC pressure. These mutate the captured cursors
        // in place instead.
        fun liveSample(): Float {
            val p = playing ?: return 0f
            val synth = p.synth
            if (synth != null) return synth.next(cursor)
            val b = p.buffer ?: return 0f
            return if (bufferIndex < b.size) b[bufferIndex++] else 0f
        }

        fun fadingSample(): Float {
            val p = fading ?: return 0f
            val synth = p.synth
            if (synth != null) return synth.next(fadeCursor)
            val b = p.buffer ?: return 0f
            return if (fadeIndex < b.size) b[fadeIndex++] else 0f
        }

        try {
            t.play()
            while (running) {
                val next = program
                val current = playing
                if (current == null || next.generation != current.generation) {
                    // Fade the old one out instead of cutting it. Answers are
                    // accepted mid-audio, so this path is routine, and cutting a
                    // sine at full amplitude is an audible click (issue #63).
                    if (current != null) {
                        fading = current
                        fadeCursor = cursor.copy()
                        fadeIndex = bufferIndex
                        fadeLeft = rampSamples
                    }
                    playing = next
                    cursor = MorseSynth.Cursor()
                    bufferIndex = 0
                }

                for (i in 0 until CHUNK) {
                    var v = liveSample()
                    if (fadeLeft > 0) {
                        v += fadingSample() * (fadeLeft.toFloat() / rampSamples)
                        fadeLeft -= 1
                        if (fadeLeft == 0) fading = null
                    }
                    buf[i] = v.coerceIn(-1f, 1f)
                }
                if (t.write(buf, 0, CHUNK, AudioTrack.WRITE_BLOCKING) < 0) break
            }
        } catch (_: IllegalStateException) {
            // Track died under us (route change, audio policy). Nothing to salvage.
        } finally {
            synchronized(this) { running = false }
        }
    }

    private fun openTrack(): AudioTrack? = try {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        // getMinBufferSize reports an error as a negative value; fall back to a
        // couple of chunks rather than handing AudioTrack.Builder nonsense.
        // Kept small on purpose: this buffer is the latency between asking for a
        // sound and hearing it, and between stop() and silence.
        val bufferBytes = if (minBytes > 0) maxOf(minBytes, CHUNK * 4 * 2) else CHUNK * 4 * 2
        AudioTrack.Builder()
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
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** ~12 ms at 44.1 kHz: small enough to react, big enough to be cheap. */
        const val CHUNK = 512
    }
}
