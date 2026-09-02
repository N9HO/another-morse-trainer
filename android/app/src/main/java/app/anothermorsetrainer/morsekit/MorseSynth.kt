package app.anothermorsetrainer.morsekit

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a playable into Morse audio **one sample at a time**, without ever
 * materialising the whole sound.
 *
 * Hand-translated from MorseKit/MorseSynth.swift. Swift `struct` → Kotlin
 * `class`; the nested `Cursor` stays a small mutable holder the caller owns.
 *
 * This used to live inside `MorsePlayer.render()` in the app package, returning
 * a whole `FloatArray`. That had two problems, and moving it here fixes both:
 *
 *  1. **Memory and the main thread.** A Code Exam at novice speed is roughly 22
 *     million samples — ~88 MB — and [MorsePlayer] built it *and* blocking-wrote
 *     it to a `MODE_STATIC` track from the calling thread, which is the main
 *     thread. Story and Exam at slow effective speeds were the hot spot for
 *     exactly that reason. A [Cursor] walked by the feeder thread needs none of
 *     it: the segment list is a few hundred entries whatever the passage length.
 *  2. **Testability.** In the app package the synthesis maths could not be
 *     reached by a unit test. Here it can, and `fixtures/render.json` pins it
 *     against the Swift twin.
 */
class MorseSynth(
    val segments: List<Segment>,
    val sampleRate: Double,
    frequency: Double,
    val amplitude: Float = 0.9f,
    rampSeconds: Double = 0.005
) {

    /**
     * One keyed element: a tone, then the silence that follows it. Lengths are
     * in **samples**, converted once up front, because the feeder must not be
     * doing seconds-to-samples arithmetic per frame — and because truncation
     * has to happen exactly once, or the total and the walk disagree.
     */
    data class Segment(val toneSamples: Int, val gapSamples: Int) {
        init {
            require(toneSamples >= 0 && gapSamples >= 0) { "segment lengths must be non-negative" }
        }
    }

    /** Where playback has got to. Cheap to copy; the feeder keeps one. */
    data class Cursor(var segmentIndex: Int = 0, var sampleInSegment: Int = 0)

    /**
     * Radians per sample. Phase restarts at zero for every tone, matching the
     * pre-rendering version exactly — the ramps make the discontinuity
     * inaudible, and resetting keeps a tone's waveform independent of what came
     * before it.
     */
    val omega: Double = 2.0 * PI * frequency / sampleRate

    /** Full raised-cosine ramp length, before the short-tone clamp below. */
    val fullRampSamples: Int = maxOf(1, (rampSeconds * sampleRate).toInt())

    /** Total length of the whole playable, in samples. */
    val totalSamples: Int = segments.sumOf { it.toneSamples + it.gapSamples }

    companion object {
        /**
         * A synth with nothing to play — the feeder's resting value, so it never
         * has to null-check per sample.
         */
        val SILENT = MorseSynth(emptyList(), 44_100.0, 600.0)

        /**
         * Build the segment list for a playable at a given timing.
         *
         * Seconds are converted to samples here, once, with the same truncation
         * the pre-rendering version used, so the sound is sample-identical.
         */
        fun segments(
            playable: MorseItem.Playable,
            timing: MorseTiming,
            sampleRate: Double
        ): List<Segment> {
            fun toSamples(seconds: Double): Int = (seconds * sampleRate).toInt()

            val out = ArrayList<Segment>()
            fun withGaps(elements: List<MorseCode.Element>, interElement: Double, trailing: Double) {
                elements.forEachIndexed { i, el ->
                    val tone = if (el == MorseCode.Element.DIT) timing.dit else timing.dah
                    val gap = if (i == elements.size - 1) trailing else interElement
                    out.add(Segment(toSamples(tone), toSamples(gap)))
                }
            }

            when (playable) {
                is MorseItem.Playable.Pattern -> {
                    val els = playable.value.map {
                        if (it == '.') MorseCode.Element.DIT else MorseCode.Element.DAH
                    }
                    withGaps(els, timing.elementGap, 0.0)
                }
                is MorseItem.Playable.Text -> {
                    val chars = playable.value.toList()
                    for ((ci, ch) in chars.withIndex()) {
                        // A space is a word gap: stretch the previous character's
                        // trailing gap to a full word gap. Only QSO-style
                        // multi-word transmissions contain spaces — single tokens
                        // are unaffected.
                        if (ch == ' ') {
                            if (out.isNotEmpty()) {
                                val last = out.removeAt(out.size - 1)
                                out.add(Segment(last.toneSamples, toSamples(timing.wordGap)))
                            }
                            continue
                        }
                        val els = MorseCode.elements(ch)
                        if (els.isEmpty()) continue
                        val afterChar = if (ci == chars.size - 1) 0.0 else timing.characterGap
                        withGaps(els, timing.elementGap, afterChar)
                    }
                }
            }
            return out
        }

        /** Convenience: build a synth straight from a playable. */
        fun forPlayable(
            playable: MorseItem.Playable,
            timing: MorseTiming,
            sampleRate: Double,
            frequency: Double,
            amplitude: Float = 0.9f,
            rampSeconds: Double = 0.005
        ): MorseSynth = MorseSynth(
            segments(playable, timing, sampleRate),
            sampleRate, frequency, amplitude, rampSeconds
        )
    }

    /**
     * True once [cursor] has nothing left to emit.
     *
     * Deliberately not `segmentIndex >= segments.size`: after exactly
     * [totalSamples] calls to [next] the cursor sits *at the end of* the last
     * segment, not past it, because [next] only advances the index on its way
     * in. Asking the question properly — is there any sample left anywhere
     * ahead? — is what makes "played to completion" testable.
     */
    fun isFinished(cursor: Cursor): Boolean {
        var index = cursor.segmentIndex
        var offset = cursor.sampleInSegment
        while (index < segments.size) {
            val s = segments[index]
            if (offset < s.toneSamples + s.gapSamples) return false
            index += 1
            offset = 0
        }
        return true
    }

    /**
     * Next sample, advancing [cursor]. Returns 0 once finished, so a caller that
     * keeps asking simply gets silence rather than having to check.
     *
     * Allocates nothing: this runs per frame on the audio feeder thread.
     */
    fun next(cursor: Cursor): Float {
        // Skip past any zero-length segments so a degenerate program (a
        // non-positive duration truncating to nothing) cannot stall the walk.
        while (cursor.segmentIndex < segments.size) {
            val s = segments[cursor.segmentIndex]
            if (cursor.sampleInSegment < s.toneSamples + s.gapSamples) break
            cursor.segmentIndex += 1
            cursor.sampleInSegment = 0
        }
        if (cursor.segmentIndex >= segments.size) return 0f

        val segment = segments[cursor.segmentIndex]
        val n = cursor.sampleInSegment
        cursor.sampleInSegment += 1

        if (n >= segment.toneSamples) return 0f   // in the trailing gap
        return sample(n, segment.toneSamples)
    }

    /**
     * One tone sample: a sine under a raised-cosine envelope.
     *
     * The ramp is clamped to half the tone so rise and fall can never overlap.
     * Past ~120 WPM a dit is shorter than two 5 ms ramps, and without the clamp
     * the fall is skipped entirely and the tone ends at full amplitude — an
     * audible click (issue #79). At every speed the app offers this is the
     * unchanged 5 ms.
     */
    private fun sample(n: Int, toneSamples: Int): Float {
        val rampSamples = maxOf(1, minOf(fullRampSamples, toneSamples / 2))
        var amp = amplitude.toDouble()
        if (n < rampSamples) {
            amp *= 0.5 * (1 - cos(PI * n / rampSamples))
        } else if (n >= toneSamples - rampSamples) {
            val m = toneSamples - n
            amp *= 0.5 * (1 - cos(PI * m / rampSamples))
        }
        return (amp * sin(omega * n)).toFloat()
    }

    /**
     * Materialise the whole thing. Only for callers that genuinely need a
     * buffer — the pileup mixer, and the fixture checks. The streaming path
     * exists precisely so this is not on the hot path.
     */
    fun renderAll(): FloatArray {
        val out = FloatArray(totalSamples)
        val cursor = Cursor()
        for (i in 0 until totalSamples) out[i] = next(cursor)
        return out
    }
}
