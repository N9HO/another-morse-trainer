package app.anothermorsetrainer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
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
 * dedicated thread; the only shared state is the [keyDown] flag. The samples
 * themselves come from a [SidetoneSynth], which knows nothing about the track.
 */
class SidetoneGenerator(private val frequencyHz: Double = 600.0) {

    private val sampleRate = 44_100
    private val amplitude = 0.5f
    private val rampSeconds = 0.005

    private val keyDown = AtomicBoolean(false)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun start() {
        if (running) return
        running = true
        AudioFocus.acquire(this)
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(2048)
        val t = AudioTrack.Builder()
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
            .setBufferSizeInBytes(minBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        thread = Thread { renderLoop(t) }.apply { isDaemon = true; name = "amt-sidetone"; start() }
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
        track?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        track = null
        AudioFocus.release(this)
    }

    private fun renderLoop(t: AudioTrack) {
        // Parked in a blocking write nearly all the time; the priority is for
        // the moments it wakes, so a 10 ms block is not queued behind a Compose
        // frame and the key-down is heard when it happens. A refused priority
        // is the old behaviour, not a failure.
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: SecurityException) {}
        // ~10 ms blocks: small enough that key-down latency is imperceptible.
        val block = sampleRate / 100
        val buf = FloatArray(block)
        val synth = SidetoneSynth(sampleRate, frequencyHz, amplitude, rampSeconds)
        while (running) {
            synth.render(buf, block, keyDown.get())
            try {
                t.write(buf, 0, block, AudioTrack.WRITE_BLOCKING)
            } catch (_: IllegalStateException) {
                break
            }
        }
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
    sampleRate: Int,
    frequencyHz: Double,
    /** The gain the envelope sits at while the key is held. */
    val amplitude: Float,
    rampSeconds: Double
) {
    private val omega = 2.0 * PI * frequencyHz / sampleRate
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
