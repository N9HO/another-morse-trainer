package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.AnswerEntryMode
import app.anothermorsetrainer.morsekit.AnswerEntryTier
import app.anothermorsetrainer.morsekit.ContestLength
import app.anothermorsetrainer.morsekit.PaddleKeyer
import app.anothermorsetrainer.morsekit.QSOContestMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The UI contract: every enumerated option list the user picks from is the
 * same, in the same order, on every app. Held to `fixtures/keyer-modes.json`
 * (#348) and `fixtures/option-lists.json`, which the Swift harness and
 * `parity/check.py` also read. A reorder, a missing entry or an extra one in
 * this tree fails here by the list's name.
 */
class OptionListsFixtureTest {

    private fun fixture(name: String): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
        assertNotNull("fixtures/$name is not on the test classpath", stream)
        return JSONObject(stream!!.bufferedReader().readText())
    }

    private fun strings(obj: JSONObject, key: String): List<String> {
        val a = obj.getJSONArray(key)
        assertTrue("fixture list '$key' is empty", a.length() > 0)
        return (0 until a.length()).map { a.getString(it) }
    }

    /** Fold to lowercase letters and digits: IAMBIC_A, iambicA and "Iambic A" agree. */
    private fun fold(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun assertList(key: String, entries: List<Enum<*>>) {
        val want = strings(fixture("option-lists.json"), key).map(::fold)
        assertEquals("option list '$key'", want, entries.map { fold(it.name) })
    }

    @Test
    fun `keyer modes are the fixture's, in order, by label`() {
        val want = strings(fixture("keyer-modes.json"), "keyerModes")
        assertEquals(want, MidiKeyOutput.KeyerMode.entries.map { it.displayName })
    }

    @Test
    fun `keyer mode codes run 0 to 9 in list order`() {
        assertEquals((0..9).toList(), MidiKeyOutput.KeyerMode.entries.map { it.code })
    }

    @Test fun `paddle modes`() = assertList("paddleModes", PaddleKeyer.Mode.entries)
    @Test fun `on-screen key types`() = assertList("onScreenKeyTypes", OnScreenKeyType.entries)
    @Test fun `reveal modes`() = assertList("revealModes", RevealMode.entries)
    @Test fun `practice durations`() = assertList("practiceDurations", PracticeDuration.entries)
    @Test fun `contest lengths`() = assertList("contestLengths", ContestLength.entries)
    @Test fun `story content`() = assertList("storyContent", StoryContent.entries)
    @Test fun `background noise levels`() = assertList("backgroundNoiseLevels", BackgroundNoiseLevel.entries)
    @Test fun `answer entry modes`() = assertList("answerEntryModes", AnswerEntryMode.entries)
    @Test fun `answer entry tiers`() = assertList("answerEntryTiers", AnswerEntryTier.entries)
    @Test fun `QSO exchange modes`() = assertList("qsoExchangeModes", QSOContestMode.entries)
    @Test fun `listen gaps`() = assertList("listenGaps", ListenGap.entries)
}
