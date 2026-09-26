package app.anothermorsetrainer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
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
 * main thread. Port of the iOS `SendingMicInput`.
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

    private val main = Handler(Looper.getMainLooper())
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    /** Picked up by the capture thread at the start of its next buffer. */
    @Volatile private var pendingSnr: Double? = null

    /** Start listening (the screen asks for the mic permission first). */
    fun start(context: Context, snr: Double) {
        if (isListening) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micDenied = true
            return
        }
        micDenied = false

        var rec: AudioRecord? = null
        var rate = 0
        for (candidate in intArrayOf(48_000, 44_100, 16_000)) {
            val minBytes = AudioRecord.getMinBufferSize(
                candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            if (minBytes <= 0) continue
            // UNPROCESSED skips AGC and noise suppression, both of which would
            // smear the very edges being timed.
            val r = try {
                AudioRecord(
                    MediaRecorder.AudioSource.UNPROCESSED,
                    candidate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                    maxOf(minBytes, 4 * 1024 * 4)
                )
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: SecurityException) {
                null
            }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                rec = r; rate = candidate; break
            }
            r?.release()
        }
        val audio = rec ?: return
        record = audio
        val detector = ToneKeyingDetector(rate.toDouble(), snr)
        pendingSnr = null
        running = true
        val t = Thread {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: SecurityException) {}
            val buffer = FloatArray(1024)
            audio.startRecording()
            while (running) {
                val n = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
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
        record?.let { try { it.stop() } catch (_: IllegalStateException) {} }
        thread?.join(500)
        thread = null
        record?.release()
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
