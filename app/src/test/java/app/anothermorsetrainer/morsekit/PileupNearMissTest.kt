package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Near-miss calls in the pileup. Twin of the near-miss checks in the iOS repo's
 * MorseKitCheck/main.swift.
 *
 * A call you have all but copied — "N9HS" for N9HO — is answered by the one
 * station it names, over and over, until you get it right. On the air that is
 * what a station does: they send their own call again. They do not open the
 * exchange on a call that isn't theirs, and they do not go quiet.
 */
class PileupNearMissTest {

    /** Silence on a bust, so a station that stays quiet is unmistakable. */
    private fun config(giveUp: Boolean) = PileupConfig(
        mode = QSOContestMode.Pota,
        maxStations = 3,
        minWPM = 20.0,
        maxWPM = 20.0,
        bustBehavior = BustBehavior.Silence,
        giveUpEnabled = giveUp,
        giveUpMin = 1,
        giveUpMax = 1
    )

    private fun engine(giveUp: Boolean) =
        PileupEngine(config(giveUp), Random(7)).also { it.callCQ() }

    private fun playCount(a: PileupEngine.Action): Int =
        if (a is PileupEngine.Action.Play) a.voices.size else 0

    /**
     * A call one character off from [real] that no station is sending and that
     * only [real] is near — so the near miss is unambiguous.
     */
    private fun unambiguousMiscopy(real: String, calls: List<String>): String? {
        for (c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") {
            if (real.last() == c) continue
            val cand = real.dropLast(1) + c
            if (calls.contains(cand)) continue
            val near = calls.filter {
                abs(it.length - cand.length) <= 1 &&
                    MorseDistance.distance(cand, it) <= PileupEngine.NEAR_MISS_TOLERANCE
            }
            if (near == listOf(real)) return cand
        }
        return null
    }

    @Test
    fun `a near miss is answered by the station it names, and keeps being answered`() {
        val e = engine(giveUp = false)
        val calls = e.stations.map { it.call }
        val real = calls[0]
        val miscopy = unambiguousMiscopy(real, calls)
        assertNotNull("could not build a near-miss probe for $calls", miscopy)
        miscopy!!

        assertEquals("the station it names answers", 1, playCount(e.send(miscopy)))
        assertNull("a near miss does not open an exchange", e.workingStation)
        assertTrue("a near miss still counts as a bust", e.bustCount >= 1)

        val again = e.send(miscopy)
        assertEquals("it keeps correcting you", 1, playCount(again))
        assertTrue(
            "the correction sends the real call",
            (again as PileupEngine.Action.Play).voices.first().text.contains(real)
        )
    }

    @Test
    fun `a call with an exchange behind it is still read as a call`() {
        val e = engine(giveUp = false)
        val calls = e.stations.map { it.call }
        val real = calls[0]
        val miscopy = unambiguousMiscopy(real, calls) ?: return

        assertEquals(1, playCount(e.send("$miscopy 5NN AL")))
        assertNull("a near miss with an exchange behind it is still a near miss", e.workingStation)

        assertEquals(1, playCount(e.send("$real 5NN AL")))
        assertEquals("the real call opens the exchange", real, e.workingStation?.call)
    }

    @Test
    fun `a caller you never resolve walks off, and says what you had them as`() {
        val e = engine(giveUp = true)
        val calls = e.stations.map { it.call }
        val real = calls[0]
        val miscopy = unambiguousMiscopy(real, calls)
        assertNotNull("could not build a near-miss probe for $calls", miscopy)
        miscopy!!

        assertTrue(e.missedCallers.isEmpty() && e.lastMissedCaller == null)
        e.send(miscopy)     // patience 1: attempts 1, still there
        e.send(miscopy)     // attempts 2 > 1, gives up

        assertEquals(1, e.missedCallers.size)
        assertEquals(real, e.missedCallers.first().call)
        assertEquals(miscopy, e.missedCallers.first().miscopiedAs)
        assertEquals("the newest walk-off is offered for immediate feedback",
            real, e.lastMissedCaller?.call)

        e.clearLastMissedCaller()
        assertNull("a walk-off is shown only once", e.lastMissedCaller)
        assertEquals("the end-of-run list keeps it", 1, e.missedCallers.size)
    }
}
