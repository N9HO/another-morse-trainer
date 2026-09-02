package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.ProgressiveCharacters
import app.anothermorsetrainer.morsekit.TrainerEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The JSON that [EngineStore] writes to SharedPreferences, round-tripped
 * without a Context. This is per-tree work, not a fixture: the two ports do
 * not share a serialisation format (iOS keeps a `Codable` snapshot under
 * `MorseTrainer.progress`), only the rules about what an old save must mean.
 *
 * Those rules are what matter. A save from before pinning has no "pin" and
 * must come back on automatic progression; a save from before exposure
 * tracking has no "exposed" and must come back with every active character
 * treated as met — and the code path for that once turned every such save
 * into a decode failure, which `characters()` answered by reseeding, silently
 * discarding the learner's whole ladder on upgrade.
 */
class EngineStoreCodecTest {

    /** A track with some practice on it: stats, a miss in the confusion matrix, a pin. */
    private fun practisedTrack(): ProgressiveCharacters {
        val engine = TrainerEngine(seedCount = 3, rng = Random(3))
        val chars = ProgressiveCharacters(engine, rng = Random(3))
        repeat(10) {
            val d = chars.nextDrill()
            chars.record(d.correct, 0.7)
        }
        // Keep drilling until a round offers a wrong answer to give.
        for (round in 0 until 50) {
            val d = chars.nextDrill()
            val wrong = d.options.firstOrNull { it != d.correct }
            chars.record(wrong ?: d.correct, 0.9)
            if (wrong != null) break
        }
        assertTrue("no confusion was recorded", engine.confusions.snapshot().isNotEmpty())
        chars.pin(ProgressiveCharacters.Stage.Pairs)
        return chars
    }

    private fun json(track: ProgressiveCharacters): JSONObject = JSONObject(EngineStore.encode(track.snapshot))

    private fun assertSameEngine(expected: TrainerEngine.Snapshot, actual: TrainerEngine.Snapshot) {
        assertEquals(expected.activeCharacters, actual.activeCharacters)
        assertEquals(expected.exposedCharacters, actual.exposedCharacters)
        assertEquals(expected.confusions, actual.confusions)
        val expectedStats = expected.stats.associate { it.character to it.attempts.toList() }
        val actualStats = actual.stats.associate { it.character to it.attempts.toList() }
        assertEquals(expectedStats, actualStats)
    }

    @Test
    fun `a snapshot survives the round trip`() {
        val track = practisedTrack()
        val snap = track.snapshot
        val back = EngineStore.decode(EngineStore.encode(snap))
        assertSameEngine(snap.engine, back.engine)
        assertEquals(snap.stage, back.stage)
        assertEquals(snap.pinnedStage, back.pinnedStage)
    }

    @Test
    fun `the round trip restores a track that behaves like the original`() {
        val track = practisedTrack()
        val restored = ProgressiveCharacters(TrainerEngine(seedCount = 1), rng = Random(1))
        restored.restore(EngineStore.decode(EngineStore.encode(track.snapshot)))
        assertEquals(track.stage, restored.stage)
        assertEquals(track.pinnedStage, restored.pinnedStage)
        assertEquals(track.engine.activeCharacters, restored.engine.activeCharacters)
        assertEquals(track.engine.exposedCharacters, restored.engine.exposedCharacters)
        for (c in track.engine.activeCharacters) {
            assertEquals("stats for $c", track.engine.stats[c]!!.attempts, restored.engine.stats[c]!!.attempts)
        }
    }

    @Test
    fun `a save without a pin restores as automatic progression`() {
        val obj = json(practisedTrack())
        assertTrue("the practised track was not pinned", obj.has("pin"))
        obj.remove("pin")
        val back = EngineStore.decode(obj.toString())
        assertNull(back.pinnedStage)
        assertEquals("the stage itself is kept", ProgressiveCharacters.Stage.Singles, back.stage)   // CONTROL: wrong on purpose
    }

    @Test
    fun `a save predating exposure tracking restores every active character as met`() {
        val obj = json(practisedTrack())
        obj.remove("exposed")
        val back = EngineStore.decode(obj.toString())
        assertNull("absent must stay absent for the engine to backfill", back.engine.exposedCharacters)

        val engine = TrainerEngine(seedCount = 1)
        engine.restore(back.engine)
        assertEquals(back.engine.activeCharacters.toSet(), engine.exposedCharacters)
    }

    @Test
    fun `a fresh learner's empty met-set is kept, not backfilled`() {
        // Present-but-empty is a real state: a beginner who saved before
        // answering anything must relaunch into the one-option onboarding.
        val fresh = ProgressiveCharacters(TrainerEngine(seedCount = 2), rng = Random(5))
        val back = EngineStore.decode(EngineStore.encode(fresh.snapshot))
        assertEquals(emptySet<Char>(), back.engine.exposedCharacters)
        val engine = TrainerEngine(seedCount = 1)
        engine.restore(back.engine)
        assertTrue(engine.exposedCharacters.isEmpty())
    }

    @Test
    fun `an unrecognised stage name falls back to singles`() {
        val obj = json(practisedTrack()).put("stage", "Octuples").put("pin", "Octuples")
        val back = EngineStore.decode(obj.toString())
        assertEquals(ProgressiveCharacters.Stage.Singles, back.stage)
        assertNull(back.pinnedStage)
    }

    @Test
    fun `a stats row without a character is skipped rather than fatal`() {
        val obj = json(practisedTrack())
        val stats = obj.getJSONArray("stats")
        val kept = stats.length()
        stats.put(JSONObject().put("c", "").put("a", org.json.JSONArray()))
        val back = EngineStore.decode(obj.toString())
        assertEquals(kept, back.engine.stats.size)
    }

    @Test
    fun `the active set keeps its order`() {
        // Order is the ladder: the engine introduces `studyOrder.first { it !in active }`,
        // and a set would have reordered a learner mid-ladder.
        val engine = TrainerEngine(seedCount = 1)
        engine.setActiveCharacters(MorseCode.kochOrder.take(12).reversed())
        val track = ProgressiveCharacters(engine, rng = Random(1))
        val back = EngineStore.decode(EngineStore.encode(track.snapshot))
        assertEquals(MorseCode.kochOrder.take(12).reversed(), back.engine.activeCharacters)
    }
}
