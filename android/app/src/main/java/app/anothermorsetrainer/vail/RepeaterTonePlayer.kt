package app.anothermorsetrainer.vail

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Plays received tone bursts at their scheduled local-clock time. Each burst is
 * a short rendered sine with click-free ramps on its own [AudioTrack], so tones
 * from several operators mix naturally. The RX counterpart of the local sidetone;
 * mirrors the iOS `KeyerEngine` `scheduleReceivedTone` path (Hz per MIDI note).
 */
class RepeaterTonePlayer {

    private val sampleRate = 44_100
    private val amplitude = 0.5f
    private val rampSeconds = 0.005
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<Runnable>()
    private val active = mutableListOf<AudioTrack>()

    /** Schedule a tone of [durationMs] at MIDI [midiNote] to play at [playAtLocalMs]. */
    fun scheduleTone(midiNote: Int, durationMs: Int, playAtLocalMs: Long) {
        if (durationMs <= 0) return
        val lead = (playAtLocalMs - System.currentTimeMillis()).coerceAtLeast(0)
        val r = Runnable { playBurst(midiNote, durationMs) }
        pending.add(r)
        main.postDelayed({ pending.remove(r); r.run() }, lead)
    }

    private fun playBurst(midiNote: Int, durationMs: Int) {
        val freq = 440.0 * 2.0.pow((midiNote - 69) / 12.0)
        val frames = (sampleRate.toDouble() * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val buf = FloatArray(frames)
        val ramp = (rampSeconds * sampleRate).toInt().coerceIn(1, frames / 2 + 1)
        val omega = 2.0 * PI * freq / sampleRate
        for (i in 0 until frames) {
            val env = when {
                i < ramp -> i.toFloat() / ramp
                i >= frames - ramp -> (frames - i).toFloat() / ramp
                else -> 1f
            }
            buf[i] = (sin(omega * i) * amplitude * env).toFloat()
        }
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
            .setBufferSizeInBytes(frames * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(buf, 0, frames, AudioTrack.WRITE_BLOCKING)
        track.play()
        active.add(track)
        // Release shortly after it finishes.
        main.postDelayed({
            try { track.stop() } catch (_: IllegalStateException) {}
            track.release()
            active.remove(track)
        }, durationMs + 100L)
    }

    fun release() {
        pending.forEach { main.removeCallbacks(it) }
        pending.clear()
        active.forEach {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        active.clear()
    }
}
