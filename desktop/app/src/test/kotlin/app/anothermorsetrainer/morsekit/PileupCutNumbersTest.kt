package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Cut numbers and the habitual signal report in the exchange (#38). Twin of the
 * `#38` checks in the iOS repo's MorseKitCheck/main.swift.
 *
 * An operator sends "5NN" from muscle memory even in the exchanges that never
 * carry a report (SST, CWT, MST, Sprint, Field Day), types it run together with
 * the exchange when the sending box is a single field, and writes a Field Day
 * class in cut form. All three graded as a bust, so the station simply repeated
 * its exchange and the QSO could never be completed.
 */
class PileupCutNumbersTest {

    /** One station, worked, with the exchange already sent: ready to be copied. */
    private fun worked(mode: QSOContestMode, seed: Int = 11, rstRequired: Boolean = false):
        Pair<PileupEngine, String> {
        val e = PileupEngine(
            PileupConfig(mode = mode, maxStations = 1, rstRequired = rstRequired),
            Random(seed)
        )
        e.callCQ()
        e.send(e.stations[0].call)
        return e to (e.expectedCopy ?: "")
    }

    private fun accepted(e: PileupEngine, text: String) = e.send(text) == PileupEngine.Action.Silence

    @Test
    fun `a report typed in front is accepted where the exchange carries none`() {
        for (mode in listOf(
            QSOContestMode.Sst, QSOContestMode.Cwt, QSOContestMode.Mst,
            QSOContestMode.Sprint, QSOContestMode.FieldDay
        )) {
            val copy = worked(mode).second
            assertEquals(
                "$mode should accept a habitual 5NN in front of '$copy'",
                true, accepted(worked(mode).first, "5NN $copy")
            )
            assertEquals(
                "$mode should accept the same report written 599",
                true, accepted(worked(mode).first, "599 $copy")
            )
        }
    }

    @Test
    fun `a report run together with the exchange is accepted`() {
        for (mode in listOf(
            QSOContestMode.Pota, QSOContestMode.SingleCaller, QSOContestMode.BasicContest
        )) {
            val copy = worked(mode).second
            assertEquals(
                "$mode should accept '5NN' glued onto '$copy'",
                true, accepted(worked(mode).first, "5NN" + copy.replace(" ", ""))
            )
        }
    }

    @Test
    fun `a report in front does not rescue a wrong exchange`() {
        assertEquals(false, accepted(worked(QSOContestMode.Sst).first, "5NN XX YY"))
        assertEquals(false, accepted(worked(QSOContestMode.Sst).first, "5NN"))
    }

    @Test
    fun `a Field Day class copied in cut numbers grades correct`() {
        val (_, copy) = worked(QSOContestMode.FieldDay)
        val parts = copy.split(" ")
        assertEquals("Field Day sends a class and a section", 2, parts.size)
        val cutClass = CutNumbers.encode(parts[0], CutNumbers.cuttableDigits.toSet())
        assertEquals(
            "'$cutClass' is the cut form of class '${parts[0]}'",
            true, accepted(worked(QSOContestMode.FieldDay).first, "$cutClass ${parts[1]}")
        )
        // The category letter is a letter, not a cut digit: "U" is not a "B".
        assertEquals(
            false,
            accepted(worked(QSOContestMode.FieldDay).first, "${parts[0].take(1)}U ${parts[1]}")
        )
    }

    @Test
    fun `opting in to the report keeps it required`() {
        val (_, full) = worked(QSOContestMode.Pota, rstRequired = true)   // "599 OH"
        assertEquals(
            "copying the required report is accepted",
            true, accepted(worked(QSOContestMode.Pota, rstRequired = true).first, full)
        )
        val withoutReport = full.split(" ").drop(1).joinToString(" ")
        assertEquals(
            "omitting a required report is still a miss",
            false, accepted(worked(QSOContestMode.Pota, rstRequired = true).first, withoutReport)
        )
        assertNotEquals("the required copy carries the report", full, withoutReport)
    }
}
