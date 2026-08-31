package app.anothermorsetrainer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
 * dedicated thread; the only shared state is the [keyDown] flag.
 */
class SidetoneGenerator(frequencyHz: Double = 600.0) {

    private val sampleRate = 44_100
    private val amplitude = 0.5f
    private val rampSeconds = 0.005
    private val omega = 2.0 * PI * frequencyHz / sampleRate

    private val keyDown = AtomicBoolean(false)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun start() {
        if (running) return
        running = true
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
        thread = Thread { renderLoop(t) }.apply { isDaemon = true; start() }
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
    }

    private fun renderLoop(t: AudioTrack) {
        // ~10 ms blocks: small enough that key-down latency is imperceptible.
        val block = sampleRate / 100
        val buf = FloatArray(block)
        val rampStep = (amplitude / (rampSeconds * sampleRate)).toFloat()
        var phase = 0.0
        var gain = 0f
        val twoPi = 2.0 * PI
        while (running) {
            val target = if (keyDown.get()) amplitude else 0f
            for (i in 0 until block) {
                if (gain != target) {
                    gain += if (target > gain) rampStep else -rampStep
                    if ((target > gain && gain > target) || (target < gain && gain < target)) gain = target
                    gain = gain.coerceIn(0f, amplitude)
                }
                buf[i] = (sin(phase) * gain).toFloat()
                phase += omega
                if (phase > twoPi) phase -= twoPi
            }
            try {
                t.write(buf, 0, block, AudioTrack.WRITE_BLOCKING)
            } catch (_: IllegalStateException) {
                break
            }
        }
    }
}
