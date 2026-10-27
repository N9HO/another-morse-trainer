package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sending Analyzer's key merge and microphone state, pinned against
 * `fixtures/gap-analyzer-merge.json` and `fixtures/gap-analyzer-mic-state.json`
 * (the same files the iOS `MorseKitCheck` harness and the Android test read).
 * The expectations were written from each fixture's `rule`, not captured from
 * either port.
 */
class GapAnalyzerTest {

    private fun load(name: String): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
        assertNotNull("fixtures/$name is not on the test classpath", stream)
        return JSONObject(stream!!.bufferedReader().readText())
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    @Test
    fun keyMergeMatchesFixture() {
        val cases = load("gap-analyzer-merge.json").getJSONArray("cases").objects()
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val name = c.getString("name")
            val steps = c.getJSONArray("steps")
            val expect = c.getJSONArray("expect")
            assertEquals("$name: steps and expectations line up", steps.length(), expect.length())
            val merge = KeyMerge<String>()
            for (i in 0 until steps.length()) {
                val step = steps.getJSONArray(i)
                val source = step.getString(0)
                val action = step.getString(1)
                val got: Boolean? = when (action) {
                    "down" -> merge.update(source, true)
                    "up" -> merge.update(source, false)
                    "releaseAll" -> merge.releaseAll()
                    else -> throw AssertionError("$name: unknown action $action")
                }
                val want: String? = if (expect.isNull(i)) null else expect.getString(i)
                val gotText = when (got) { true -> "down"; false -> "up"; null -> null }
                assertEquals("$name, step $i ($source $action)", want, gotText)
            }
        }
    }

    @Test
    fun micStateMatchesFixture() {
        val cases = load("gap-analyzer-mic-state.json").getJSONArray("cases").objects()
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val name = c.getString("name")
            val steps = c.getJSONArray("steps")
            val expect = c.getJSONArray("expect")
            assertEquals("$name: steps and expectations line up", steps.length(), expect.length())
            var state = MicInputState()
            for (i in 0 until steps.length()) {
                val code = steps.getString(i)
                val event = MicEvent.fromCode(code)
                assertNotNull("$name: unknown event $code", event)
                state = state.after(event!!)
                val want = expect.getJSONArray(i)
                assertEquals("$name, step $i ($code): listening", want.getBoolean(0), state.isListening)
                assertEquals("$name, step $i ($code): denied", want.getBoolean(1), state.micDenied)
            }
        }
    }
}
