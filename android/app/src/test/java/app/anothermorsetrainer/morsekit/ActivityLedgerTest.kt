package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The activity ledger behind the Stats screen's daily grid (#181), pinned
 * against `fixtures/activity.json` at the repo root — the same file the iOS
 * `MorseKitCheck` harness reads.
 *
 * The fixture pins the shade thresholds (with their boundary values), a
 * multi-record scenario with its per-day totals and levels, and the cap. The
 * expected values were derived from the documented rules independently of
 * either implementation, so both ports drifting the same way still fails.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class ActivityLedgerTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("activity.json")
        assertNotNull("fixtures/activity.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    @Test
    fun `the cap and the thresholds are the fixture's`() {
        assertEquals(fixture.getInt("capDays"), ActivityLedger.CAP_DAYS)
        val thresholds = fixture.getJSONArray("levelThresholds")
        assertEquals((0 until thresholds.length()).map { thresholds.getInt(it) }, ActivityLedger.LEVEL_THRESHOLDS)
        assertEquals(thresholds.length(), ActivityLedger.MAX_LEVEL)
    }

    @Test
    fun `seconds map to shade levels as the fixture pins, boundaries included`() {
        val levels = fixture.getJSONArray("levels")
        assertTrue("fixture has no level cases", levels.length() > 0)
        for (i in 0 until levels.length()) {
            val c = levels.getJSONObject(i)
            val secs = c.getInt("seconds")
            assertEquals("level for $secs s", c.getInt("level"), ActivityLedger.levelForSeconds(secs))
        }
    }

    @Test
    fun `records on the same day add up and every day reads as the fixture expects`() {
        val scenario = fixture.getJSONObject("scenario")
        val ledger = ActivityLedger()
        val records = scenario.getJSONArray("records")
        for (i in 0 until records.length()) {
            val r = records.getJSONObject(i)
            ledger.record(LocalDate.parse(r.getString("day")), r.getInt("seconds"))
        }
        assertEquals(scenario.getInt("dayCount"), ledger.dayCount)
        val expected = scenario.getJSONArray("expected")
        for (i in 0 until expected.length()) {
            val e = expected.getJSONObject(i)
            val day = LocalDate.parse(e.getString("day"))
            assertEquals("$day recorded", e.getBoolean("recorded"), ledger.isRecorded(day))
            assertEquals("$day seconds", e.getInt("seconds"), ledger.seconds(day))
            assertEquals("$day level", e.getInt("level"), ledger.level(day))
        }
    }

    @Test
    fun `the cap drops the earliest day`() {
        val cap = fixture.getJSONObject("cap")
        val first = LocalDate.parse(cap.getString("firstDay"))
        val ledger = ActivityLedger()
        for (i in 0 until cap.getInt("distinctDays")) {
            ledger.record(first.plusDays(i.toLong()), cap.getInt("secondsEach"))
        }
        assertEquals(cap.getInt("expectedCount"), ledger.dayCount)
        assertFalse(ledger.isRecorded(LocalDate.parse(cap.getString("droppedDay"))))
        assertEquals(LocalDate.parse(cap.getString("oldestKept")), ledger.recordedDays.first())
        assertEquals(LocalDate.parse(cap.getString("newestKept")), ledger.recordedDays.last())
    }

    @Test
    fun `a negative duration counts as zero but still marks the day`() {
        val ledger = ActivityLedger()
        val day = LocalDate.of(2026, 3, 2)
        ledger.record(day, -30)
        assertTrue(ledger.isRecorded(day))
        assertEquals(0, ledger.seconds(day))
        assertEquals(1, ledger.level(day))
    }
}
