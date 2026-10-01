package app.anothermorsetrainer.vail

import app.anothermorsetrainer.MainHandler
import app.anothermorsetrainer.PcmOut
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Plays received tone bursts at their scheduled local-clock time. Each burst is
 * a short rendered sine with click-free ramps, and bursts from several
 * operators mix naturally. The RX counterpart of the local sidetone; mirrors
 * the iOS `KeyerEngine` `scheduleReceivedTone` path (Hz per MIDI note).
 *
 * Desktop: the Android port gives each burst its own static `AudioTrack`. A
 * Java Sound mixer has a small, device-dependent number of lines, and opening
 * one per dit would run out mid-QSO, so here one streaming line is kept open
 * and a feeder thread sums whatever bursts are sounding into it. The schedule
 * (a burst starts when its time comes round on the UI thread) is unchanged.
 */
class RepeaterTonePlayer {

    private val sampleRate = 44_100
    private val amplitude = 0.5f
    private val rampSeconds = 0.005
    private val main = MainHandler()
    private val pending = mutableListOf<Runnable>()

    private class Burst(val samples: FloatArray) { var cursor = 0 }

    /** Sounding now; the feeder thread and the UI thread both touch it, under its own lock. */
    private val sounding = mutableListOf<Burst>()
    @Volatile private var running = false
    private var feeder: Thread? = null

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
        synchronized(sounding) { sounding.add(Burst(buf)) }
        ensureFeeder()
    }

    @Synchronized
    private fun ensureFeeder() {
        if (running && feeder != null) return
        val line = PcmOut.open(sampleRate, CHUNK * 4) ?: return   // no output: silent, as on Android
        running = true
        feeder = Thread { feed(line) }.apply { isDaemon = true; name = "amt-repeater-rx"; start() }
    }

    private fun feed(line: PcmOut) {
        try { Thread.currentThread().priority = Thread.MAX_PRIORITY } catch (_: SecurityException) {}
        val buf = FloatArray(CHUNK)
        try {
            while (running) {
                java.util.Arrays.fill(buf, 0f)
                synchronized(sounding) {
                    val it = sounding.iterator()
                    while (it.hasNext()) {
                        val b = it.next()
                        var i = 0
                        while (i < CHUNK && b.cursor < b.samples.size) {
                            buf[i] += b.samples[b.cursor]
                            b.cursor += 1
                            i += 1
                        }
                        if (b.cursor >= b.samples.size) it.remove()
                    }
                }
                if (!line.write(buf, CHUNK)) break
            }
        } finally {
            line.close()
            synchronized(this) { running = false; feeder = null }
        }
    }

    fun release() {
        pending.forEach { main.removeCallbacks(it) }
        pending.clear()
        main.removeCallbacksAndMessages(null)
        synchronized(sounding) { sounding.clear() }
        running = false
        feeder?.let { runCatching { it.join(250) } }
        feeder = null
    }

    private companion object {
        /** ~12 ms at 44.1 kHz, as the Morse player uses. */
        const val CHUNK = 512
    }
}
