package app.anothermorsetrainer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The complete, ordered list of Vail Adapter keyer modes (#348), against
 * `fixtures/keyer-modes.json`, the file the other two ports read. A Settings
 * picker that lists `KeyerMode.entries` can only be as complete as this.
 */
class KeyerModesTest {

    private val modes by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("keyer-modes.json")
        assertNotNull("fixtures/keyer-modes.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText()).getJSONArray("modes")
    }

    @Test
    fun theListIsCompleteAndInTheFixturesOrder() {
        assertEquals(modes.length(), MidiKeyOutput.KeyerMode.entries.size)
        for (i in 0 until modes.length()) {
            val want = modes.getJSONObject(i)
            val got = MidiKeyOutput.KeyerMode.entries[i]
            assertEquals("name of mode ${i + 1}", want.getString("name"), got.displayName)
            assertEquals("code of ${want.getString("name")}", want.getInt("code"), got.code)
            assertEquals(got, MidiKeyOutput.KeyerMode.fromCode(want.getInt("code")))
        }
    }

    @Test
    fun theAdapterTimesSendingExactlyWhereTheFixtureSays() {
        for (i in 0 until modes.length()) {
            val want = modes.getJSONObject(i)
            val mode = MidiKeyOutput.KeyerMode.fromCode(want.getInt("code"))
            assertEquals(want.getString("name"), want.getBoolean("adapterTimesSending"), AdapterKeyer.adapterTimesSending(mode))
        }
    }
}
