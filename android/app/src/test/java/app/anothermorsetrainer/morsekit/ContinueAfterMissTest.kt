package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Continue with the key after a miss" (#352, #347), pinned against
 * `fixtures/continue-after-miss.json` — the file the iOS `MorseKitCheck`
 * harness and the desktop tree read too. Put on the classpath by
 * `sourceSets["test"].resources` in build.gradle.kts.
 */
class ContinueAfterMissTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("continue-after-miss.json")
        assertNotNull("fixtures/continue-after-miss.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    @Test
    fun `debounce matches the shared fixture`() {
        assertEquals(fixture.getLong("debounceMs"), ContinueAfterMiss.DEBOUNCE_MS)
    }

    @Test
    fun `every case plays out as the shared fixture says`() {
        val cases = fixture.getJSONArray("cases")
        assertTrue("fixture has no cases", cases.length() > 0)
        var steps = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val enabled = case.getBoolean("enabled")
            val machine = ContinueAfterMiss()
            val list = case.getJSONArray("steps")
            for (j in 0 until list.length()) {
                val step = list.getJSONObject(j)
                val label = "$name, step ${j + 1}"
                when (val op = step.getString("op")) {
                    "miss" -> machine.miss(step.getString("target"), step.getLong("atMs"), enabled)
                    "input" -> assertEquals(
                        label,
                        ContinueAfterMiss.Outcome.valueOf(step.getString("expect").uppercase()),
                        machine.input(step.getString("text"), step.getLong("atMs"))
                    )
                    "reset" -> machine.reset()
                    else -> throw AssertionError("$label: unknown op $op")
                }
                assertEquals("$label: awaiting", step.getBoolean("awaiting"), machine.isAwaiting)
                steps++
            }
        }
        assertTrue("fixture steps never ran", steps > 20)
    }
}
