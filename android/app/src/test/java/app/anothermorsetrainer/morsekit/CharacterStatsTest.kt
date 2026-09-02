package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mastery, pinned against `fixtures/mastery.json` at the repo root — the same
 * file the iOS `MorseKitCheck` harness reads.
 *
 * [CharacterStats] decides when a character is learned well enough to introduce
 * the next one, so it is the gate the whole Koch ladder hangs off. It had **no
 * tests on this side at all**, which is the audit's test-parity item: the Swift
 * twin carried ~200 harness checks over this engine while the Kotlin port
 * carried none.
 *
 * Mastery is a pure function of an attempt sequence and a config, which is why
 * it can be shared as data rather than as copied test code.
 */
class CharacterStatsTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("mastery.json")
        assertNotNull("fixtures/mastery.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private val tolerance: Double get() = fixture.getDouble("tolerance")

    private fun cases(): List<JSONObject> {
        val arr = fixture.getJSONArray("cases")
        assertTrue("fixture has no cases", arr.length() > 0)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** Replay a case's attempts through `record`, exercising the trim too. */
    private fun replay(c: JSONObject): CharacterStats {
        val stats = CharacterStats('E')
        val attempts = c.getJSONArray("attempts")
        for (i in 0 until attempts.length()) {
            val a = attempts.getJSONArray(i)
            stats.record(a.getBoolean(0), a.getDouble(1))
        }
        return stats
    }

    @Test
    fun `the history limit matches the shared fixture`() {
        assertEquals(fixture.getInt("historyLimit"), CharacterStats.historyLimit)
    }

    @Test
    fun `recorded history is bounded, oldest first`() {
        for (c in cases()) {
            val stats = replay(c)
            assertEquals("${c.getString("name")} kept", c.getInt("keptCount"), stats.attempts.size)
            assertTrue(
                "${c.getString("name")} exceeded the history limit",
                stats.attempts.size <= CharacterStats.historyLimit
            )
        }
    }

    @Test
    fun `accuracy and median match the shared fixture`() {
        for (c in cases()) {
            val name = c.getString("name")
            val stats = replay(c)
            val window = c.getInt("window")
            assertEquals("$name recent", c.getInt("recentCount"), stats.recent(window).size)
            assertEquals("$name accuracy", c.getDouble("accuracy"), stats.accuracy(window), tolerance)
            if (c.isNull("medianTTR")) {
                assertNull("$name median should not exist", stats.medianTTR(window))
            } else {
                assertEquals(
                    "$name median",
                    c.getDouble("medianTTR"), stats.medianTTR(window)!!, tolerance
                )
            }
        }
    }

    @Test
    fun `mastery matches the shared fixture`() {
        for (c in cases()) {
            val name = c.getString("name")
            val stats = replay(c)
            assertEquals(
                "$name isMastered",
                c.getBoolean("isMastered"),
                stats.isMastered(
                    ttrThreshold = c.getDouble("ttrThreshold"),
                    window = c.getInt("window"),
                    requiredAccuracy = c.getDouble("requiredAccuracy")
                )
            )
        }
    }

    @Test
    fun `copy freezes a snapshot away from the live stats`() {
        // The Swift twin is a struct, so `Array(stats.values)` copies by value.
        // This port is a class, and without `copy()` a snapshot would alias the
        // engine's own list and every later record would mutate it.
        val live = CharacterStats('E')
        live.record(true, 0.4)
        val frozen = live.copy()
        live.record(false, 2.0)
        assertEquals("the snapshot moved with the live stats", 1, frozen.attempts.size)
        assertEquals(2, live.attempts.size)
    }
}
