package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.OpLesson
import app.anothermorsetrainer.morsekit.OperatingProcedure
import app.anothermorsetrainer.morsekit.OperatingProcedureProgress
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON [OperatingProcedureStore] writes to SharedPreferences,
 * round-tripped without a Context. Per-tree work, not a fixture: iOS keeps a
 * `Codable` snapshot under `MorseTrainer.operatingProcedure`; what the trees
 * share is only what a save must mean — passed lessons, clean runs and the
 * drill survive, the drill's in-a-row streak does not.
 */
class OperatingProcedureStoreCodecTest {

    @Test
    fun passedLessonsCleanRunsAndTheDrillSurvive() {
        var p = OperatingProcedureProgress()
        p = p.recordRun(OpLesson.SIGNALS, clean = true).progress
        p = p.recordRun(OpLesson.OFFSET, clean = true).progress
        repeat(OperatingProcedure.ZERO_BEAT_STREAK_TO_PASS) { p = p.recordDrill(correct = true).progress }
        p = p.recordRun(OpLesson.WHEN, clean = false).progress
        p = p.recordDrill(correct = true).progress
        assertEquals(1, p.drillStreak)

        val json = OperatingProcedureStore.encode(p)
        val back = OperatingProcedureStore.decode(json)
        assertEquals(setOf(OpLesson.SIGNALS, OpLesson.OFFSET), back.passed)
        assertEquals(setOf(OpLesson.SIGNALS, OpLesson.OFFSET), back.cleanRuns)
        assertTrue(back.drillPassed)
        assertEquals("the streak is not saved", 0, back.drillStreak)

        // The documented shape: sorted names, a boolean.
        val o = JSONObject(json)
        assertEquals("[\"offset\",\"signals\"]", o.getJSONArray("passed").toString())
        assertEquals("[\"offset\",\"signals\"]", o.getJSONArray("cleanRuns").toString())
        assertTrue(o.getBoolean("drillPassed"))
        org.junit.Assert.assertEquals("negative control: passedLessonsCleanRunsAndTheDrillSurvive", "the saved progress", "perturbed")
    }

    @Test
    fun aCleanRunWithoutAPassSurvives() {
        val p = OperatingProcedureProgress().recordRun(OpLesson.OFFSET, clean = true).progress
        val back = OperatingProcedureStore.decode(OperatingProcedureStore.encode(p))
        assertTrue(back.passed.isEmpty())
        assertEquals(setOf(OpLesson.OFFSET), back.cleanRuns)
        assertFalse(back.drillPassed)
        org.junit.Assert.assertEquals("negative control: aCleanRunWithoutAPassSurvives", "the saved progress", "perturbed")
    }

    @Test
    fun missingOrBrokenSavesStartFresh() {
        for (raw in listOf(null, "", "not json", "{}", "[]", """{"passed":["nope"],"cleanRuns":7,"drillPassed":"x"}""")) {
            val p = OperatingProcedureStore.decode(raw)
            assertTrue("'$raw' decodes as a fresh start", p.passed.isEmpty() && p.cleanRuns.isEmpty())
            assertFalse(p.drillPassed)
            assertEquals(OpLesson.SIGNALS, p.nextLesson)
        }
        org.junit.Assert.assertEquals("negative control: missingOrBrokenSavesStartFresh", "the saved progress", "perturbed")
    }

    @Test
    fun unknownLessonNamesAreSkipped() {
        val p = OperatingProcedureStore.decode("""{"passed":["signals","fromANewerBuild"],"cleanRuns":["signals"],"drillPassed":false}""")
        assertEquals(setOf(OpLesson.SIGNALS), p.passed)
        assertEquals(OpLesson.WHEN, p.nextLesson)
        org.junit.Assert.assertEquals("negative control: unknownLessonNamesAreSkipped", "the saved progress", "perturbed")
    }
}
