package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The fixed-pool quiz behind Words, Abbreviations, Q-codes and Prosigns: one
 * option at first, choices that grow with what the learner has heard, and a
 * weighting that favours items that are new, missed, or slow.
 *
 * Behaviour rather than fixture data — it is RNG-driven — except the weights,
 * which are a pure function of an attempt history and so are asserted to the
 * exact values the documented formula gives. The Swift harness has always
 * checked the first half of this and this port never did; the weights are
 * pinned here because they decide what a learner hears next.
 */
class PhraseQuizTest {

    private val abbreviations = MorseData.abbreviationItems

    private fun quiz(seed: Int = 9) =
        PhraseQuiz(name = "Abbreviations", items = abbreviations, rng = Random(seed))

    @Test
    fun `the first drill shows a single option, which is the answer`() {
        val d = quiz().nextDrill()
        assertEquals(listOf(d.correct), d.options)
    }

    @Test
    fun `recording scores the choice against the answer`() {
        val q = quiz()
        val d = q.nextDrill()
        assertTrue(q.record(d.correct, 0.8).correct)
        val again = q.nextDrill()
        assertFalse(q.record("not " + again.correct, 0.8).correct)
    }

    @Test
    fun `recording before any drill is not correct and unlocks nothing`() {
        val outcome = quiz().record("and", 0.5)
        assertFalse(outcome.correct)
        assertNull(outcome.unlocked)
    }

    @Test
    fun `choices grow to the cap as items are heard, stay distinct, and include the answer`() {
        val q = quiz()
        var most = 0
        repeat(60) {
            val d = q.nextDrill()
            most = maxOf(most, d.options.size)
            assertEquals("options repeated a label", d.options.size, d.options.toSet().size)
            assertTrue("answer missing from its own options", d.correct in d.options)
            q.record(d.correct, 0.8)
        }
        assertEquals("choices never reached the cap", 4, most)
    }

    @Test
    fun `the cap follows the config`() {
        val q = PhraseQuiz(
            name = "Abbreviations", items = abbreviations,
            config = PhraseQuiz.Config(optionCount = 6), rng = Random(9)
        )
        var most = 0
        repeat(120) {
            val d = q.nextDrill()
            most = maxOf(most, d.options.size)
            q.record(d.correct, 0.8)
        }
        assertEquals(6, most)
    }

    @Test
    fun `an item never unlocks anything`() {
        // Unlike the Koch ladder, a fixed pool has nothing to graduate to.
        val q = quiz()
        repeat(40) {
            val d = q.nextDrill()
            assertNull(q.record(d.correct, 0.5).unlocked)
        }
    }

    @Test
    fun `the reveal repeats the answer only when it adds something`() {
        val abbr = quiz().nextDrill()
        assertEquals("an abbreviation reveals its meaning", abbr.correct, abbr.revealSecondary)
        val word = PhraseQuiz(name = "Words", items = MorseData.wordItems, rng = Random(2)).nextDrill()
        assertEquals("a word's answer is its display, so nothing to add", "", word.revealSecondary)
    }

    @Test
    fun `summary counts the pool in the noun given, or the lowercased name`() {
        assertEquals("${abbreviations.size} abbreviations", quiz().summary)
        // Mode-named quizzes read as gibberish without a noun ("155 head copy" — issue #61).
        val headCopy = PhraseQuiz(name = "Head Copy", items = abbreviations, summaryNoun = "words")
        assertEquals("${abbreviations.size} words", headCopy.summary)
    }

    // ---- Weighting: new, missed, and slow items come around more often ----

    /** A one-item quiz, so every drill is that item and its history is under control. */
    private fun single(): Pair<PhraseQuiz, MorseItem> {
        val item = abbreviations.first { it.id == "ES" }
        return PhraseQuiz(name = "One", items = listOf(item), rng = Random(1)) to item
    }

    private fun answer(q: PhraseQuiz, correct: Boolean, ttr: Double) {
        val d = q.nextDrill()
        q.record(if (correct) d.correct else "wrong", ttr)
    }

    @Test
    fun `an unheard item weighs four`() {
        val (q, item) = single()
        assertEquals(5.0, q.weight(item), 0.0)   // CONTROL: wrong on purpose
    }

    @Test
    fun `five fast correct answers bring an item down to one`() {
        val (q, item) = single()
        repeat(5) { answer(q, correct = true, ttr = 0.5) }
        assertEquals(1.0, q.weight(item), 1e-9)
    }

    @Test
    fun `five misses weigh eight, the accuracy penalty plus no correct time at all`() {
        val (q, item) = single()
        repeat(5) { answer(q, correct = false, ttr = 0.5) }
        // 1 + (1 - 0) * 4, then + 3 for no correct answer to take a median over.
        assertEquals(8.0, q.weight(item), 1e-9)
    }

    @Test
    fun `slow correct answers add the median over threshold, capped at three`() {
        val (q, item) = single()
        // Threshold is 1.5 s; a 3.0 s median adds min(3.0 / 1.5, 3) = 2.
        repeat(5) { answer(q, correct = true, ttr = 3.0) }
        assertEquals(3.0, q.weight(item), 1e-9)
        // Slower still hits the cap: 1 + min(6.0 / 1.5, 3) = 4.
        repeat(5) { answer(q, correct = true, ttr = 6.0) }
        assertEquals(4.0, q.weight(item), 1e-9)
    }

    @Test
    fun `only the last five attempts count`() {
        val (q, item) = single()
        repeat(5) { answer(q, correct = false, ttr = 0.5) }
        repeat(5) { answer(q, correct = true, ttr = 0.5) }
        assertEquals("old misses aged out of the window", 1.0, q.weight(item), 1e-9)
    }
}
