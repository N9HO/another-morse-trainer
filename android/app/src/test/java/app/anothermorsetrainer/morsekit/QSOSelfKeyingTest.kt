package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Contest keys on your side (#320): your CQ, your TU and each smart-box
 * send, carrying your callsign the way the iOS Pileup Runner and Contest do.
 * Pinned by `fixtures/qso-self-keying.json` at the repo root, shared with the
 * iOS harness and the other Kotlin tree's twin of this test.
 */
class QSOSelfKeyingTest {

    private fun fixture(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("qso-self-keying.json")
        assertNotNull("fixtures/qso-self-keying.json is not on the test classpath", stream)
        return JSONObject(stream!!.bufferedReader().readText())
    }

    private fun phase(s: String): PileupEngine.Phase = when (s) {
        "idle" -> PileupEngine.Phase.Idle
        "pileup" -> PileupEngine.Phase.Pileup
        "working" -> PileupEngine.Phase.Working(1)
        "readyToLog" -> PileupEngine.Phase.ReadyToLog(1)
        else -> throw AssertionError("unknown phase $s")
    }

    @Test
    fun `CQ carries your callsign as the shared fixture says`() {
        val cases = fixture().getJSONArray("cq")
        assertTrue("fixture has no CQ cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val code = c.getString("mode")
            val mode = QSOContestMode.entries.firstOrNull { it.code == code }
            assertNotNull("unknown mode $code", mode)
            assertEquals("CQ for $code", c.getString("text"),
                PileupEngine.cqText(mode!!, c.getString("call")))
        }
    }

    @Test
    fun `sign-off carries your callsign as the shared fixture says`() {
        val cases = fixture().getJSONArray("signOff")
        assertTrue("fixture has no sign-off cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.getString("text"), PileupEngine.signOffText(c.getString("call")))
        }
    }

    @Test
    fun `smart-box sends key what the shared fixture says`() {
        val cases = fixture().getJSONArray("send")
        assertTrue("fixture has no send cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val typed = c.getString("typed")
            val working = if (c.isNull("working")) null else c.getString("working")
            assertEquals("send \"$typed\" ${c.getString("pre")} -> ${c.getString("post")}",
                c.getString("text"),
                PileupEngine.selfSendText(typed, phase(c.getString("pre")), phase(c.getString("post")), working))
        }
    }

    // The run rules (#323): the same fixture pins how Contest builds its
    // engine and how a run answers a send around logging, as on iOS.

    private fun carried(c: PileupConfig, field: String): Any = when (field) {
        "toneSpread" -> c.toneSpread
        "minVolume" -> c.minVolume
        "maxVolume" -> c.maxVolume
        "minDelay" -> c.minDelay
        "maxDelay" -> c.maxDelay
        "qsbEnabled" -> c.qsbEnabled
        "qrnLevel" -> c.qrnLevel
        "cutNumbersEnabled" -> c.cutNumbersEnabled
        "cutDigits" -> c.cutDigits
        "bustBehavior" -> c.bustBehavior
        "giveUpEnabled" -> c.giveUpEnabled
        "usOnly" -> c.usOnly
        else -> throw AssertionError("unknown carried field $field")
    }

    @Test
    fun `Contest config carries the Pileup realism settings as the shared fixture says`() {
        val section = fixture().getJSONObject("contestConfig")
        val r = section.getJSONObject("realism")
        val base = PileupConfig(
            mode = QSOContestMode.entries.first { it.code == r.getString("mode") },
            maxStations = r.getInt("maxStations"),
            minWPM = r.getDouble("minWPM"),
            maxWPM = r.getDouble("maxWPM"),
            toneSpread = r.getDouble("toneSpread"),
            minVolume = r.getDouble("minVolume").toFloat(),
            maxVolume = r.getDouble("maxVolume").toFloat(),
            minDelay = r.getDouble("minDelay"),
            maxDelay = r.getDouble("maxDelay"),
            qsbEnabled = r.getBoolean("qsbEnabled"),
            qrnLevel = r.getDouble("qrnLevel").toFloat(),
            cutNumbersEnabled = r.getBoolean("cutNumbersEnabled"),
            cutDigits = r.getString("cutDigits").toSet(),
            rstRequired = r.getBoolean("rstRequired"),
            bustBehavior = BustBehavior.entries.first { it.code == r.getString("bustBehavior") },
            giveUpEnabled = r.getBoolean("giveUpEnabled"),
            usOnly = r.getBoolean("usOnly")
        )
        val fields = section.getJSONArray("carried")
        val cases = section.getJSONArray("cases")
        assertTrue("fixture has no contest config cases", cases.length() > 0 && fields.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val code = c.getString("contest")
            val contest = ContestType.entries.firstOrNull { it.code == code }
            assertNotNull("unknown contest $code", contest)
            val got = base.forContest(contest!!, c.getInt("maxCallers"))
            assertEquals("$code exchange", c.getString("mode"), got.mode.code)
            assertEquals("$code min WPM", c.getDouble("minWPM"), got.minWPM, 1e-9)
            assertEquals("$code max WPM", c.getDouble("maxWPM"), got.maxWPM, 1e-9)
            assertEquals("$code callers", c.getInt("maxStations") + 1, got.maxStations)
            assertFalse("$code requires no RST", got.rstRequired)
            for (j in 0 until fields.length()) {
                val f = fields.getString(j)
                assertEquals("$code keeps realism $f", carried(base, f), carried(got, f))
            }
        }
    }

    @Test
    fun `a send logs a copied exchange as the shared fixture says`() {
        val cases = fixture().getJSONArray("logOnSend")
        assertTrue("fixture has no logOnSend cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val typed = c.getString("typed")
            val e = PileupEngine(PileupConfig(mode = QSOContestMode.SingleCaller, giveUpEnabled = false))
            e.callCQ()
            val s = e.stations.first()
            e.send(s.call)
            when (c.getString("phase")) {
                "working" -> assertTrue("reached working", e.phase is PileupEngine.Phase.Working)
                "readyToLog" -> {
                    e.send(e.expectedCopy ?: "")
                    assertTrue("reached readyToLog", e.phase is PileupEngine.Phase.ReadyToLog)
                }
                else -> throw AssertionError("unknown phase ${c.getString("phase")}")
            }
            val action = e.send(typed)
            val logged = action == PileupEngine.Action.Logged(s.call) && e.qsoCount == 1
            assertEquals("\"$typed\" in ${c.getString("phase")} logs", !c.getBoolean("logs"), logged)
        }
    }

    @Test
    fun `re-call after TU matches the shared fixture`() {
        val cases = fixture().getJSONArray("recallAfterLog")
        assertTrue("fixture has no recallAfterLog cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val code = c.getString("mode")
            val e = PileupEngine(PileupConfig(
                mode = QSOContestMode.entries.first { it.code == code },
                maxStations = c.getInt("maxStations"),
                giveUpEnabled = false
            ))
            e.callCQ()
            val s = e.stations.first()
            e.send(s.call)
            e.send(e.expectedCopy ?: "")
            assertEquals("$code logs its first station", PileupEngine.Action.Logged(s.call), e.send("TU"))
            val waiting = e.activeCount
            val action = e.recallAfterLog(c.getBoolean("autoRecall"))
            val label = "$code re-call ${c.getBoolean("autoRecall")}"
            if (!c.getBoolean("recalls")) {
                assertTrue("$label plays", action is PileupEngine.Action.Play)
                assertTrue("$label leaves callers waiting", waiting > 0)
                assertEquals("$label: one voice per waiting caller", waiting, (action as PileupEngine.Action.Play).voices.size)
                assertTrue("$label stays in the pileup", e.phase is PileupEngine.Phase.Pileup)
            } else {
                assertEquals(label, PileupEngine.Action.Silence, action)
            }
        }
    }
}
