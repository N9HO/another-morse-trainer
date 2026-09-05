package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The custom word-list parser, pinned against `fixtures/custom-words.json` at
 * the repo root — the same file the iOS `MorseKitCheck` harness reads.
 *
 * The two ports used to parse a pasted list differently (iOS kept unsendable
 * characters and had no length cap; Android parsed in the settings layer with
 * no MorseKit twin), so the fixture pins one rule set for both. Expected values
 * were derived from the documented rules independently of either
 * implementation, so both ports drifting the same way still fails.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class CustomWordsTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("custom-words.json")
        assertNotNull("fixtures/custom-words.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    @Test
    fun theWordCapIsTheFixtures() {
        assertEquals(fixture.getInt("maxLength"), MorseData.CUSTOM_WORD_MAX_LENGTH)
    }

    @Test
    fun parseWordListMatchesTheFixture() {
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val expected = c.getJSONArray("expected")
            val expectedList = (0 until expected.length()).map { expected.getString(it) }
            assertEquals(
                "${c.getString("name")}: parseWordList(${JSONObject.quote(c.getString("input"))})",
                expectedList,
                MorseData.parseWordList(c.getString("input"))
            )
        }
    }

    /** `customWordItems` takes the parsed list as-is: one item per word, in order. */
    @Test
    fun customWordItemsMapTheCleanListOneToOne() {
        val items = MorseData.customWordItems(listOf("OHIO", "TEXAS"))
        assertEquals(listOf("custom-OHIO", "custom-TEXAS"), items.map { it.id })
        assertEquals(MorseItem.Playable.Text("OHIO"), items.first().playable)
        assertEquals("OHIO", items.first().answer)
    }
}
