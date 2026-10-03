package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Which sessions have an accuracy at all (#183). Listen & Learn and Stories
 * play without grading an answer, so their records are "not scored": the
 * stats screen shows N/A for them and the speed bands leave them out.
 */
class SessionScoringTest {

    private fun record(mode: String, attempts: Int, correct: Int, wpm: Int = 20) = SessionRecord(
        id = UUID.randomUUID(), date = Instant.EPOCH, mode = mode,
        characterWPM = wpm, effectiveWPM = wpm, attempts = attempts, correct = correct,
        fastestTTR = null, medianTTR = null, durationSeconds = 60.0,
        characters = emptyList(), activeCharacters = emptyList()
    )

    @Test
    fun `passive modes are never scored, whatever they heard`() {
        assertFalse(record("Listen", attempts = 12, correct = 0).isScored)
        assertFalse(record("Stories", attempts = 3, correct = 3).isScored)
        assertFalse(SessionRecord.isScoredMode("Listen"))
        assertFalse(SessionRecord.isScoredMode("Stories"))
    }

    @Test
    fun `a graded session is scored only once something was answered`() {
        assertTrue(record("Characters", attempts = 10, correct = 7).isScored)
        assertFalse(record("Characters", attempts = 0, correct = 0).isScored)
        assertTrue(SessionRecord.isScoredMode("Characters"))
    }

    @Test
    fun `speed bands are built from scored sessions only`() {
        val sessions = listOf(
            record("Characters", attempts = 10, correct = 9),
            record("Listen", attempts = 40, correct = 0)
        )
        val bands = WPMBands.summarize(
            sessions.filter { it.isScored }.map { WPMBands.Entry(it.characterWPM, it.attempts, it.correct, null) }
        )
        assertEquals(1, bands.size)
        assertEquals(1, bands.single().sessions)
        assertEquals(10, bands.single().attempts)
        assertEquals(0.9, bands.single().accuracy, 1e-9)
    }
}
