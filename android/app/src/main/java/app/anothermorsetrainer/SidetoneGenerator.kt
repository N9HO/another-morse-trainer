package app.anothermorsetrainer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/**
 * A continuous sine that is gated on/off by the Morse key, for live sending
 * practice. Unlike [MorsePlayer] (which renders fixed bursts), this keeps a
 * streaming [AudioTrack] running and ramps the gain up/down over 5 ms on each
 * key-down/up so the sidetone is click-free.
 *
 * Port of the TX path of the iOS `KeyerEngine` `ToneGenerator` (the RX / received
 * tone scheduling lives with the Vail repeater work). The audio render runs on a
 * dedicated thread; the shared state is [keyDown] and [frequencyHz]. The samples
 * themselves come from a [SidetoneSynth], which knows nothing about the track.
 *
 * **Latency.** The key is read once per block and heard only after what is
 * queued plays out, so the track asks for the low-latency path at the output's
 * own rate, renders one hardware burst per block and, when that path is
 * granted, starts with two bursts queued (Oboe's starting size). Each time
 * the queue runs dry, which is heard as a click, it grows by a burst, as
 * Oboe's latency tuner does, up to [MAX_QUEUED_BURSTS]; the depth a device
 * needed is kept for the rest of the process, so the next drill starts there.
 * A normal-mixer track keeps its default queue; a device that won't open the
 * track gets the old 44.1 kHz one, and one with no output keys silently.
 * Logcat tag `amt-sidetone` says what was granted and each time the queue
 * grew. The iOS twin is `AudioSession`'s 5 ms I/O buffer.
 *
 * [focusGain] is what the generator asks of other apps' audio for as long as it
 * runs. Drills leave the default, which pauses them; the Vail repeater passes
 * [AudioFocus.Gain.DUCK] so a radio app keeps running alongside — the split
 * [AudioFocus] documents, and the same one iOS makes with its `repeaterMix`
 * session.
 */
class SidetoneGenerator(
    frequencyHz: Double = 600.0,
    private val focusGain: AudioFocus.Gain = AudioFocus.Gain.EXCLUSIVE
) {

    private val amplitude = 0.5f
    private val rampSeconds = 0.005

    /** The pitch; a change reaches the next block ([SendingKeyer.applyConfig]). */
    @Volatile var frequencyHz: Double = frequencyHz

    private val keyDown = AtomicBoolean(false)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var output: Output? = null

    /** An open track and the shape it was granted. */
    private class Output(val track: AudioTrack, val sampleRate: Int, val blockFrames: Int, val lowLatency: Boolean)

    fun start() {
        if (running) return
        running = true
        AudioFocus.acquire(this, focusGain)
        val out = open()
        if (out == null) {
            // No usable output: keying still decodes, silently.
            Log.w(TAG, "no output track; keying silently")
            running = false
            AudioFocus.release(this)
            return
        }
        output = out
        Log.i(TAG, "start: ${if (out.lowLatency) "low latency" else "normal"} at ${out.sampleRate} Hz, " +
            "${out.blockFrames}-frame blocks, ${out.track.bufferSizeInFrames} frames queued")
        thread = Thread { renderLoop(out) }.apply { isDaemon = true; name = "amt-sidetone"; start() }
    }

    /** Key down/up — the render loop ramps toward the new target gain. */
    fun setKeyDown(down: Boolean) {
        keyDown.set(down)
    }

    fun stop() {
        running = false
        keyDown.set(false)
        thread?.let { try { it.join(200) } catch (_: InterruptedException) {} }
        thread = null
        output?.let {
            Log.i(TAG, "stop: ${it.track.underrunCount} underruns, ${it.track.bufferSizeInFrames} frames queued")
            try { it.track.stop() } catch (_: IllegalStateException) {}
            it.track.release()
        }
        output = null
        AudioFocus.release(this)
    }

    /** The low-latency track if the device grants one, else the old one. */
    private fun open(): Output? {
        val rate = AudioFocus.outputSampleRate
            ?: AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 }
            ?: FALLBACK_SAMPLE_RATE
        val burst = AudioFocus.outputFramesPerBurst?.takeIf { it in MIN_BURST..MAX_BURST } ?: (rate / 400)
        return openTrack(rate, burst, lowLatency = true)
            ?: openTrack(LEGACY_SAMPLE_RATE, LEGACY_SAMPLE_RATE / 100, lowLatency = false)
    }

    private fun openTrack(rate: Int, blockFrames: Int, lowLatency: Boolean): Output? {
        val track = try {
            val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            if (minBytes <= 0) return null
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(if (lowLatency) maxOf(minBytes, MAX_QUEUED_BURSTS * blockFrames * 4) else minBytes.coerceAtLeast(2048))
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            builder.build()
        } catch (e: Exception) {
            // UnsupportedOperationException, IllegalArgumentException: this
            // device will not open that track. The caller tries the next one.
            Log.w(TAG, "${if (lowLatency) "low-latency" else "normal"} track at $rate Hz refused", e)
            return null
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return null
        }
        // Asking is not getting: the system can hand back a normal-mixer track
        // (before API 26, or on a route with no fast path, such as Bluetooth),
        // and that one needs its default queue or it underruns.
        val granted = lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        if (granted) {
            // Queue a few bursts, not the whole capacity: the writer keeps the
            // queue full, so this is what every key edge waits behind. Two to
            // begin with, or what this device has already shown it needs
            // ([deepen]); the system rounds it up if it must.
            track.setBufferSizeInFrames(maxOf(queuedBursts * blockFrames, rate / 250))
        }
        return try {
            track.play()
            Output(track, rate, blockFrames, granted)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "track at $rate Hz would not play", e)
            track.release()
            null
        }
    }

    private fun renderLoop(out: Output) {
        // Parked in a blocking write nearly all the time; the priority is for
        // the moments it wakes, so a block is not queued behind a Compose
        // frame and the key-down is heard when it happens. A refused priority
        // is the old behaviour, not a failure.
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: SecurityException) {}
        val block = out.blockFrames
        val buf = FloatArray(block)
        val synth = SidetoneSynth(out.sampleRate, frequencyHz, amplitude, rampSeconds)
        // The track starts playing before the first block is written, so the
        // count it holds by then is the start's, not the queue running dry.
        var underruns = -1
        while (running) {
            synth.frequencyHz = frequencyHz
            synth.render(buf, block, keyDown.get())
            try {
                out.track.write(buf, 0, block, AudioTrack.WRITE_BLOCKING)
            } catch (_: IllegalStateException) {
                break
            }
            if (out.lowLatency) {
                val count = out.track.underrunCount
                if (underruns < 0) {
                    underruns = count
                } else if (count > underruns) {
                    underruns = count
                    deepen(out)
                }
            }
        }
    }

    /**
     * The queue ran dry: queue one more burst, up to [MAX_QUEUED_BURSTS].
     * Render thread only, between writes.
     */
    private fun deepen(out: Output) {
        val track = out.track
        val now = track.bufferSizeInFrames
        if (now >= MAX_QUEUED_BURSTS * out.blockFrames) return
        val got = track.setBufferSizeInFrames(now + out.blockFrames)
        if (got <= now) return
        queuedBursts = maxOf(queuedBursts, (got + out.blockFrames - 1) / out.blockFrames)
        Log.i(TAG, "ran dry: now $got frames queued")
    }

    private companion object {
        const val TAG = "amt-sidetone"
        /** The deepest the low-latency queue grows: 24 ms at a 4 ms burst. */
        const val MAX_QUEUED_BURSTS = 6
        /**
         * Bursts to queue on a low-latency track: two, or more once a track in
         * this process has run dry. Kept across drills, not across launches.
         */
        @Volatile var queuedBursts = 2
        /** The rate it used to run at, and still does when low latency is refused. */
        const val LEGACY_SAMPLE_RATE = 44_100
        const val FALLBACK_SAMPLE_RATE = 48_000
        /** Bursts outside this are a misreport; fall back to 2.5 ms blocks. */
        const val MIN_BURST = 16
        const val MAX_BURST = 4096
    }
}

/**
 * The pure half of [SidetoneGenerator]: a sine gated by a ramped gain, one
 * block at a time. Holds the oscillator phase and the current gain so
 * consecutive blocks join seamlessly. Kept apart from the [AudioTrack] so the
 * ramp can be pinned by a unit test without audio hardware.
 *
 * The ramp moves [gain] toward the key's target by at most one step per sample
 * and lands on it exactly — the same one-liner [BackgroundNoise] uses. It
 * replaced a step followed by an overshoot guard of the form
 * `target > gain && gain > target`, which meant to snap a step that crossed the
 * target back onto it but can never be true, so the ramp never settled on its
 * own: only the clamp to `0..amplitude` after it stopped the gain wandering,
 * and only because the two targets happened to be the clamp's two bounds.
 */
internal class SidetoneSynth(
    private val sampleRate: Int,
    frequencyHz: Double,
    /** The gain the envelope sits at while the key is held. */
    val amplitude: Float,
    rampSeconds: Double
) {
    /** The pitch, changeable while it plays; the phase carries over, so it never clicks. */
    var frequencyHz: Double = frequencyHz
        set(value) {
            if (value == field) return
            field = value
            omega = 2.0 * PI * value / sampleRate
        }
    private var omega = 2.0 * PI * frequencyHz / sampleRate
    /** Per-sample gain step: the full range in [rampSeconds]. */
    val rampStep = (amplitude / (rampSeconds * sampleRate)).toFloat()
    private var phase = 0.0

    /** Where the envelope is now: 0 in silence, [amplitude] with the key held. */
    var gain = 0f
        private set

    /** Fill the first [count] samples of [buf], ramping toward [keyDown]'s target. */
    fun render(buf: FloatArray, count: Int, keyDown: Boolean) {
        val target = if (keyDown) amplitude else 0f
        for (i in 0 until count) {
            gain += (target - gain).coerceIn(-rampStep, rampStep)
            buf[i] = (sin(phase) * gain).toFloat()
            phase += omega
            if (phase > TWO_PI) phase -= TWO_PI
        }
    }

    private companion object {
        const val TWO_PI = 2.0 * PI
    }
}
