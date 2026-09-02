package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The Characters track past its first unlock: singles → pairs → triples →
 * words & call signs, the shape of a group drill, and a learner-chosen stage
 * holding against automatic progression (issue #51).
 *
 * Behaviour rather than fixture data, because the track is stateful and
 * RNG-driven. `MorseLadderTest` already covers what completes the singles
 * stage; this is everything the Swift harness checks after that and this port
 * never did. Every test seeds its own [Random] so failures are reproducible.
 */
class ProgressiveCharactersTest {

    private val pairs = ProgressiveCharacters.Stage.Pairs.displayName

    /** A ladder whose whole single-character set is active, so it can leave singles. */
    private fun fullLadder(seed: Int = 11): Pair<TrainerEngine, ProgressiveCharacters> {
        val engine = TrainerEngine(seedCount = 2, rng = Random(seed))
        engine.setActiveCharacters(MorseCode.kochOrder)
        return engine to ProgressiveCharacters(engine, rng = Random(seed))
    }

    /**
     * Answer correctly and fast until [done] says so, or the budget runs out.
     * Returns whether [done] was ever satisfied.
     */
    private fun drive(
        ladder: ProgressiveCharacters,
        budget: Int = 20_000,
        ttr: Double = 0.4,
        done: (DrillOutcome) -> Boolean
    ): Boolean {
        repeat(budget) {
            val drill = ladder.nextDrill()
            if (done(ladder.record(drill.correct, ttr))) return true
        }
        return false
    }

    private fun masteredPairs(seed: Int = 11): Pair<TrainerEngine, ProgressiveCharacters> {
        val (engine, ladder) = fullLadder(seed)
        assertTrue("could not complete singles", drive(ladder) { it.unlocked == pairs })
        return engine to ladder
    }

    @Test
    fun `starts at singles`() {
        val (_, ladder) = fullLadder()
        assertEquals(ProgressiveCharacters.Stage.Pairs, ladder.stage)   // CONTROL: wrong on purpose
        assertNull("a fresh track is on automatic progression", ladder.pinnedStage)
    }

    @Test
    fun `completing singles unlocks pairs on the answer that completes it`() {
        val (_, ladder) = masteredPairs()
        assertEquals(ProgressiveCharacters.Stage.Pairs, ladder.stage)
    }

    @Test
    fun `a group drill offers four distinct groups including the answer`() {
        val (_, ladder) = masteredPairs()
        var sawGroup = false
        repeat(30) {
            val d = ladder.nextDrill()
            val playable = d.playable
            if (playable is MorseItem.Playable.Text && playable.value.length >= 2) {
                sawGroup = d.options.size == 4 && d.options.toSet().size == 4 && d.correct in d.options
                if (sawGroup) return
            }
            ladder.record(d.correct, 0.4)
        }
        assertTrue("no pairs drill with four distinct options in 30 rounds", sawGroup)
    }

    @Test
    fun `advances through triples to words and call signs, reporting each unlock`() {
        val (_, ladder) = masteredPairs()
        val triples = ProgressiveCharacters.Stage.Triples.displayName
        val phrases = ProgressiveCharacters.Stage.Phrases.displayName
        assertTrue("never unlocked triples", drive(ladder, budget = 2000) { it.unlocked == triples })
        assertEquals(ProgressiveCharacters.Stage.Triples, ladder.stage)
        assertTrue("never unlocked phrases", drive(ladder, budget = 2000) { it.unlocked == phrases })
        assertEquals(ProgressiveCharacters.Stage.Phrases, ladder.stage)
        // Phrases is the final stage: nothing further ever unlocks.
        assertTrue(!drive(ladder, budget = 200) { it.unlocked != null })
        assertEquals(ProgressiveCharacters.Stage.Phrases, ladder.stage)
    }

    @Test
    fun `summary names the engine in singles and the stage after`() {
        val (engine, ladder) = fullLadder()
        assertEquals(engine.summary, ladder.summary)
        ladder.pin(ProgressiveCharacters.Stage.Triples)
        assertEquals("Triples", ladder.summary)
    }

    // ---- Stage pinning (issue #51) ----

    @Test
    fun `pinning singles returns the track to singles and holds it there`() {
        val (_, ladder) = masteredPairs()
        ladder.pin(ProgressiveCharacters.Stage.Singles)
        assertEquals(ProgressiveCharacters.Stage.Singles, ladder.stage)
        // The whole ladder is mastered, so auto progression wants to leave.
        assertTrue(!drive(ladder, budget = 200, ttr = 0.3) { ladder.stage != ProgressiveCharacters.Stage.Singles })
        assertEquals(ProgressiveCharacters.Stage.Singles, ladder.stage)
    }

    @Test
    fun `a pinned stage holds despite a mastered window and keeps the answer among the options`() {
        val (_, ladder) = masteredPairs()
        ladder.pin(ProgressiveCharacters.Stage.Pairs)
        repeat(100) {
            val d = ladder.nextDrill()
            assertTrue("answer missing from its own options", d.correct in d.options)
            ladder.record(d.correct, 0.3)
            assertEquals("pin did not hold", ProgressiveCharacters.Stage.Pairs, ladder.stage)
        }
    }

    @Test
    fun `a pinned stage works on a tiny studied set without expanding it`() {
        // Two characters form only four pairs, fewer than a cap of six, so the
        // option padding has to give up gracefully rather than spin forever —
        // and, unlike the dev jump, a pin must leave the learner's set alone.
        val engine = TrainerEngine(seedCount = 2, rng = Random(31))
        engine.config.optionCount = 6
        val ladder = ProgressiveCharacters(engine, rng = Random(31))
        ladder.pin(ProgressiveCharacters.Stage.Pairs)
        repeat(20) {
            val d = ladder.nextDrill()
            assertTrue("answer missing from its own options", d.correct in d.options)
            assertTrue("more options than the cap", d.options.size <= 6)
            ladder.record(d.correct, 0.3)
        }
        assertEquals("pin expanded the studied set", 2, engine.activeCharacters.size)
    }

    @Test
    fun `unpin returns to automatic progression from the current stage`() {
        val (_, ladder) = masteredPairs()
        ladder.pin(ProgressiveCharacters.Stage.Pairs)
        ladder.unpin()
        assertNull(ladder.pinnedStage)
        assertEquals(ProgressiveCharacters.Stage.Pairs, ladder.stage)
    }

    @Test
    fun `a dev jump clears a pin`() {
        // The stage readout must never claim one stage while the track serves another.
        val (_, ladder) = fullLadder()
        ladder.pin(ProgressiveCharacters.Stage.Triples)
        ladder.jumpToStage(ProgressiveCharacters.Stage.Phrases)
        assertNull(ladder.pinnedStage)
        assertEquals(ProgressiveCharacters.Stage.Phrases, ladder.stage)
    }

    @Test
    fun `a restart to singles clears a pin`() {
        val (_, ladder) = fullLadder()
        ladder.pin(ProgressiveCharacters.Stage.Phrases)
        ladder.resetToSingles()
        assertNull(ladder.pinnedStage)
        assertEquals(ProgressiveCharacters.Stage.Singles, ladder.stage)
    }

    // ---- Snapshot ----

    @Test
    fun `a snapshot carries the stage and the pin, and a pin-less one restores as auto`() {
        val (_, ladder) = masteredPairs()
        ladder.pin(ProgressiveCharacters.Stage.Pairs)
        val snap = ladder.snapshot
        assertEquals(ProgressiveCharacters.Stage.Pairs, snap.stage)
        assertEquals(ProgressiveCharacters.Stage.Pairs, snap.pinnedStage)

        val restored = ProgressiveCharacters(TrainerEngine(seedCount = 1), rng = Random(1))
        restored.restore(snap)
        assertEquals(ProgressiveCharacters.Stage.Pairs, restored.stage)
        assertEquals(ProgressiveCharacters.Stage.Pairs, restored.pinnedStage)
        assertEquals(MorseCode.kochOrder, restored.engine.activeCharacters)

        // Older saves carry no pin; they must come back on automatic progression.
        val auto = ProgressiveCharacters(TrainerEngine(seedCount = 1), rng = Random(1))
        auto.restore(snap.copy(pinnedStage = null))
        assertNull(auto.pinnedStage)
        assertEquals(ProgressiveCharacters.Stage.Pairs, auto.stage)
        assertNotEquals(auto.stage, ProgressiveCharacters.Stage.Singles)
    }
}
