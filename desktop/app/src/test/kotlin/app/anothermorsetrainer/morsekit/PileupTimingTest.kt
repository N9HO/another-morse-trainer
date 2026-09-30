package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * How a pileup sounds like people rather than a metronome, and what happens
 * when your copy fits two of them. Twin of the timing and ambiguity checks in
 * the iOS repo's MorseKitCheck/main.swift.
 */
class PileupTimingTest {

    private fun delays(a: PileupEngine.Action): List<Double> =
        if (a is PileupEngine.Action.Play) a.voices.map { it.delay } else emptyList()

    private fun config() = PileupConfig(
        mode = QSOContestMode.Pota,
        maxStations = 4,
        minWPM = 20.0,
        maxWPM = 20.0,
        minDelay = 0.2,
        maxDelay = 1.5
    )

    @Test
    fun `the same request twice does not come back on the same beat`() {
        val e = PileupEngine(config(), Random(7)).also { it.callCQ() }
        val first = delays(e.repeatRequest())
        val second = delays(e.repeatRequest())

        assertTrue("the pileup should answer with more than one voice", first.size > 1)
        assertTrue("same voice count between rounds", first.size == second.size)
        assertNotEquals("two rounds must not land identically", first, second)
        assertTrue("stations must not answer together", first.toSet().size == first.size)
        assertTrue(
            "delays stay near the configured window",
            first.all { it >= 0 && it <= 1.5 + 0.5 }
        )
    }

    @Test
    fun `the exchange no longer lands on a fixed beat`() {
        val e = PileupEngine(config(), Random(7)).also { it.callCQ() }
        val exchange = delays(e.send(e.stations[0].call)).firstOrNull()
        assertTrue("an exchange should have played", exchange != null)
        assertNotEquals("the old hard-coded beat", 0.2, exchange!!, 1e-9)
        assertTrue("still prompt, since you named them", exchange > 0.0 && exchange < 0.6)
    }

    /**
     * A miscopy that fits two stations needs two calls within a couple of
     * characters of each other, which random callsigns rarely are — so walk
     * seeds until such a pileup turns up.
     */
    @Test
    fun `a miscopy that fits two callers brings both back, hesitating`() {
        var found = false
        for (seed in 1..400) {
            val e = PileupEngine(config(), Random(seed)).also { it.callCQ() }
            val calls = e.stations.map { it.call }
            if (calls.size < 2) continue

            var probe: String? = null
            outer@ for (call in calls) {
                for (c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") {
                    if (call.last() == c) continue
                    val cand = call.dropLast(1) + c
                    if (calls.contains(cand)) continue
                    val hits = calls.filter {
                        abs(it.length - cand.length) <= 1 &&
                            MorseDistance.distance(cand, it) <= PileupEngine.NEAR_MISS_TOLERANCE
                    }
                    if (hits.size >= 2) { probe = cand; break@outer }
                }
            }
            val miscopy = probe ?: continue
            found = true

            val before = e.activeCount
            val action = e.send(miscopy)
            val voices = (action as? PileupEngine.Action.Play)?.voices
            assertTrue("both callers should come back", (voices?.size ?: 0) >= 2)
            assertNull("neither of them opens an exchange", e.workingStation)
            assertTrue(
                "an unsure caller waits rather than answering instantly",
                voices!!.all { it.delay > 0.2 }
            )
            assertTrue(
                "unsure callers do not answer in lockstep",
                voices.map { it.delay }.toSet().size == voices.size
            )
            assertTrue("nobody is dropped for an ambiguous miscopy", e.activeCount == before)
            break
        }
        assertTrue("an ambiguous-miscopy pileup could be found", found)
    }
}
