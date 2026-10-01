package app.anothermorsetrainer.morsekit

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns microphone audio into key-down / key-up edges with sample-accurate
 * timestamps, so a tone keyed on anything the phone can hear — a practice
 * oscillator, a keyer's sidetone, a rig's speaker, a signal generator — can
 * feed the Sending Analyzer like a key (#234, #235).
 *
 * The ported CW decoder core (morsekit/cw) already hears tones, but it reports
 * text and a tone-present flag, not edge times, and it is not to be modified;
 * timing feedback needs the edges themselves. So this is a separate, small
 * detector that runs on the same kind of capture. The spec is
 * `fixtures/sending-analysis.json` (`toneDetector.derivation`); hand-translated
 * from MorseKit/ToneKeyingDetector.swift.
 *
 * Per block of [BLOCK_MS] (4 ms):
 *  1. Goertzel magnitude (normalised to sine amplitude, 2|X|/N) at every
 *     candidate pitch from 300 to 1200 Hz in 50 Hz steps, or only at the
 *     locked pitch once there is one. The level is the largest.
 *  2. The noise floor follows the level while the key is up (drops at half the
 *     difference per block, rises at 0.2 %), frozen while it is down.
 *  3. The peak is the loudest block of the current or last confirmed tone,
 *     decaying toward the floor (0.3 % per block); raised only while down.
 *  4. Raw "on" when level > max(floor·snr, 0.002, floor + ½(peak − floor));
 *     raw "off" below max(floor·snr·0.7, 0.0015, floor + 0.35(peak − floor)).
 *  5. A change must hold for two blocks (8 ms); the edge is stamped at the
 *     start of the first block of the new state.
 *  6. While unlocked, each confirmed-on block votes for its loudest pitch; the
 *     first to reach 12 votes is locked.
 *
 * Not thread-safe: one capture thread owns it.
 */
class ToneKeyingDetector(
    val sampleRate: Double,
    /** Required peak-to-floor ratio (≈ 12 dB at 4). Lower hears quieter tones and more noise. */
    var snr: Double = 4.0,
    pitchHz: Double? = null
) {
    data class Edge(val isDown: Boolean, val timeMs: Double)

    val blockSamples: Int = maxOf(16, (sampleRate * BLOCK_MS / 1000).roundToInt())
    private val pitches: List<Double> = pitchHz?.let { listOf(it) } ?: CANDIDATE_PITCHES
    private val coeffs = DoubleArray(pitches.size) { 2 * cos(2 * PI * pitches[it] / sampleRate) }
    private val s1 = DoubleArray(pitches.size)
    private val s2 = DoubleArray(pitches.size)
    private val votes = IntArray(pitches.size)
    private var lockedIndex: Int? = if (pitchHz != null) 0 else null

    /** The pitch in use: the locked one, else the loudest candidate lately. */
    var pitchHz: Double? = pitchHz
        private set
    val isLocked: Boolean get() = lockedIndex != null
    /** Level of the last block (sine amplitude, 0…1). */
    var level: Double = 0.0
        private set
    var noiseFloor: Double = 0.0
        private set
    /** Whether the key is (confirmed) down. */
    var isDown: Boolean = false
        private set

    private var fill = 0
    private var blockIndex = 0
    private var peak = 0.0
    private var started = false
    private var pendingState: Boolean? = null
    private var pendingSince = 0

    /** Feed [count] samples (nominal full scale ±1). Returns the edges they confirm, oldest first. */
    fun process(samples: FloatArray, count: Int = samples.size): List<Edge> {
        val edges = ArrayList<Edge>(2)
        for (i in 0 until count) {
            val raw = samples[i]
            val x = if (raw.isFinite()) raw.coerceIn(-1f, 1f).toDouble() else 0.0
            val li = lockedIndex
            if (li != null) {
                val s0 = x + coeffs[li] * s1[li] - s2[li]
                s2[li] = s1[li]; s1[li] = s0
            } else {
                for (b in coeffs.indices) {
                    val s0 = x + coeffs[b] * s1[b] - s2[b]
                    s2[b] = s1[b]; s1[b] = s0
                }
            }
            fill++
            if (fill == blockSamples) {
                finishBlock()?.let { edges.add(it) }
                fill = 0
            }
        }
        return edges
    }

    private fun finishBlock(): Edge? {
        val n = blockSamples.toDouble()
        var best = 0.0
        val locked = lockedIndex
        var bestIndex = locked ?: 0
        val range = if (locked != null) locked..locked else coeffs.indices
        for (b in range) {
            val power = maxOf(0.0, s1[b] * s1[b] + s2[b] * s2[b] - coeffs[b] * s1[b] * s2[b])
            val mag = 2 * sqrt(power) / n
            if (mag > best) { best = mag; bestIndex = b }
            s1[b] = 0.0; s2[b] = 0.0
        }
        level = best
        blockIndex++

        if (!started) {
            started = true
            noiseFloor = best
            peak = best
            return null
        }
        if (!isDown) {
            noiseFloor += (best - noiseFloor) * (if (best < noiseFloor) 0.5 else 0.002)
        } else {
            peak = maxOf(peak, best)
        }
        peak = maxOf(noiseFloor, peak - (peak - noiseFloor) * 0.003)

        val onThreshold = maxOf(noiseFloor * snr, ABSOLUTE_ON, noiseFloor + 0.5 * (peak - noiseFloor))
        val offThreshold = maxOf(noiseFloor * snr * 0.7, ABSOLUTE_OFF, noiseFloor + 0.35 * (peak - noiseFloor))
        val raw = if (isDown) best >= offThreshold else best > onThreshold

        var edge: Edge? = null
        if (raw == isDown) {
            pendingState = null
        } else if (pendingState == raw) {
            // Second block in the new state: confirm, stamped at the first.
            isDown = raw
            pendingState = null
            edge = Edge(raw, pendingSince * n * 1000 / sampleRate)
            if (raw) peak = maxOf(peak, best)
        } else {
            pendingState = raw
            pendingSince = blockIndex - 1
        }

        if (isDown && lockedIndex == null) {
            votes[bestIndex] += 1
            pitchHz = pitches[bestIndex]
            if (votes[bestIndex] >= LOCK_VOTES) lockedIndex = bestIndex
        }
        return edge
    }

    /** Back to a fresh start: floor re-learned, pitch search re-armed (unless set by hand). */
    fun reset() {
        s1.fill(0.0); s2.fill(0.0); votes.fill(0)
        fill = 0
        blockIndex = 0
        started = false
        isDown = false
        pendingState = null
        level = 0.0
        noiseFloor = 0.0
        peak = 0.0
        if (pitches.size > 1) {
            lockedIndex = null
            pitchHz = null
        }
    }

    companion object {
        const val BLOCK_MS = 4.0
        val CANDIDATE_PITCHES: List<Double> = (0..18).map { 300.0 + 50.0 * it }
        const val LOCK_VOTES = 12
        private const val ABSOLUTE_ON = 0.002
        private const val ABSOLUTE_OFF = 0.0015
    }
}
