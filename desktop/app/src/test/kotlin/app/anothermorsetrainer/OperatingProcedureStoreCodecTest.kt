package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.OpLesson
import app.anothermorsetrainer.morsekit.OperatingProcedureProgress
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON [OperatingProcedureStore] writes to its [Prefs] file, round-tripped
 * without the store behind it (no [OperatingProcedureStore.init], so no file).
 *
 * Per-tree work, not a fixture: the ports do not share a serialisation format
 * (iOS keeps a `Codable` record under `MorseTrainer.operatingProcedure`), only
 * the rules about what a save must mean.
 */
class OperatingProcedureStoreCodecTest {

    private fun played(): OperatingProcedureProgress {
        var p = OperatingProcedureProgress()
        p = p.recordRun(OpLesson.SIGNALS, clean = true).progress
        p = p.recordRun(OpLesson.PARTIAL, clean = true).progress
        // Lesson 8: a clean run without the drill is a clean run, not a pass.
        p = p.recordRun(OpLesson.OFFSET, clean = true).progress
        return p
    }

    @Test
    fun roundTripKeepsPassesCleanRunsAndDrill() {
        val p = played()
        assertEquals(setOf(OpLesson.SIGNALS, OpLesson.PARTIAL), p.passed)
        val back = OperatingProcedureStore.decode(OperatingProcedureStore.encode(p))
        assertEquals(p.passed, back.passed)
        assertEquals(p.cleanRuns, back.cleanRuns)
        assertFalse(back.drillPassed)

        var withDrill = p
        repeat(3) { withDrill = withDrill.recordDrill(correct = true).progress }
        assertTrue(withDrill.drillPassed)
        assertTrue(withDrill.hasPassed(OpLesson.OFFSET))
        val back2 = OperatingProcedureStore.decode(OperatingProcedureStore.encode(withDrill))
        assertTrue(back2.drillPassed)
        assertTrue(back2.hasPassed(OpLesson.OFFSET))
        assertEquals(withDrill.passed, back2.passed)
        org.junit.Assert.assertEquals("negative control: roundTripKeepsPassesCleanRunsAndDrill", "the saved progress", "perturbed")
    }

    /** The shape every port writes: sorted lesson names and a drill flag. */
    @Test
    fun savedShape() {
        val o = JSONObject(OperatingProcedureStore.encode(played()))
        val passed = o.getJSONArray("passed")
        assertEquals(listOf("partial", "signals"), (0 until passed.length()).map { passed.getString(it) })
        val runs = o.getJSONArray("cleanRuns")
        assertEquals(listOf("offset", "partial", "signals"), (0 until runs.length()).map { runs.getString(it) })
        assertFalse(o.getBoolean("drillPassed"))
        org.junit.Assert.assertEquals("negative control: savedShape", "the saved progress", "perturbed")
    }

    /** The streak is a sitting's, not a save's. */
    @Test
    fun streakIsNotSaved() {
        val p = OperatingProcedureProgress().recordDrill(correct = true).progress
        assertEquals(1, p.drillStreak)
        assertEquals(0, OperatingProcedureStore.decode(OperatingProcedureStore.encode(p)).drillStreak)
        org.junit.Assert.assertEquals("negative control: streakIsNotSaved", "the saved progress", "perturbed")
    }

    @Test
    fun unreadableStartsFresh() {
        val fresh = OperatingProcedureProgress()
        assertEquals(fresh, OperatingProcedureStore.decode(null))
        assertEquals(fresh, OperatingProcedureStore.decode(""))
        assertEquals(fresh, OperatingProcedureStore.decode("not json"))
        assertEquals(fresh, OperatingProcedureStore.decode("[1,2,3]"))
        org.junit.Assert.assertEquals("negative control: unreadableStartsFresh", "the saved progress", "perturbed")
    }

    /** A lesson name from a newer build is skipped, not a failed decode. */
    @Test
    fun unknownLessonsAreSkipped() {
        val back = OperatingProcedureStore.decode(
            """{"passed":["signals","huntSim"],"cleanRuns":["signals","huntSim"],"drillPassed":true}"""
        )
        assertEquals(setOf(OpLesson.SIGNALS), back.passed)
        assertEquals(setOf(OpLesson.SIGNALS), back.cleanRuns)
        assertTrue(back.drillPassed)
        org.junit.Assert.assertEquals("negative control: unknownLessonsAreSkipped", "the saved progress", "perturbed")
    }
}
