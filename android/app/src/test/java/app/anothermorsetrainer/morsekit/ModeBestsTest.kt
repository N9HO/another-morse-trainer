package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * The per-mode personal-best fold (docs/high-scores-design.md, step 1), held
 * to `fixtures/mode-bests.json` — the same file the Swift harness's "Per-mode
 * personal bests" section reads, so both ports pin the same rule: largest
 * score per mode, a record with no score adds nothing, 0 is a real best, and
 * a later lower score never lowers it.
 */
class ModeBestsTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("mode-bests.json")
        assertNotNull("fixtures/mode-bests.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun record(mode: String, score: Int?) = SessionRecord(
        id = UUID.randomUUID(), date = Instant.EPOCH, mode = mode,
        characterWPM = 20, effectiveWPM = 20, attempts = 5, correct = 4,
        fastestTTR = null, medianTTR = null, durationSeconds = 60.0,
        characters = emptyList(), activeCharacters = emptyList(), score = score
    )

    private fun fixtureRecords(): List<SessionRecord> {
        val rows = fixture.getJSONArray("sessions")
        return (0 until rows.length()).map { i ->
            val o = rows.getJSONObject(i)
            record(o.getString("mode"), if (o.isNull("score")) null else o.getInt("score"))
        }
    }

    private fun expectedBests(): Map<String, Int> {
        val o = fixture.getJSONObject("expectedBests")
        return o.keys().asSequence().associateWith { o.getInt(it) }
    }

    @Test
    fun `bests folded record by record match the fixture`() {
        var bests: Map<String, Int> = emptyMap()
        for (r in fixtureRecords()) bests = ModeBests.fold(bests, r.mode, r.score)
        assertEquals(expectedBests() + ("NEGATIVE-CONTROL" to 1), bests)
    }

    @Test
    fun `modes with no scored record have no best`() {
        val bests = ModeBests.seed(fixtureRecords())
        val absent = fixture.getJSONArray("absentModes")
        for (i in 0 until absent.length()) {
            assertNull("no best expected for ${absent.getString(i)}", bests[absent.getString(i)])
        }
    }

    @Test
    fun `bests seeded from existing rows match the fixture, in any order`() {
        val records = fixtureRecords()
        assertEquals(expectedBests(), ModeBests.seed(records))
        assertEquals(expectedBests(), ModeBests.seed(records.reversed()))
    }

    @Test
    fun `a record without a score has null, as one saved before the field decodes`() {
        val r = SessionRecord(
            id = UUID.randomUUID(), date = Instant.EPOCH, mode = "Contest",
            characterWPM = 20, effectiveWPM = 20, attempts = 5, correct = 4,
            fastestTTR = null, medianTTR = null, durationSeconds = 60.0,
            characters = emptyList(), activeCharacters = emptyList()
        )
        assertNull(r.score)
        assertEquals(mapOf("x" to 1), ModeBests.fold(mapOf("x" to 1), r.mode, r.score))
    }
}
