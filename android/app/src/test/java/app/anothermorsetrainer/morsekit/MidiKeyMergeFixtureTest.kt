package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A MIDI key's notes and the app keyer's tone merged into the one logical key
 * (#380), against `fixtures/midi-key-merge.json`, the file the other two ports
 * read. The expected actions were worked out by hand from the fixture's
 * derivation, not captured from this class.
 */
class MidiKeyMergeFixtureTest {

    private val cases: List<JSONObject> by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("midi-key-merge.json")
        assertNotNull("fixtures/midi-key-merge.json is not on the test classpath", stream)
        val a = JSONObject(stream!!.bufferedReader().readText()).getJSONArray("cases")
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun describe(actions: List<MidiKeyMerge.Action>): List<String> = actions.map {
        when (it) {
            is MidiKeyMerge.Action.Key -> "key ${if (it.isDown) "down" else "up"} ${it.at}"
            is MidiKeyMerge.Action.Paddle -> "paddle ${if (it.element == PaddleKeyer.Element.DIT) "dit" else "dah"} ${if (it.isDown) "down" else "up"}"
            MidiKeyMerge.Action.StopKeyer -> "stop"
        }
    }

    private fun joined(row: JSONArray): String = (0 until row.length()).joinToString(" ") { row.get(it).toString() }

    @Test
    fun theMergeDoesWhatTheFixtureSays() {
        assertTrue("fixture has no cases", cases.isNotEmpty())
        for (case in cases) {
            val name = case.getString("name")
            val merge = MidiKeyMerge()
            var raw = case.getString("adapter") == "passthrough"
            val got = mutableListOf<String>()
            val events = case.getJSONArray("events")
            for (i in 0 until events.length()) {
                val e = events.getJSONArray(i)
                when (val first = e.get(0)) {
                    is Int -> got += describe(merge.note(first, e.getString(1) == "down", raw, e.getLong(2)))
                    "tone" -> got += describe(merge.keyerTone(e.getString(1) == "on", e.getLong(2)))
                    "release" -> got += describe(merge.releaseAll(e.getLong(1)))
                    "mode" -> raw = e.getString(1) == "passthrough"
                    else -> throw AssertionError("$name: event $e is a note, tone, release or mode")
                }
            }
            val want = case.getJSONArray("actions")
            assertEquals(name, (0 until want.length()).map { joined(want.getJSONArray(it)) }, got)
        }
    }
}
