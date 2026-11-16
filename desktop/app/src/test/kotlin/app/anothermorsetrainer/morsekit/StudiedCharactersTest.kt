package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * "Studied characters" (Sending Drill and Analyzer Groups), pinned against
 * `fixtures/gap-stats-studied.json`, the file the iOS `MorseKitCheck` harness
 * also reads. The rule is the Koch active set: what the learner has been
 * taught, not the seed plus every character that happens to have stats.
 */
class StudiedCharactersTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("gap-stats-studied.json")
        assertNotNull("fixtures/gap-stats-studied.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    @Test
    fun `studied characters are the Koch active set for every fixture case`() {
        val cases = fixture.getJSONArray("cases")
        assertTrue("fixture has no cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val koch = MorseCode.kochOrder
            val letters = koch.filter { it.isLetter() }
            val seed = when (c.getString("proficiency")) {
                "none" -> koch.take(2)
                "someLetters" -> letters.take(13)
                "allLetters" -> letters
                else -> koch
            }
            val selected = c.getString("selectedPunctuation").toSet()
            val engine = TrainerEngine(seedCount = 2, rng = Random(9))
            engine.setActiveCharacters(seed)
            engine.applyStudyOrder(MorseCode.studyOrder(selected))
            repeat(c.getInt("unlocks")) {
                engine.studyOrder.firstOrNull { it !in engine.activeCharacters }
                    ?.let { engine.addActiveCharacter(it) }
            }
            val out = c.getString("optedOut").toSet()
            if (out.isNotEmpty()) engine.applyStudyOrder(MorseCode.studyOrder(selected - out))
            for (ch in c.getString("statsOnly")) {
                if (ch !in engine.activeCharacters) {
                    engine.addActiveCharacter(ch)
                    engine.removeActiveCharacter(ch)
                }
            }
            val got = SendingDrill.studiedCharacters(engine.snapshot).joinToString("")
            assertEquals(c.getString("name"), c.getString("studied"), got)
        }
    }
}
