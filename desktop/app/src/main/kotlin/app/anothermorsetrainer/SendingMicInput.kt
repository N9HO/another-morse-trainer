package app.anothermorsetrainer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.anothermorsetrainer.morsekit.ToneKeyingDetector

/**
 * The microphone as a Morse key (#234, #235): a tone the phone can hear — a
 * practice oscillator, a keyer's sidetone, a rig's speaker, a signal
 * generator — becomes key-down / key-up edges for the Sending Analyzer.
 *
 * The tone detection is MorseKit's [ToneKeyingDetector]; this is only the
 * capture around it, set up the way [CwDecoderEngine] captures (UNPROCESSED
 * source, the same sample-rate fallback chain, a dedicated capture thread),
 * and it does not touch the ported decoder core. Edge times come from the
 * sample count, so they are as accurate as the detector, not as late as the
 * main thread. Port of the iOS `SendingMicInput`; on desktop the capture is a
 * Java Sound [PcmIn], and [micDenied] means no input line could be opened.
 *
 * Nobody has keyed real hardware into this yet: the detector is pinned by a
 * synthetic fixture, and the capture has only been compiled.
 */
class SendingMicInput {

    var isListening by mutableStateOf(false)
        private set
    var micDenied by mutableStateOf(false)
        private set
    /** Tone level and floor (sine amplitude, 0…1) for the meter. */
    var level by mutableDoubleStateOf(0.0)
        private set
    var noiseFloor by mutableDoubleStateOf(0.0)
        private set
    var toneOn by mutableStateOf(false)
        private set
    var pitchHz by mutableStateOf<Double?>(null)
        private set
    var pitchLocked by mutableStateOf(false)
        private set

    /** Key edges (on the main thread), in ms since listening started. */
    var onEdge: ((isDown: Boolean, atMs: Double) -> Unit)? = null

    private val main = MainHandler()
    private var record: PcmIn? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    /** Picked up by the capture thread at the start of its next buffer. */
    @Volatile private var pendingSnr: Double? = null

    /** Start listening. */
    fun start(snr: Double) {
        if (isListening) return
        micDenied = false

        val audio = PcmIn.open(intArrayOf(48_000, 44_100, 16_000), 4 * 1024) ?: run {
            micDenied = true
            return
        }
        val rate = audio.sampleRate
        record = audio
        val detector = ToneKeyingDetector(rate.toDouble(), snr)
        pendingSnr = null
        running = true
        val t = Thread {
            try { Thread.currentThread().priority = Thread.MAX_PRIORITY } catch (_: SecurityException) {}
            val buffer = FloatArray(1024)
            while (running) {
                val n = audio.read(buffer, buffer.size)
                if (n < 0) break
                if (n == 0) continue
                pendingSnr?.let { detector.snr = it; pendingSnr = null }
                val edges = detector.process(buffer, n)
                val lv = detector.level
                val floor = detector.noiseFloor
                val down = detector.isDown
                val pitch = detector.pitchHz
                val locked = detector.isLocked
                main.post {
                    if (!running) return@post
                    edges.forEach { onEdge?.invoke(it.isDown, it.timeMs) }
                    level = lv
                    noiseFloor = floor
                    toneOn = down
                    pitchHz = pitch
                    pitchLocked = locked
                }
            }
        }
        t.name = "sending-mic-capture"
        thread = t
        t.start()
        isListening = true
    }

    fun stop() {
        running = false
        // Stop the record before joining: stop() is what unblocks a blocking
        // read. Release only after the thread is out.
        record?.stop()
        thread?.join(500)
        thread = null
        record?.close()
        record = null
        isListening = false
        toneOn = false
        level = 0.0
    }

    /** Change how loud a tone must be over the room (applied on the next buffer). */
    fun setSensitivity(snr: Double) {
        pendingSnr = snr
    }
}
