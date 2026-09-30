package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timing, pinned against `fixtures/timing.json` at the repo root — the same file
 * the iOS `MorseKitCheck` harness reads.
 *
 * This used to be hand-written Kotlin described as a "twin" of the Swift
 * section, and the two had already drifted: this side swept all 56 speeds and
 * pinned the effective-above-character clamp, and the Swift side pinned neither.
 * Copying test *code* between the trees is what failed; sharing test *data*
 * fixes the mechanism. The fixture is data, so CLAUDE.md's two-ports rule still
 * holds — there is no shared module here, just a JSON file each tree reads in
 * its own idiom.
 *
 * The expected values were derived from the documented ARRL formulas
 * independently of either implementation, so both ports drifting the same way
 * still fails.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class MorseTimingTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("timing.json")
        assertNotNull("fixtures/timing.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private val durationTolerance: Double get() = fixture.getJSONObject("tolerance").getDouble("duration")
    private val parisTolerance: Double get() = fixture.getJSONObject("tolerance").getDouble("paris")

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
    fun `standard timing matches the shared fixture`() {
        val cases = fixture.getJSONArray("standard")
        assertTrue("fixture has no standard cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val wpm = c.getDouble("wpm")
            val t = MorseTiming(wpm)
            val at = "$wpm WPM"
            assertEquals("$at unit", c.getDouble("unit"), t.unit, durationTolerance)
            assertEquals("$at dit", c.getDouble("dit"), t.dit, durationTolerance)
            assertEquals("$at dah", c.getDouble("dah"), t.dah, durationTolerance)
            assertEquals("$at elementGap", c.getDouble("elementGap"), t.elementGap, durationTolerance)
            assertEquals("$at spacingUnit", c.getDouble("spacingUnit"), t.spacingUnit, durationTolerance)
            assertEquals("$at characterGap", c.getDouble("characterGap"), t.characterGap, durationTolerance)
            assertEquals("$at wordGap", c.getDouble("wordGap"), t.wordGap, durationTolerance)
            assertEquals("$at PARIS", c.getDouble("parisSeconds"), parisDuration(t), parisTolerance)
        }
    }

    @Test
    fun `PARIS keeps its 50-unit definition across the whole range`() {
        val sweep = fixture.getJSONArray("parisSweep")
        assertTrue("fixture has no PARIS sweep", sweep.length() > 0)
        for (i in 0 until sweep.length()) {
            val c = sweep.getJSONObject(i)
            val wpm = c.getDouble("wpm")
            assertEquals(
                "PARIS at $wpm WPM",
                c.getDouble("seconds"), parisDuration(MorseTiming(wpm)), parisTolerance
            )
        }
    }

    @Test
    fun `Farnsworth matches the shared fixture, clamp included`() {
        val cases = fixture.getJSONArray("farnsworth")
        assertTrue("fixture has no Farnsworth cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val cw = c.getDouble("characterWpm")
            val ew = c.getDouble("effectiveWpm")
            val t = MorseTiming.farnsworth(characterWpm = cw, effectiveWpm = ew)
            val at = "$cw/$ew"
            // An effective speed above the character speed is clamped, not
            // honoured — that is what resolvedEffectiveWpm pins.
            assertEquals("$at effectiveWpm", c.getDouble("resolvedEffectiveWpm"), t.effectiveWpm, durationTolerance)
            assertEquals("$at unit", c.getDouble("unit"), t.unit, durationTolerance)
            assertEquals("$at dit", c.getDouble("dit"), t.dit, durationTolerance)
            assertEquals("$at spacingUnit", c.getDouble("spacingUnit"), t.spacingUnit, durationTolerance)
            assertEquals("$at characterGap", c.getDouble("characterGap"), t.characterGap, durationTolerance)
            assertEquals("$at wordGap", c.getDouble("wordGap"), t.wordGap, durationTolerance)
            assertEquals("$at PARIS", c.getDouble("parisSeconds"), parisDuration(t), parisTolerance)
        }
    }

    @Test
    fun `spacing never drops below one unit`() {
        val sweep = fixture.getJSONArray("spacingFloorSweep")
        assertTrue("fixture has no spacing floor sweep", sweep.length() > 0)
        for (i in 0 until sweep.length()) {
            val c = sweep.getJSONObject(i)
            val t = MorseTiming.farnsworth(c.getDouble("characterWpm"), c.getDouble("effectiveWpm"))
            val at = "effective ${c.getDouble("effectiveWpm")}"
            assertEquals("$at spacingUnit", c.getDouble("spacingUnit"), t.spacingUnit, durationTolerance)
            assertTrue("$at fell below one unit", t.spacingUnit >= t.unit - 1e-12)
        }
    }

    @Test
    fun `character durations match the shared fixture`() {
        val block = fixture.getJSONObject("characterDurations")
        val t = MorseTiming(block.getDouble("wpm"))
        val entries = block.getJSONArray("entries")
        assertTrue("fixture has no character durations", entries.length() > 0)
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val ch = e.getString("character")
            assertEquals("duration of '$ch'", e.getDouble("seconds"), t.duration(ch[0]), durationTolerance)
        }
    }
}
