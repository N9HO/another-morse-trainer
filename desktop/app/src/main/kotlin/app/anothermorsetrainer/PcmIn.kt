package app.anothermorsetrainer

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

/**
 * A mono microphone line: the desktop's `AudioRecord`.
 *
 * Java Sound's `TargetDataLine` is WASAPI/DirectSound capture on Windows and
 * ALSA on Linux (PulseAudio/PipeWire through the runtime's ALSA plugin inside
 * Flatpak, which needs `--socket=pulseaudio`). There is no runtime permission
 * prompt for a desktop Java app. On Windows the microphone privacy switch for
 * desktop apps applies; when it is off, the line may open and deliver silence
 * rather than fail, which reads in the decoder as "no tone". The Android
 * port's `UNPROCESSED` source has no Java Sound equivalent: whatever the
 * operating system's input processing does is what arrives here.
 *
 * 16-bit signed little-endian, scaled to ±1 floats on the way out, for the
 * same reason as [PcmOut]: it is the format every mixer opens.
 */
internal class PcmIn private constructor(private val line: TargetDataLine, val sampleRate: Int) {

    private var bytes = ByteArray(0)

    /** Block until [buf] has up to [count] samples; returns how many were read, or -1 when the line died. */
    fun read(buf: FloatArray, count: Int): Int {
        val need = count * 2
        if (bytes.size < need) bytes = ByteArray(need)
        val got = try { line.read(bytes, 0, need) } catch (_: Exception) { return -1 }
        if (got <= 0) return if (line.isOpen) 0 else -1
        val n = got / 2
        for (i in 0 until n) {
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            buf[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return n
    }

    /** Unblocks a pending [read]; call before joining the reading thread. */
    fun stop() {
        runCatching { line.stop() }
        runCatching { line.flush() }
    }

    fun close() {
        runCatching { line.close() }
    }

    companion object {
        /**
         * Open the default input at the first of [rates] the device accepts,
         * started and ready to [read]. Null when there is no input device or
         * none of the rates opens.
         */
        fun open(rates: IntArray, bufferFrames: Int): PcmIn? {
            for (rate in rates) {
                val line = try {
                    val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
                    val l = AudioSystem.getTargetDataLine(format)
                    l.open(format, bufferFrames * 2)
                    l.start()
                    l
                } catch (_: Exception) {
                    null
                } catch (_: LinkageError) {
                    null
                }
                if (line != null) return PcmIn(line, rate)
            }
            return null
        }
    }
}
