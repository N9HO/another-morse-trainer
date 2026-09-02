package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Max callers is a cap, not just a top-up target (#143). Twin of the `#143`
 * cap checks in the iOS harness, MorseKitCheck/main.swift.
 *
 * Calling CQ only ever topped the pileup up, so a pileup that had grown to
 * eight stayed eight after the setting came down to two, which read as the
 * setting doing nothing. Lowering it mid-session thins the pileup at once,
 * and CQ never refills past it.
 */
class PileupCallerCapTest {

    private fun config(maxStations: Int) = PileupConfig(
        mode = QSOContestMode.Pota,
        maxStations = maxStations,
        minWPM = 20.0,
        maxWPM = 20.0,
        giveUpEnabled = false
    )

    /** CQ until the pileup has grown past the cap it is about to be given. */
    private fun grownPast(size: Int): PileupEngine {
        val e = PileupEngine(config(8), Random(7))
        var cqs = 0
        do { e.callCQ(); cqs += 1 } while (e.activeCount <= size && cqs < 20)
        assertTrue("a pileup should grow past $size under Max callers 8", e.activeCount > size)
        return e
    }

    @Test
    fun `lowering Max callers thins the pileup at once`() {
        val e = grownPast(2)
        e.update(config(2))
        assertTrue("active ${e.activeCount}", e.activeCount in 5..8)
        assertTrue("callers the cap sent away are neither walk-offs nor busts",
            e.missedCallers.isEmpty() && e.bustCount == 0)
    }

    @Test
    fun `CQ never refills past Max callers`() {
        val e = grownPast(2)
        e.update(config(2))
        e.callCQ()
        assertTrue("active ${e.activeCount}", e.activeCount in 5..8)
    }

    @Test
    fun `the cap never sends away the station being worked`() {
        val e = grownPast(2)
        val worked = e.stations.first().call
        e.send(worked)
        e.update(config(1))
        assertEquals(1, e.activeCount)
        assertEquals(worked, e.workingStation?.call)
    }
}
