package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.FirstFour
import app.anothermorsetrainer.morsekit.FirstFourPhase
import app.anothermorsetrainer.morsekit.FirstFourProgress
import app.anothermorsetrainer.morsekit.FirstFourStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON [FirstFourStore] writes to its [Prefs] file, round-tripped
 * without the store behind it. Per-tree work, not a fixture: iOS keeps a `Codable`
 * snapshot under `MorseTrainer.firstFour`; what the two share is only what a
 * save must mean — passed stages and clean runs survive, the in-phase streak
 * does not.
 */
class FirstFourStoreCodecTest {

    @Test
    fun passedStagesAndCleanRunsSurvive() {
        val p = FirstFourProgress()
        repeat(FirstFour.COPY_STREAK_TO_PASS) { p.recordElement(FirstFourStage.CALL, FirstFourPhase.COPY, true) }
        repeat(FirstFour.SEND_STREAK_TO_PASS) { p.recordElement(FirstFourStage.CALL, FirstFourPhase.SEND, true) }
        repeat(FirstFour.COPY_STREAK_TO_PASS) { p.recordElement(FirstFourStage.STATE, FirstFourPhase.COPY, true) }
        p.recordScene(FirstFourStage.WALKTHROUGH, true)
        p.recordElement(FirstFourStage.QUESTION, FirstFourPhase.COPY, true)

        val back = FirstFourStore.decode(FirstFourStore.encode(p))
        assertEquals(setOf(FirstFourStage.CALL), back.passed)
        assertEquals(setOf(FirstFourStage.CALL, FirstFourStage.STATE), back.copyPassed)
        assertEquals(1, back.cleanRuns(FirstFourStage.WALKTHROUGH))
        assertEquals(0, back.streak)
        assertEquals(FirstFourPhase.SEND, back.openingPhase(FirstFourStage.STATE))
    }

    @Test
    fun missingOrBrokenSavesStartFresh() {
        for (raw in listOf(null, "", "not json", "{}", """{"passed":["nope"],"cleanRuns":{"nope":3}}""")) {
            val p = FirstFourStore.decode(raw)
            assertTrue("'$raw' decodes as a fresh start", p.passed.isEmpty() && p.copyPassed.isEmpty())
            assertEquals(FirstFourStage.CALL, p.nextStage)
        }
    }
}
