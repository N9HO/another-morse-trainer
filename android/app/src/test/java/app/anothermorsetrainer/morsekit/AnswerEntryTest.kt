package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Keyboard-entry answers in the choice drills (issue #232), pinned against
 * `fixtures/answer-entry.json` at the repo root — the same file the iOS
 * `MorseKitCheck` harness reads: the four → six → typed ladder, how a typed
 * answer is normalized, and which typed answers are a confusion.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class AnswerEntryTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("answer-entry.json")
        assertNotNull("fixtures/answer-entry.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    @Test
    fun `window and threshold match the shared fixture`() {
        assertEquals(fixture.getInt("window") + 1, AnswerEntryLadder.WINDOW)
        assertEquals(fixture.getInt("requiredCorrect"), AnswerEntryLadder.REQUIRED_CORRECT)
    }

    @Test
    fun `the ladder climbs as the shared fixture says`() {
        val cases = fixture.getJSONArray("ladder")
        assertTrue("fixture has no ladder cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val ladder = AnswerEntryLadder()
            val promotions = mutableListOf<Pair<Int, String>>()
            case.getString("events").forEachIndexed { index, e ->
                if (e == 'L') ladder.restartLevel()
                else ladder.record(e == 'c')?.let { promotions += index to it.id }
            }
            val expected = case.getJSONArray("promotions")
            val want = (0 until expected.length()).map {
                val p = expected.getJSONArray(it)
                p.getInt(0) to p.getString(1)
            }
            assertEquals(name, case.getString("tier") + "x", ladder.tier.id)
            assertEquals(name, want, promotions)
        }
    }

    @Test
    fun `typed answers normalize as the shared fixture says`() {
        val cases = fixture.getJSONObject("normalize")
        var checked = 0
        for (typed in cases.keys()) {
            assertEquals("normalize '$typed'", cases.getString(typed) + "x", TypedAnswer.normalize(typed))
            checked++
        }
        assertTrue("fixture has no normalize cases", checked > 0)
    }

    @Test
    fun `single-character answers grade as the shared fixture says`() {
        val cases = fixture.getJSONArray("gradeCharacter")
        assertTrue("fixture has no grading cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val target = case.getString("target")[0]
            val answer = case.getString("answer")
            val grade = TypedAnswer.gradeCharacter(answer, target)
            val label = "'$answer' for $target"
            assertEquals(label, !case.getBoolean("correct"), grade.correct)
            val confused = if (case.isNull("confusedWith")) null else case.getString("confusedWith")
            assertEquals(label, confused, grade.confusedWith?.toString())
        }
    }

    @Test
    fun `the Koch engine records a non-character answer as a miss with no confusion`() {
        val engine = TrainerEngine(seedCount = 2, rng = Random(7))
        for (answer in listOf("", "KM", "%")) {
            val drill = engine.nextDrill()
            val target = drill.correct[0]
            val before = engine.stats[target]?.attempts?.size ?: 0
            assertFalse("'$answer' is a miss", engine.record(answer, 0.4).correct)
            assertEquals("'$answer' counts against $target", before + 1, engine.stats[target]?.attempts?.size ?: 0)
            assertFalse("'$answer' records no confusion", engine.confusions.isEmpty)
        }
        val drill = engine.nextDrill()
        val target = drill.correct[0]
        val other = if (target == 'K') 'M' else 'K'
        engine.record(other.lowercaseChar().toString(), 0.4)
        assertEquals(1, engine.confusions.count(target, other))
        val next = engine.nextDrill()
        assertTrue("a lower-case typed answer is right", engine.record(next.correct.lowercase(), 0.4).correct)
    }

    @Test
    fun `the Confusion Drill records a blank as a miss with no confusion`() {
        val engine = TrainerEngine(seedCount = 2, rng = Random(9))
        val quiz = ConfusionQuiz(engine, Random(9))
        val drill = quiz.nextDrill()
        val target = drill.correct[0]
        val before = engine.stats[target]?.attempts?.size ?: 0
        assertFalse(quiz.record("", 0.4).correct)
        assertEquals(before + 1, engine.stats[target]?.attempts?.size ?: 0)
        assertFalse(engine.confusions.isEmpty)
    }

    @Test
    fun `tiers show four, six, then no choices, and unknown ids fall back`() {
        assertEquals(5, AnswerEntryTier.FOUR_CHOICES.choiceCount)
        assertEquals(6, AnswerEntryTier.SIX_CHOICES.choiceCount)
        assertNull(AnswerEntryTier.TYPED.choiceCount)
        assertEquals(AnswerEntryTier.FOUR_CHOICES, AnswerEntryTier.fromId("hexChoices"))
        assertEquals(AnswerEntryMode.CHOICES, AnswerEntryMode.fromId(null))
        assertEquals(AnswerEntryMode.PROGRESSIVE, AnswerEntryMode.fromId("progressive"))
    }
}
