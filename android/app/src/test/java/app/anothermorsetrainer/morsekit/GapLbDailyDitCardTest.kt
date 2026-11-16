package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Home Daily Dit card text (gap row-25), pinned to `fixtures/gap-lb-daily-dit-card.json`. */
class GapLbDailyDitCardTest {
    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("gap-lb-daily-dit-card.json")
        assertNotNull("fixtures/gap-lb-daily-dit-card.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().use { it.readText() })
    }

    @Test
    fun `home card subtitle matches the fixture`() {
        val cases = fixture.getJSONArray("cases")
        assertEquals(6, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val actual = DailyDit.homeSubtitle(
                outcome = if (c.getString("outcome") == "solved") DailyDitOutcome.SOLVED else DailyDitOutcome.PLAYING,
                puzzleNumber = c.getInt("puzzleNumber"),
                solvedWpm = if (c.isNull("solvedWpm")) null else c.getDouble("solvedWpm"),
                guessesUsed = c.getInt("guessesUsed"),
                listens = c.getInt("listens")
            )
            assertEquals(c.getString("name"), c.getString("expected"), actual)
        }
    }
}
