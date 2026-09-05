package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Rapid Fire's contest-exchange kinds (#173). Twin of the "Rapid Fire" section
 * in the iOS repo's MorseKitCheck/main.swift.
 *
 * States can take the ARRL/RAC Field Day sections alongside; serial numbers
 * can go out as cut numbers and are graded as numbers either way; names and
 * power draw from the lists the QSO/exam data already carries.
 */
class RapidFireTest {

    private fun items(config: RapidFireQuiz.Config, seed: Int, n: Int = 200): List<String> {
        val q = RapidFireQuiz(config, Random(seed))
        return (0 until n).map { q.nextDrill().correct }
    }

    private fun sentText(d: Drill): String = (d.playable as MorseItem.Playable.Text).value

    @Test
    fun `the section toggle widens the state pool to the three-letter sections`() {
        val pool = RapidFireQuiz.statePool(includeSections = true)
        assertTrue("EPA is a section, not a state", "EPA" in pool)
        assertTrue("STX is a section, not a state", "STX" in pool)
        assertTrue(ContestData.arrlSections.all { it in pool })
        assertTrue(MorseData.usStates.all { it in pool })
        assertEquals("each abbreviation once", pool.size, pool.toSet().size)

        val drawn = items(RapidFireQuiz.Config(content = RapidFireContent.STATES, statesIncludeSections = true), seed = 1)
        assertTrue("a run of 200 draws sends at least one 3-letter section", drawn.any { it.length == 3 })
        assertTrue(drawn.all { it in pool })
    }

    @Test
    fun `no section appears with the toggle off`() {
        assertEquals(MorseData.usStates, RapidFireQuiz.statePool(includeSections = false))
        val drawn = items(RapidFireQuiz.Config(content = RapidFireContent.STATES), seed = 2)
        assertTrue(drawn.all { it in MorseData.usStates })
        assertTrue(drawn.none { it.length == 3 })
    }

    @Test
    fun `serials are 001 to 999 and cut numbers render the digits as letters`() {
        val plain = RapidFireQuiz(RapidFireQuiz.Config(content = RapidFireContent.SERIALS), Random(3))
        repeat(100) {
            val d = plain.nextDrill()
            assertEquals(3, d.correct.length)
            assertTrue(d.correct.all { it.isDigit() })
            assertTrue(d.correct.toInt() in 1..999)
            assertEquals("sent as-is without the toggle", d.correct, sentText(d))
        }

        val cut = RapidFireQuiz(RapidFireQuiz.Config(content = RapidFireContent.SERIALS, serialCutNumbers = true), Random(4))
        var sawCutLetter = false
        repeat(100) {
            val d = cut.nextDrill()
            val expected = CutNumbers.encode(d.correct, CutNumbers.cuttableDigits.toSet())
            assertEquals(expected, sentText(d))
            assertEquals("the answer stays the true digits", d.correct, d.revealPrimary)
            if (expected != d.correct) {
                sawCutLetter = true
                assertEquals("sent as $expected", d.revealSecondary)
            }
        }
        assertTrue(sawCutLetter)
        assertEquals("Serial numbers (cut)", cut.summary)
        // 4 and 6 have no cut form; every other digit becomes its letter.
        assertEquals("TTA", CutNumbers.encode("001", CutNumbers.cuttableDigits.toSet()))
        assertEquals("4N6", CutNumbers.encode("496", CutNumbers.cuttableDigits.toSet()))
    }

    @Test
    fun `a serial is graded correct in either form`() {
        for (serial in listOf(true, false)) {
            val q = RapidFireQuiz(RapidFireQuiz.Config(content = RapidFireContent.SERIALS, serialCutNumbers = serial), Random(5))
            repeat(60) {
                val d = q.nextDrill()
                val digits = d.correct
                val cut = CutNumbers.encode(digits, CutNumbers.cuttableDigits.toSet())
                assertTrue("digits copy $digits", q.record(digits, 0.0).correct)
                assertTrue("cut copy $cut for $digits", q.record(cut, 0.0).correct)
                assertTrue("unpadded copy for $digits", q.record(digits.toInt().toString(), 0.0).correct)
                assertTrue("lower-case cut copy", q.record(cut.lowercase(), 0.0).correct)
                val wrong = ((digits.toInt() % 999) + 1).toString().padStart(3, '0')
                assertFalse("$wrong is not $digits", q.record(wrong, 0.0).correct)
                assertFalse(q.record("", 0.0).correct)
            }
        }
        // A number group is still graded as text: cut letters are not a copy of it.
        val n = RapidFireQuiz(RapidFireQuiz.Config(content = RapidFireContent.NUMBERS, numberCount = 3), Random(6))
        var group = n.nextDrill().correct
        while (group.all { it == '4' || it == '6' }) group = n.nextDrill().correct   // no cut form to differ by
        assertFalse(n.record(CutNumbers.encode(group, CutNumbers.cuttableDigits.toSet()), 0.0).correct)
    }

    @Test
    fun `names and power draw from the shared lists`() {
        val names = items(RapidFireQuiz.Config(content = RapidFireContent.NAMES), seed = 7)
        assertTrue(names.all { it in MorseData.opNames })
        assertTrue("more than one name over a run", names.toSet().size > 1)

        val powers = items(RapidFireQuiz.Config(content = RapidFireContent.POWER), seed = 8)
        assertTrue(powers.all { it in ExamData.powers })
        assertTrue("more than one power level over a run", powers.toSet().size > 1)

        val q = RapidFireQuiz(RapidFireQuiz.Config(content = RapidFireContent.POWER), Random(9))
        val d = q.nextDrill()
        assertTrue(q.record(d.correct.lowercase(), 0.0).correct)
        assertEquals("Power levels", q.summary)
        assertEquals("Names", RapidFireQuiz(RapidFireQuiz.Config(content = RapidFireContent.NAMES)).summary)
    }

    @Test
    fun `every kind carries a label and a one-line blurb`() {
        for (c in RapidFireContent.entries) {
            assertTrue(c.label.isNotBlank())
            assertTrue(c.blurb.isNotBlank())
            assertFalse(c.blurb.contains('\n'))
        }
        assertEquals(listOf("Call signs", "Words", "Number groups", "State abbreviations", "Serial numbers", "Names", "Power", "Mixed"),
            RapidFireContent.entries.map { it.label })
    }
}
