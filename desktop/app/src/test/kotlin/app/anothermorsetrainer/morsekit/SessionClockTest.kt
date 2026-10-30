package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session timer menu's arithmetic (add, subtract), pinned against
 * `fixtures/gap-timers-session-clock.json` at the repo root - the same file
 * the iOS `MorseKitCheck` harness and the other Kotlin tree read. The expected
 * values come from the documented menu rules, not from running either port.
 */
class SessionClockTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("gap-timers-session-clock.json")
        assertNotNull("fixtures/gap-timers-session-clock.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun optInt(o: JSONObject, key: String): Int? = if (o.isNull(key)) null else o.getInt(key)

    private fun check(cases: JSONArray, op: (Int?, Int) -> Int?) {
        assertTrue("fixture has no cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val remaining = optInt(c, "remaining")
            val seconds = c.getInt("seconds")
            assertEquals("remaining=$remaining seconds=$seconds", optInt(c, "expected"), op(remaining, seconds))
        }
    }

    @Test
    fun `adding time follows the fixture`() = check(fixture.getJSONArray("add")) { r, s -> SessionClock.added(r, s) }

    @Test
    fun `subtracting time follows the fixture`() = check(fixture.getJSONArray("subtract")) { r, s -> SessionClock.reduced(r, s) }
}
