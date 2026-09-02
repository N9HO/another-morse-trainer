package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The study ladder, pinned against `fixtures/ladder.json` at the repo root —
 * the same file the iOS `MorseKitCheck` harness reads.
 *
 * This is the divergence that made the audit item: this port threaded opted-in
 * punctuation through the [TrainerEngine.studyOrder] ladder while iOS added it
 * straight to the active set, so unlocking singles→pairs took 40 characters
 * here and 37 there. The ladder was judged the intended design, so iOS was
 * changed to match — which makes these tests a guard on *this* side not
 * drifting in return.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class MorseLadderTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("ladder.json")
        assertNotNull("fixtures/ladder.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    @Test
    fun `Koch order matches the shared fixture`() {
        assertEquals(
            "Koch order",
            fixture.getString("kochOrder"), MorseCode.kochOrder.joinToString("")
        )
        assertEquals(
            "Koch order length",
            fixture.getInt("kochOrderLength"), MorseCode.kochOrder.size
        )
    }

    @Test
    fun `pickable punctuation matches the shared fixture, in order`() {
        // Order matters: this list is the sequence punctuation is *taught* in,
        // and the two ports used to disagree about it.
        assertEquals(
            fixture.getString("pickablePunctuation"),
            MorseCode.pickablePunctuation.joinToString("")
        )
    }

    @Test
    fun `study order matches the shared fixture for every punctuation selection`() {
        val cases = fixture.getJSONArray("cases")
        assertTrue("fixture has no cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val selection = c.getString("selectedPunctuation").toSet()
            val built = MorseCode.studyOrder(selection)
            assertEquals("selection '$selection' order", c.getString("studyOrder"), built.joinToString(""))
            assertEquals("selection '$selection' length", c.getInt("length"), built.size)
        }
    }

    /**
     * Drive the ladder to its first unlock and count what it took. This is the
     * end-to-end shape of the divergence: 37 characters with nothing opted in,
     * 40 with all three, rather than 37 either way.
     */
    private fun charactersNeededToUnlockPairs(punctuation: Set<Char>): Int? {
        val engine = TrainerEngine(seedCount = 2, rng = Random(11))
        engine.studyOrder = MorseCode.studyOrder(punctuation)
        engine.setActiveCharacters(MorseCode.kochOrder)
        val ladder = ProgressiveCharacters(engine, rng = Random(11))
        repeat(20_000) {
            val drill = ladder.nextDrill()
            if (ladder.record(drill.correct, 0.4).unlocked == ProgressiveCharacters.Stage.Pairs.displayName) {
                return engine.activeCharacters.size
            }
        }
        return null
    }

    @Test
    fun `opted-in punctuation holds the singles stage open`() {
        assertEquals(
            "with no punctuation opted in, singles completes at the Koch core",
            MorseCode.kochOrder.size, charactersNeededToUnlockPairs(emptySet())
        )
        assertEquals(
            "with all three opted in, singles is held open until 40 are learned",
            MorseCode.kochOrder.size + 3, charactersNeededToUnlockPairs(setOf('.', ',', '/'))
        )
    }

    @Test
    fun `opting in does not drop the mark straight into the active set`() {
        val engine = TrainerEngine(seedCount = 2, rng = Random(3))
        engine.studyOrder = MorseCode.studyOrder(setOf('.', ',', '/'))
        assertFalse("period was granted rather than earned", '.' in engine.activeCharacters)
        assertFalse("comma was granted rather than earned", ',' in engine.activeCharacters)
    }

    /**
     * Issue #133: opting *out* of a mark already earned removes it from the
     * active set, so it stops being drilled. The fixture pins the arithmetic —
     * only pickable punctuation goes, never the Koch core, and opting back in
     * is not an immediate add.
     */
    @Test
    fun `opting out reconciles the active set as the shared fixture says`() {
        val cases = fixture.getJSONArray("optOutCases")
        assertTrue("fixture has no opt-out cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val engine = TrainerEngine(seedCount = 2, rng = Random(5))
            engine.setActiveCharacters(c.getString("activeBefore").toList())
            val removed = engine.applyStudyOrder(
                MorseCode.studyOrder(c.getString("selectedPunctuation").toSet())
            )
            assertEquals("${c.getString("name")}: active set", c.getString("activeAfter"), engine.activeCharacters.joinToString(""))
            assertEquals("${c.getString("name")}: removed", c.getString("removed"), removed.joinToString(""))
        }
    }

    /** What the fixture cannot express: the state around the removal. */
    @Test
    fun `opting out removes the mark but keeps its history`() {
        val engine = TrainerEngine(seedCount = 2, rng = Random(9))
        engine.applyStudyOrder(MorseCode.studyOrder(setOf('.', ',', '/')))
        engine.setActiveCharacters(MorseCode.kochOrder + listOf('.', ','))
        engine.setExposedCharacters(engine.activeCharacters)
        repeat(3) { engine.stats[',']!!.record(true, 0.4) }

        val removed = engine.applyStudyOrder(MorseCode.studyOrder(setOf('.')))
        assertEquals("only the opted-out comma is removed", listOf(','), removed)
        assertFalse("the comma is still in the drill", ',' in engine.activeCharacters)
        assertTrue("the still-opted-in period was removed too", '.' in engine.activeCharacters)
        assertEquals("the comma's stats were thrown away", 3, engine.stats[',']!!.attempts.size)
        assertTrue("the comma is no longer 'met'", ',' in engine.exposedCharacters)
        repeat(50) { assertFalse("the removed comma was still asked", engine.nextQuestion().target == ',') }

        // Opting back in puts it on the ladder, not into the drill; the ladder
        // re-introduces it once everything else is mastered.
        engine.applyStudyOrder(MorseCode.studyOrder(setOf('.', ',')))
        assertFalse("opting back in re-added the mark immediately", ',' in engine.activeCharacters)
        for (c in engine.activeCharacters) repeat(5) { engine.stats[c]!!.record(true, 0.1) }
        assertEquals("the ladder did not re-introduce the mark", ',', engine.advanceIfReady())
    }

    @Test
    fun `a reconcile never removes the Koch core`() {
        val engine = TrainerEngine(seedCount = 2, rng = Random(9))
        engine.setActiveCharacters(MorseCode.kochOrder)
        val removed = engine.applyStudyOrder(MorseCode.studyOrder(emptySet()))
        assertTrue("a Koch-core character ('?' included) was removed", removed.isEmpty())
        assertEquals(MorseCode.kochOrder, engine.activeCharacters)
    }
}
