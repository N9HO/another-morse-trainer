package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The Koch engine: what it seeds with, what it offers as choices, and when it
 * introduces the next character.
 *
 * Behaviour rather than fixture data, because this is stateful and
 * RNG-driven — the shareable parts (the ladder's order, the mastery rule) are
 * pinned in `fixtures/ladder.json` and `fixtures/mastery.json` instead. What is
 * here are the invariants the Swift harness has always checked and this port
 * never did.
 *
 * Every test seeds its own [Random] so failures are reproducible.
 */
class TrainerEngineTest {

    private fun engine(seedCount: Int = 2, seed: Int = 7, config: TrainerEngine.Config = TrainerEngine.Config()) =
        TrainerEngine(config = config, seedCount = seedCount, rng = Random(seed))

    /** Answer `count` questions correctly and quickly. */
    private fun drillCorrectly(e: TrainerEngine, count: Int, ttr: Double = 0.3) {
        repeat(count) {
            val q = e.nextQuestion()
            e.record(q.target, q, ttr)
        }
    }

    @Test
    fun `seeds from the front of the Koch order`() {
        assertEquals(MorseCode.kochOrder.take(2), engine(seedCount = 2).activeCharacters)
        assertEquals(MorseCode.kochOrder.take(5), engine(seedCount = 5).activeCharacters)
    }

    @Test
    fun `a seed count below one still yields a usable engine`() {
        // pickTarget over an empty active set would have nothing to choose from.
        assertEquals(1, engine(seedCount = 0).activeCharacters.size)
    }

    @Test
    fun `every question offers its own target among the options`() {
        val e = engine(seedCount = 6)
        e.setExposedCharacters(e.activeCharacters)
        repeat(200) {
            val q = e.nextQuestion()
            assertTrue("target missing from its own options", q.target in q.options)
            assertEquals("options repeated a character", q.options.size, q.options.toSet().size)
            assertTrue("offered an inactive character", q.options.all { it in e.activeCharacters })
        }
    }

    @Test
    fun `choices are drawn only from characters the learner has met`() {
        // A true beginner sees one option and builds up; distractors may only
        // come from the exposed set, never from characters never presented.
        val e = engine(seedCount = 6)
        e.setExposedCharacters(emptyList())
        val q = e.nextQuestion()
        assertEquals("an unmet character was offered as a distractor", 1, q.options.size)
        assertEquals(q.target, q.options.single())
    }

    @Test
    fun `recording scores the answer and feeds the character's stats`() {
        val e = engine(seedCount = 2)
        val q = e.nextQuestion()
        val wrong = e.activeCharacters.first { it != q.target }

        assertFalse(e.record(wrong, q, 0.5).correct)
        assertTrue(e.record(q.target, q, 0.5).correct)

        val stats = e.stats[q.target]!!
        assertEquals(2, stats.attempts.size)
        assertEquals(listOf(false, true), stats.attempts.map { it.correct })
    }

    @Test
    fun `no new character arrives until every active one is mastered`() {
        val e = engine(seedCount = 2)
        assertNull("advanced before anything was mastered", e.advanceIfReady())
        assertEquals(2, e.activeCharacters.size)
    }

    @Test
    fun `mastering the active set introduces exactly one new character`() {
        val e = engine(seedCount = 2)
        e.setExposedCharacters(e.activeCharacters)
        drillCorrectly(e, 200)
        assertTrue("nothing was ever introduced", e.activeCharacters.size > 2)
        // Whatever it grew to, it grew along the ladder and only forwards.
        assertEquals(
            "characters arrived out of ladder order",
            MorseCode.kochOrder.take(e.activeCharacters.size), e.activeCharacters
        )
    }

    @Test
    fun `a fully learned ladder stops advancing rather than repeating`() {
        val e = engine()
        e.setActiveCharacters(MorseCode.kochOrder)
        e.studyOrder = MorseCode.kochOrder
        // Mastered or not, there is nothing left in the ladder to add.
        for (c in MorseCode.kochOrder) repeat(5) { e.stats[c]!!.record(true, 0.1) }
        assertTrue(e.allActiveMastered)
        assertNull("introduced a character that is already active", e.advanceIfReady())
        assertEquals(MorseCode.kochOrder.size, e.activeCharacters.size)
    }

    @Test
    fun `replacing the active set keeps stats already earned`() {
        val e = engine(seedCount = 2)
        val first = e.activeCharacters.first()
        e.stats[first]!!.record(true, 0.4)

        e.setActiveCharacters(MorseCode.kochOrder.take(10))
        assertEquals("earned history was thrown away", 1, e.stats[first]!!.attempts.size)
        assertNotNull("a newly active character has no stats", e.stats[MorseCode.kochOrder[9]])
    }

    @Test
    fun `a wrong answer is remembered as a confusion pair`() {
        val e = engine(seedCount = 4)
        val q = e.nextQuestion()
        val wrong = e.activeCharacters.first { it != q.target }
        e.record(wrong, q, 0.5)
        assertTrue(
            "the mix-up was not recorded, so confusion drills have nothing to work from",
            e.confusions.pairs().isNotEmpty()
        )
    }
}
