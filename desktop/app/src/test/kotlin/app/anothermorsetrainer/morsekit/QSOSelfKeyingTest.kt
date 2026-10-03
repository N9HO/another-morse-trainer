package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
