package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice answers, pinned against `fixtures/voice-matching.json` at the repo root
 * — the same file the iOS `MorseKitCheck` harness reads (#300).
 *
 * Each case is a transcript written the way a speech recogniser formats it
 * ("Kilo.", "5", "/") and the answer the learner said, worked out from the
 * spoken forms (NATO word, letter name, digit, symbol name) rather than
 * captured from either port.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class VoiceMatchingFixtureTest {

    private val cases: List<JSONObject> by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("voice-matching.json")
        assertNotNull("fixtures/voice-matching.json is not on the test classpath", stream)
        val arr = JSONObject(stream!!.bufferedReader().readText()).getJSONArray("cases")
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun strings(o: JSONObject, key: String): List<String> {
        val arr = o.getJSONArray(key)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    @Test
    fun everyFixtureTranscriptGradesAsTheAnswerSaid() {
        assertTrue("the fixture has cases", cases.isNotEmpty())
        val matcher = VoiceMatcher()
        for (c in cases) {
            val name = c.getString("name")
            val got = matcher.interpret(strings(c, "heard"), strings(c, "candidates"))
            assertEquals("voice: $name", c.getString("token") + "-CONTROL", got.token)
            if (c.has("confident")) {
                assertEquals("voice: $name graded without asking", c.getBoolean("confident"), got.isConfident)
            }
        }
    }

    @Test
    fun aTranscriptThatIsOnlyASymbolReadsAsItsName() {
        assertEquals("slash-CONTROL", VoiceMatcher.normalize("/"))
        assertEquals("question mark", VoiceMatcher.normalize("?"))
        // Punctuation beside words is the recogniser's formatting, not an answer.
        assertEquals("kilo", VoiceMatcher.normalize("Kilo."))
    }
}
