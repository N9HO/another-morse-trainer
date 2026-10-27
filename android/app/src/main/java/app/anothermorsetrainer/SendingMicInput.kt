package app.anothermorsetrainer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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
import app.anothermorsetrainer.morsekit.MicEvent
import app.anothermorsetrainer.morsekit.MicInputState
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
 * The two flags move by [MicInputState] (pinned by
 * `fixtures/gap-analyzer-mic-state.json`). As on iOS the microphone stops, and
 * does not resume by itself, when audio focus is taken from it (a call, another
 * app: [AudioFocus] LOST / LOST_TRANSIENT, the Android counterpart of an audio
 * session interruption), when the input device it is recording from is removed
 * (route loss), and when the capture dies under the reader. A refused
 * permission is [micDenied], which the screen words on its own.
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

    private var state = MicInputState()
    private val main = Handler(Looper.getMainLooper())
    private var focusObservation: AudioFocus.Observation? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var audioManager: AudioManager? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    /** Picked up by the capture thread at the start of its next buffer. */
    @Volatile private var pendingSnr: Double? = null

    private fun transition(event: MicEvent) {
        state = state.after(event)
        isListening = state.isListening
        micDenied = state.micDenied
    }

    /** Start listening (the screen asks for the mic permission first). */
    fun start(context: Context, snr: Double) {
        if (isListening) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            transition(MicEvent.START_DENIED)
            return
        }

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
        val audio = rec ?: run {
            transition(MicEvent.START_FAILED)
            return
        }
        // Another app may hold the microphone: startRecording then throws or
        // leaves the record stopped. Not a denial; the permission is held.
        val started = try {
            audio.startRecording()
            audio.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (_: IllegalStateException) {
            false
        }
        if (!started) {
            audio.release()
            transition(MicEvent.START_FAILED)
            return
        }
        record = audio
        val detector = ToneKeyingDetector(rate.toDouble(), snr)
        pendingSnr = null
        running = true
        val t = Thread {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: SecurityException) {}
            val buffer = FloatArray(1024)
            while (running) {
                val n = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    // ERROR_DEAD_OBJECT and friends: the capture died under us
                    // (not a stop(): that clears `running` first).
                    main.post { if (running) shutDown(MicEvent.ROUTE_LOST) }
                    break
                }
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
        transition(MicEvent.START_GRANTED)

        // Hold audio focus while listening so a call or another app taking it
        // is heard about (iOS: the recording session claim and its interruption
        // observer). Anything that takes focus away stops the microphone; the
        // end of it (REGAINED) does not restart it.
        AudioFocus.acquire(this)
        focusObservation = AudioFocus.observe { event ->
            if (isListening && (event == AudioFocus.Event.LOST || event == AudioFocus.Event.LOST_TRANSIENT)) {
                shutDown(MicEvent.INTERRUPTION_BEGAN)
            }
        }
        // Route loss: the input device being recorded from was removed
        // (a USB or Bluetooth microphone unplugged or switched off).
        val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager = manager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (!isListening) return
                val routedId = record?.routedDevice?.id
                val lost = removedDevices.any { it.isSource && (routedId == null || it.id == routedId) }
                if (lost) shutDown(MicEvent.ROUTE_LOST)
            }
        }
        deviceCallback = callback
        manager?.registerAudioDeviceCallback(callback, main)
    }

    fun stop() = shutDown(MicEvent.STOP)

    private fun shutDown(event: MicEvent) {
        focusObservation?.let { AudioFocus.removeObserver(it) }
        focusObservation = null
        deviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        audioManager = null
        if (record != null) AudioFocus.release(this)
        running = false
        // Stop the record before joining: stop() is what unblocks a blocking
        // read. Release only after the thread is out.
        record?.let { try { it.stop() } catch (_: IllegalStateException) {} }
        thread?.join(500)
        thread = null
        record?.release()
        record = null
        transition(event)
        toneOn = false
        level = 0.0
    }

    /** Change how loud a tone must be over the room (applied on the next buffer). */
    fun setSensitivity(snr: Double) {
        pendingSnr = snr
    }
}
