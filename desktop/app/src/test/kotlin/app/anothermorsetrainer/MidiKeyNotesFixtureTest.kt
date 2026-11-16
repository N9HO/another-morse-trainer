package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.MidiKeyPaddle
import app.anothermorsetrainer.morsekit.MidiKeyParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MIDI note table (#351) against `fixtures/midi-key-notes.json`, the file the
 * other two ports read. Expected values come from the Vail repeater's notes and
 * the Morserino-32 Pocket's MIDI mode, not from this parser.
 */
class MidiKeyNotesFixtureTest {

    private val fixture by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("midi-key-notes.json")
        assertNotNull("fixtures/midi-key-notes.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun contact(name: String) = when (name) {
        "straight" -> MidiKeyPaddle.STRAIGHT
        "dit" -> MidiKeyPaddle.DIT
        "dah" -> MidiKeyPaddle.DAH
        else -> throw AssertionError("unknown contact '$name' in fixture")
    }

    @Test
    fun everyMappedNoteKeysTheContactTheFixtureNames() {
        val mapped = fixture.getJSONArray("mapped")
        assertTrue("fixture 'mapped' is empty", mapped.length() > 0)
        for (i in 0 until mapped.length()) {
            val row = mapped.getJSONObject(i)
            val note = row.getInt("note")
            assertEquals("note $note", contact(row.getString("contact")), MidiKeyParser.paddleForNote(note))
        }
    }

    @Test
    fun everyUnmappedNoteStaysSilent() {
        val unmapped = fixture.getJSONArray("unmapped")
        assertTrue("fixture 'unmapped' is empty", unmapped.length() > 0)
        for (i in 0 until unmapped.length()) {
            assertNull("note ${unmapped.getInt(i)}", MidiKeyParser.paddleForNote(unmapped.getInt(i)))
        }
    }

    /** Nothing keys that the fixture does not list, across the whole 0..127 range. */
    @Test
    fun noOtherNoteKeys() {
        val mapped = fixture.getJSONArray("mapped")
        val listed = (0 until mapped.length()).map { mapped.getJSONObject(it).getInt("note") }.toSet()
        for (note in 0..127) {
            if (note !in listed) assertNull("note $note", MidiKeyParser.paddleForNote(note))
        }
    }
}
