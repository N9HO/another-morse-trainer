package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MidiKeyer] against `fixtures/midi-keyer.json`, the file the iOS
 * `MorseKitCheck` harness reads too. Each case scripts MIDI notes, which go
 * through [MidiKeyParser.paddleForNote] first; the expected edges were worked
 * out by hand from the fixture's `derivation`, not captured from either port.
 * On the classpath through `sourceSets["test"].resources` in build.gradle.kts.
 */
class MidiKeyerTest {

    private val cases: List<JSONObject> by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("midi-keyer.json")
        assertNotNull("fixtures/midi-keyer.json is not on the test classpath", stream)
        val a = JSONObject(stream!!.bufferedReader().readText()).getJSONArray("cases")
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun run(case: JSONObject): Pair<List<String>, MidiKeyer> {
        val keyer = MidiKeyer(PaddleKeyer.Mode.fromId(case.getString("mode")), case.getDouble("wpm"))
        val edges = mutableListOf<MidiKeyer.Edge>()
        val events = case.getJSONArray("events")
        for (i in 0 until events.length()) {
            val e = events.getJSONArray(i)
            if (e.get(0) == "unplug") {
                edges += keyer.releaseAll(e.getDouble(1))
                continue
            }
            if (e.get(0) == "config") {
                val mode = PaddleKeyer.Mode.fromId(e.getString(1))
                assertEquals("config mode ${e.getString(1)} is a PaddleKeyer mode", e.getString(1), mode.id)
                edges += keyer.configure(mode, e.getDouble(2), e.getDouble(3))
                continue
            }
            val paddle = MidiKeyParser.paddleForNote(e.getInt(0)) ?: continue
            // The app's setting for a Vail Adapter's keyer mode; absent means Passthrough.
            val timed = MidiKeyer.timesNote(e.getInt(0), case.optBoolean("adapterPassthrough", true))
            edges += keyer.contact(paddle, e.getString(1) == "down", e.getDouble(2), timed)
        }
        edges += keyer.advance(case.getDouble("untilMs"))
        val got = edges.map { "${if (it.isDown) "down" else "up"} ${it.atMs.toInt()} ${if (it.timedByApp) "keyer" else "straight"}" }
        return got to keyer
    }

    private fun expected(rows: JSONArray): List<String> = (0 until rows.length()).map {
        val r = rows.getJSONArray(it)
        "${r.getString(0)} ${r.getDouble(1).toInt()} ${r.getString(2)}"
    }

    @Test
    fun matchesTheFixture() {
        assertTrue("the fixture carries cases", cases.isNotEmpty())
        for (case in cases) {
            val name = case.getString("name")
            assertEquals("mode ${case.getString("mode")} is a PaddleKeyer mode",
                case.getString("mode"), PaddleKeyer.Mode.fromId(case.getString("mode")).id)
            val (got, keyer) = run(case)
            assertEquals(name, expected(case.getJSONArray("expected")), got)
            assertFalse("$name — idle afterwards", keyer.isBusy)
        }
    }

    /** The one this exists for, named on its own so a failure says so. */
    @Test
    fun holdingTheTinyMidiDitPadSendsAStringOfDits() {
        val k = MidiKeyer(PaddleKeyer.Mode.IAMBIC_A, 20.0)
        val edges = k.contact(MidiKeyParser.paddleForNote(20)!!, true, 0.0) +
            k.advance(250.0) + k.contact(MidiKeyParser.paddleForNote(20)!!, false, 250.0) + k.advance(1000.0)
        assertEquals(listOf(0.0, 60.0, 120.0, 180.0, 240.0, 300.0), edges.map { it.atMs })
        assertTrue(edges.all { it.timedByApp })
    }

    /**
     * The on-screen paddles run on [MidiKeyer] too, pressing only dit and dah,
     * so it must time every `paddle-keyer.json` script, in every mode, exactly
     * as [PaddleKeyer] does. The iOS harness runs the same check.
     */
    @Test
    fun pressingOnlyDitAndDahTimesExactlyAsPaddleKeyer() {
        val stream = javaClass.classLoader?.getResourceAsStream("paddle-keyer.json")
        assertNotNull("fixtures/paddle-keyer.json is not on the test classpath", stream)
        val scripts = JSONObject(stream!!.bufferedReader().readText()).getJSONArray("cases")
        assertTrue("paddle-keyer.json carries cases", scripts.length() > 0)
        var compared = 0
        for (i in 0 until scripts.length()) {
            val script = scripts.getJSONObject(i)
            val wpm = script.getDouble("wpm")
            for (mode in PaddleKeyer.Mode.entries) {
                val paddles = PaddleKeyer(mode, wpm)
                val midi = MidiKeyer(mode, wpm)
                val a = mutableListOf<String>()
                val b = mutableListOf<String>()
                val events = script.getJSONArray("events")
                for (j in 0 until events.length()) {
                    val e = events.getJSONArray(j)
                    val dah = e.getString(0) == "dah"
                    val down = e.getString(1) == "down"
                    val t = e.getDouble(2)
                    a += paddles.paddle(if (dah) PaddleKeyer.Element.DAH else PaddleKeyer.Element.DIT, down, t)
                        .map { "${it.isDown} ${it.atMs}" }
                    b += midi.contact(if (dah) MidiKeyPaddle.DAH else MidiKeyPaddle.DIT, down, t)
                        .map { "${it.isDown} ${it.atMs}" }
                }
                a += paddles.advance(script.getDouble("untilMs")).map { "${it.isDown} ${it.atMs}" }
                b += midi.advance(script.getDouble("untilMs")).map { "${it.isDown} ${it.atMs}" }
                assertEquals("${mode.id}: ${script.getString("name")}", a, b)
                compared++
            }
        }
        assertEquals("every script in every mode", scripts.length() * PaddleKeyer.Mode.entries.size, compared)
    }
}
