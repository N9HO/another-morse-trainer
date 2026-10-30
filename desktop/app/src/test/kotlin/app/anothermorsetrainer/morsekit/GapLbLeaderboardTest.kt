package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaderboard parity rows 06-09 (display name, metric rounding, post-run line,
 * Settings visibility), pinned to `fixtures/gap-lb-display-name.json`,
 * `gap-lb-metric.json` and `gap-lb-result-line.json`.
 */
class GapLbLeaderboardTest {
    private fun cases(name: String): JSONArray {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
        assertNotNull("fixtures/$name is not on the test classpath", stream)
        return JSONObject(stream!!.bufferedReader().use { it.readText() }).getJSONArray("cases")
    }

    private fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    @Test
    fun `display name rules and messages match the fixture`() {
        val cs = cases("gap-lb-display-name.json")
        assertEquals(23, cs.length())
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            val raw = c.getString("raw")
            assertEquals("problem for '$raw'", c.strOrNull("problem"), Leaderboard.displayNameProblem(raw))
            assertEquals("normalized for '$raw'", c.strOrNull("normalized"), Leaderboard.normalizeDisplayName(raw))
            assertEquals("valid for '$raw'", c.isNull("problem"), Leaderboard.isValidDisplayName(raw))
        }
    }

    @Test
    fun `metric label rounds to nearest like iOS`() {
        val cs = cases("gap-lb-metric.json")
        assertEquals(8, cs.length())
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            assertEquals("metric ${c.getDouble("metric")}", c.getString("expected"), Leaderboard.metricLabel(c.getDouble("metric")))
            assertEquals(c.getString("expected"), Leaderboard.roundedMetric(c.getDouble("metric")).toString())
        }
    }

    @Test
    fun `post-run line matches the fixture`() {
        val cs = cases("gap-lb-result-line.json")
        assertEquals(6, cs.length())
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            val actual = Leaderboard.resultLine(
                kind = c.getString("kind"),
                rank = c.optInt("rank", 0),
                metric = c.optDouble("metric", 0.0),
                correct = c.optInt("correct", 0),
                total = c.optInt("total", 0),
                personalBest = c.optBoolean("personalBest", false),
                reason = c.optString("reason", "")
            )
            val detail = c.strOrNull("detail")
            val expected = if (detail == null) c.getString("headline") else c.getString("headline") + "\n" + detail
            assertEquals(c.getString("name"), expected, actual)
        }
    }

    @Test
    fun `name and delete rows show with sharing off`() {
        assertTrue(Leaderboard.showsNameAndDeleteRows(sharing = false))
        assertTrue(Leaderboard.showsNameAndDeleteRows(sharing = true))
    }
}
