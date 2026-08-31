package app.anothermorsetrainer.morsekit

/**
 * Converts a words-per-minute (WPM) speed into the precise tone and gap
 * durations used to key Morse code.
 *
 * The standard reference word is "PARIS", which is exactly 50 time units
 * long. So at a given WPM, there are `50 * WPM` units per minute, which
 * makes one unit (one "dit") = `1200 / WPM` milliseconds.
 *
 * Standard ("PARIS") timing, in dit units:
 *   - dit tone .............. 1
 *   - dah tone .............. 3
 *   - gap between elements .. 1   (inside a single character)
 *   - gap between characters  3
 *   - gap between words ..... 7
 *
 * Hand-translated from MorseKit/Timing.swift. Swift `struct` → Kotlin `class`;
 * the two Swift initializers become a secondary constructor + a `farnsworth`
 * factory. Durations are in seconds (Swift's `TimeInterval` is just `Double`).
 */
class MorseTiming private constructor(
    /** Character speed — how fast the dits/dahs within a character are sent. */
    val wpm: Double,
    /**
     * Effective (Farnsworth) speed — the overall WPM, achieved by stretching
     * the gaps *between* characters and words. Must be ≤ [wpm]. When equal,
     * timing is standard (no extra spacing).
     */
    val effectiveWpm: Double
) {
    /** Standard timing — character and effective speed are the same. */
    constructor(wpm: Double) : this(wpm, wpm)

    companion object {
        /** Farnsworth: characters at [characterWpm], spacing stretched to [effectiveWpm]. */
        fun farnsworth(characterWpm: Double, effectiveWpm: Double): MorseTiming =
            MorseTiming(characterWpm, minOf(effectiveWpm, characterWpm))
    }

    /** Duration of one dit, in seconds (at character speed). */
    val unit: Double get() = (1200.0 / wpm) / 1000.0

    val dit: Double get() = unit
    val dah: Double get() = 3 * unit
    val elementGap: Double get() = unit       // between dits/dahs in a char

    /**
     * One unit of inter-character / word spacing. With Farnsworth this is
     * larger than [unit]. Derived from the ARRL formula: a standard "PARIS"
     * word is 50 units, of which 31 are character elements and 19 are spacing;
     * the extra time needed to hit the effective speed is spread over those 19.
     */
    val spacingUnit: Double
        get() {
            if (effectiveWpm >= wpm) return unit
            val value = (60.0 / effectiveWpm - 37.2 / wpm) / 19.0
            return maxOf(unit, value)
        }

    val characterGap: Double get() = 3 * spacingUnit
    val wordGap: Double get() = 7 * spacingUnit

    /**
     * Total time to play a single character, from the start of its first
     * tone to the end of its last tone (no trailing gap).
     */
    fun duration(character: Char): Double {
        val elements = MorseCode.elements(character)
        if (elements.isEmpty()) return 0.0
        val toneTime = elements.fold(0.0) { acc, e ->
            acc + if (e == MorseCode.Element.DIT) dit else dah
        }
        val gapTime = (elements.size - 1) * elementGap
        return toneTime + gapTime
    }

    override fun equals(other: Any?): Boolean =
        other is MorseTiming && other.wpm == wpm && other.effectiveWpm == effectiveWpm

    override fun hashCode(): Int = 31 * wpm.hashCode() + effectiveWpm.hashCode()
}
