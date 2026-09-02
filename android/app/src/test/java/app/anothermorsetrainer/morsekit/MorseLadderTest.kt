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
}
