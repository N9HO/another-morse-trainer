package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.MidiKeyParser
import app.anothermorsetrainer.morsekit.MidiKeyRoute
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which MIDI key notes hold the key and which go into the app's paddle keyer
 * (#380), against `fixtures/midi-key-routing.json`, the file the other two
 * ports read. Expected values come from the Vail Adapter firmware, the N6ARA
 * TinyMIDI and the Morserino-32 Pocket's MIDI mode, not from this parser.
 */
class MidiKeyRoutingFixtureTest {

    private val fixture by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("midi-key-routing.json")
        assertNotNull("fixtures/midi-key-routing.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun route(name: String) = when (name) {
        "held" -> MidiKeyRoute.HELD
        "keyerDit" -> MidiKeyRoute.KEYER_DIT
        "keyerDah" -> MidiKeyRoute.KEYER_DAH
        else -> throw AssertionError("unknown route '$name' in fixture")
    }

    @Test
    fun everyNoteTakesTheFixturesRouteInPassthroughAndInEveryOtherMode() {
        val routes = fixture.getJSONArray("routes")
        assertTrue("fixture 'routes' is empty", routes.length() > 0)
        for (i in 0 until routes.length()) {
            val row = routes.getJSONObject(i)
            val note = row.getInt("note")
            assertEquals("note $note in Passthrough", route(row.getString("passthrough")), MidiKeyParser.routeForNote(note, true))
            assertEquals("note $note in other modes", route(row.getString("otherModes")), MidiKeyParser.routeForNote(note, false))
        }
    }

    @Test
    fun unroutedNotesGoNowhere() {
        val unrouted = fixture.getJSONArray("unrouted")
        assertTrue("fixture 'unrouted' is empty", unrouted.length() > 0)
        for (i in 0 until unrouted.length()) {
            val note = unrouted.getInt(i)
            assertNull("note $note", MidiKeyParser.routeForNote(note, true))
            assertNull("note $note", MidiKeyParser.routeForNote(note, false))
        }
    }

    /** A note is routed exactly when it keys at all (fixtures/midi-key-notes.json). */
    @Test
    fun routedNotesAreExactlyTheKeyingNotes() {
        for (note in 0..127) {
            val keys = MidiKeyParser.paddleForNote(note) != null
            assertEquals("note $note", keys, MidiKeyParser.routeForNote(note, true) != null)
            assertEquals("note $note", keys, MidiKeyParser.routeForNote(note, false) != null)
        }
    }

    @Test
    fun onlyTheFixturesModesSendRawPaddles() {
        val raw = fixture.getJSONArray("rawPaddleModes")
        val names = (0 until raw.length()).map { raw.getString(it) }.toSet()
        assertTrue("fixture 'rawPaddleModes' is empty", names.isNotEmpty())
        for (mode in MidiKeyOutput.KeyerMode.entries) {
            assertEquals(mode.displayName, mode.displayName in names, AdapterKeyer.sendsRawPaddles(mode))
        }
    }
}
