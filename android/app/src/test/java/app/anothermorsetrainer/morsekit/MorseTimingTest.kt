package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timing at the raised 60 WPM ceiling (issue #79). Twin of the `Timing @ 60 WPM`
 * section in MorseKitCheck/main.swift.
 */
class MorseTimingTest {

    /**
     * "PARIS" plus its trailing word gap — 50 units by definition (31 of
     * character elements, 12 of inter-character gaps, 7 of word gap), so at
     * N WPM this must come to exactly 60/N seconds.
     */
    private fun parisDuration(timing: MorseTiming): Double {
        val word = "PARIS"
        var total = word.sumOf { timing.duration(it) }
        total += (word.length - 1) * timing.characterGap
        return total + timing.wordGap
    }

    @Test
    fun `standard timing at 60 WPM`() {
        val fast = MorseTiming(60.0)
        assertEquals(0.020, fast.dit, 1e-9)
        assertEquals(0.060, fast.dah, 1e-9)
        assertEquals(fast.unit, fast.spacingUnit, 1e-9)
        assertEquals("PARIS takes one second at 60 WPM", 1.0, parisDuration(fast), 1e-6)
    }

    @Test
    fun `the whole 5 to 60 range keeps the PARIS definition`() {
        for (wpm in 5..60) {
            val timing = MorseTiming(wpm.toDouble())
            assertEquals("$wpm WPM", 60.0 / wpm, parisDuration(timing), 1e-6)
        }
    }

    @Test
    fun `Farnsworth still works from the new ceiling`() {
        val farns = MorseTiming.farnsworth(characterWpm = 60.0, effectiveWpm = 20.0)
        // Characters stay at full speed; only the gaps stretch.
        assertEquals(0.020, farns.dit, 1e-9)
        assertTrue(farns.spacingUnit > farns.unit)
        assertEquals((60.0 / 20 - 37.2 / 60) / 19, farns.spacingUnit, 1e-9)
    }

    @Test
    fun `effective equal to character collapses to standard timing`() {
        val same = MorseTiming.farnsworth(characterWpm = 60.0, effectiveWpm = 60.0)
        assertEquals(MorseTiming(60.0).unit, same.spacingUnit, 1e-9)
    }

    @Test
    fun `spacing never drops below one unit`() {
        // Just under the character speed the ARRL formula lands close to a plain
        // unit; it must never come out shorter than one.
        for (effective in 5..60) {
            val t = MorseTiming.farnsworth(60.0, effective.toDouble())
            assertTrue("effective $effective", t.spacingUnit >= t.unit - 1e-12)
        }
    }

    @Test
    fun `an effective speed above the character speed is clamped, not honoured`() {
        val t = MorseTiming.farnsworth(characterWpm = 60.0, effectiveWpm = 90.0)
        assertEquals(60.0, t.effectiveWpm, 1e-9)
        assertEquals(t.unit, t.spacingUnit, 1e-9)
    }
}
