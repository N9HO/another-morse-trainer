package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Partial-call queries in the pileup (Apple repo #85). Twin of the `#85` checks
 * in the iOS repo's MorseKitCheck/main.swift.
 *
 * A partial is whatever fragment you managed to copy, and it is not always the
 * front of the call: two stations landing on top of each other leave you a
 * trailing letter, and "9H?" for N9HO queries the middle. Matching only a
 * prefix sent those to the busted-call path — on the silence setting, no reply
 * at all.
 */
class PileupPartialCallTest {

    /** Silence on a bust makes an unanswered partial unmistakable. */
    private fun config() = PileupConfig(
        mode = QSOContestMode.Pota,
        maxStations = 4,
        minWPM = 20.0,
        maxWPM = 20.0,
        giveUpEnabled = false,
        bustBehavior = BustBehavior.Silence
    )

    private fun engine() = PileupEngine(config(), Random(7)).also { it.callCQ() }

    private fun playCount(a: PileupEngine.Action): Int =
        if (a is PileupEngine.Action.Play) a.voices.size else 0

    @Test
    fun `every partial of a live call draws a reply`() {
        val live = engine().stations.map { it.call }
        assertTrue("the seeded pileup should have stations", live.isNotEmpty())

        val unanswered = mutableListOf<String>()
        var interiorTested = false
        for (call in live) {
            for (len in 1..call.length) {
                for (start in 0..(call.length - len)) {
                    val frag = call.substring(start, start + len)
                    // An exact call is a full copy, not a partial: it opens the
                    // exchange instead of re-calling, and is covered elsewhere.
                    if (live.contains(frag)) continue
                    if (start > 0) interiorTested = true
                    // A fresh engine per probe so bumps can't bleed across.
                    if (playCount(engine().send("$frag?")) < 1) unanswered.add(frag)
                }
            }
        }
        assertTrue("partials left unanswered: $unanswered", unanswered.isEmpty())
        assertTrue("the sweep should cover non-leading fragments", interiorTested)
    }

    @Test
    fun `a trailing-letter partial is answered, not silence`() {
        val live = engine().stations.map { it.call }
        val tail = live.first().takeLast(1)
        // Only meaningful when that letter isn't itself somebody's whole call.
        if (live.contains(tail)) return
        assertTrue(playCount(engine().send("$tail?")) >= 1)
    }

    @Test
    fun `a partial matching nobody is still a bust`() {
        val e = engine()
        assertTrue(playCount(e.send("QXJZ?")) == 0 && e.bustCount >= 1)
    }
}
