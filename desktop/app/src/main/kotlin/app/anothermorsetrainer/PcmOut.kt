package app.anothermorsetrainer

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * A streaming mono output line: the desktop's `AudioTrack` in `MODE_STREAM`.
 *
 * Every sound the app makes goes through one of these — the Morse player, the
 * sidetone, the background-noise floor, the repeater's received tones — each
 * fed by its own thread with blocking writes, the same shape as the Android
 * port. Java Sound's `SourceDataLine` maps to WASAPI/DirectSound on Windows
 * and ALSA on Linux (which, in a Flatpak, the runtime routes to PulseAudio or
 * PipeWire).
 *
 * The line is 16-bit signed little-endian PCM: float lines exist on some
 * mixers and not others, and 16-bit is the one format every Java Sound mixer
 * opens. Samples are clamped to ±1 and scaled on the way in.
 *
 * [open] returns null when there is no output device (a CI runner, a machine
 * with audio disabled). Callers treat that the way the Android port treats a
 * failed `AudioTrack.Builder().build()`: practice carries on silently.
 */
internal class PcmOut private constructor(private val line: SourceDataLine) {

    private var bytes = ByteArray(0)

    /** Write [count] samples of [buf], blocking until the line has taken them. False if the line died. */
    fun write(buf: FloatArray, count: Int): Boolean {
        val need = count * 2
        if (bytes.size < need) bytes = ByteArray(need)
        for (i in 0 until count) {
            val v = (buf[i].coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[2 * i] = (v and 0xFF).toByte()
            bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return try {
            line.write(bytes, 0, need) >= 0 && line.isOpen
        } catch (_: Exception) {
            false
        }
    }

    fun close() {
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
    }

    companion object {
        /**
         * Open a line at [sampleRate] with room for [bufferFrames] samples. The
         * buffer is the latency between asking for a sound and hearing it, so
         * callers keep it small; the mixer may round it up.
         */
        fun open(sampleRate: Int, bufferFrames: Int): PcmOut? = try {
            val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val line = AudioSystem.getSourceDataLine(format)
            line.open(format, bufferFrames * 2)
            line.start()
            PcmOut(line)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            // A headless JRE without the sound module's native half.
            null
        }
    }
}
