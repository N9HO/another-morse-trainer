package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The new-item introduction (issue #162), pinned against
 * `fixtures/introduction.json` at the repo root — the same file the iOS
 * `MorseKitCheck` harness reads. The spoken form ("dah-di-dah") is what the
 * learner reads while the sound plays, so both ports have to say it the same
 * way.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class CharacterIntroductionTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("introduction.json")
        assertNotNull("fixtures/introduction.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun drill(correct: String) = Drill(
        playable = MorseItem.Playable.Text(correct),
        options = listOf(correct),
        correct = correct,
        revealPrimary = correct,
        revealSecondary = ""
    )

    @Test
    fun `spoken patterns match the shared fixture`() {
        val spoken = fixture.getJSONObject("spoken")
        var checked = 0
        for (pattern in spoken.keys()) {
            assertEquals(pattern, spoken.getString(pattern), CharacterIntroduction.spokenPattern(pattern))
            checked++
        }
        assertTrue("fixture has no spoken patterns", checked > 0)
    }

    @Test
    fun `symbol patterns match the shared fixture`() {
        val symbols = fixture.getJSONObject("symbols")
        for (pattern in symbols.keys()) {
            assertEquals(pattern, symbols.getString(pattern), CharacterIntroduction.symbolPattern(pattern))
        }
    }

    @Test
    fun `what a drill introduces matches the shared fixture`() {
        val cases = fixture.getJSONObject("introduces")
        for (correct in cases.keys()) {
            if (correct.startsWith("$")) continue
            val intro = CharacterIntroduction.forDrill(drill(correct)) { false }
            if (cases.isNull(correct)) {
                assertNull("$correct should not be introduced", intro)
                continue
            }
            val expected = cases.getJSONObject(correct)
            assertNotNull("$correct should be introduced", intro)
            assertEquals(correct, intro!!.id)
            assertEquals(correct, expected.getString("pattern"), intro.pattern)
            assertEquals(correct, expected.getBoolean("prosign"), intro.isProsign)
        }
    }

    @Test
    fun `an item already met is not introduced again`() {
        assertNull(CharacterIntroduction.forDrill(drill("K")) { it == "K" })
        assertNotNull(CharacterIntroduction.forDrill(drill("K")) { it == "M" })
    }

    @Test
    fun `a prosign plays its run-together pattern, a character its text`() {
        val k = CharacterIntroduction.forDrill(drill("K")) { false }!!
        assertEquals(MorseItem.Playable.Text("X"), k.playable)
        assertNull(k.meaning)
        assertFalse(k.isProsign)

        val ar = CharacterIntroduction.forDrill(drill("<AR>")) { false }!!
        assertEquals(MorseItem.Playable.Pattern(".-.-."), ar.playable)
        assertEquals(MorseData.prosigns.first { it.name == "<AR>" }.meaning, ar.meaning)
    }

    @Test
    fun `the engine's own drills introduce their target until it has been met`() {
        val engine = TrainerEngine(seedCount = 2, rng = kotlin.random.Random(4))
        val first = engine.nextDrill()
        val intro = CharacterIntroduction.forDrill(first) { it.single() in engine.exposedCharacters }
        assertNotNull(intro)
        assertEquals(first.correct, intro!!.id)
        engine.record(first.correct, 0.3)   // presenting it counts as meeting it
        assertNull(CharacterIntroduction.forDrill(first) { it.single() in engine.exposedCharacters })
    }
}
