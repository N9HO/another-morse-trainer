package app.anothermorsetrainer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Stats] must survive whatever is in SharedPreferences.
 *
 * These parsers run from `Stats.init`, which `MainActivity.onCreate` calls, so
 * anything they throw is an unrecoverable launch crash: the app dies on every
 * start and the only way back is clearing its data. `parseRecent` and
 * `parseChars` were unguarded, while `parseHistory` alone was wrapped —
 * an inconsistency nothing had noticed because nothing tested it.
 *
 * The iOS twin never had the bug; it decodes with `try? JSONDecoder()`, which
 * yields nil rather than throwing. So this is a one-tree fix and a one-tree test.
 */
class StatsParsingTest {

    private val garbage = listOf(
        "",                      // never written, but cheap to survive
        "not json at all",
        "{",                     // truncated
        "null",
        "[",
        "[{\"mode\":}]",         // malformed row
        "{\"E\":\"not an object\"}",
    )

    @Test
    fun `no input can crash the recent-sessions parser`() {
        for (bad in garbage) {
            assertTrue("threw or mis-parsed on ${bad.take(20)}", Stats.parseRecent(bad).isEmpty())
        }
    }

    @Test
    fun `no input can crash the character-stats parser`() {
        for (bad in garbage) {
            assertTrue("threw or mis-parsed on ${bad.take(20)}", Stats.parseChars(bad).isEmpty())
        }
    }

    @Test
    fun `no input can crash the history parser`() {
        for (bad in garbage) {
            assertTrue("threw or mis-parsed on ${bad.take(20)}", Stats.parseHistory(bad).isEmpty())
        }
    }

    @Test
    fun `a well-formed recent list still parses`() {
        val json = """
            [{"mode":"Characters","day":20000,"att":10,"cor":9,"ttr":450,
              "wpm":20,"med":600,"id":"abc"}]
        """.trimIndent()
        val out = Stats.parseRecent(json)
        assertEquals(1, out.size)
        assertEquals("Characters", out[0].mode)
        assertEquals(20000L, out[0].epochDay)
        assertEquals(10, out[0].attempts)
        assertEquals(9, out[0].correct)
        assertEquals(450, out[0].bestTtrMs)
        assertEquals(20, out[0].characterWpm)
        assertEquals(600, out[0].medianTtrMs)
        assertEquals("abc", out[0].recordId)
    }

    @Test
    fun `one bad row does not discard the good ones`() {
        // The point of guarding per row as well as overall: losing a single
        // session is much better than losing a year of them.
        val json = """
            [{"mode":"Characters","day":1,"att":1,"cor":1,"ttr":-1},
             {"mode":"Words"},
             {"mode":"Q-Codes","day":3,"att":3,"cor":3,"ttr":-1}]
        """.trimIndent()
        val out = Stats.parseRecent(json)
        assertEquals("the middle row is missing required keys", 2, out.size)
        assertEquals(listOf("Characters", "Q-Codes"), out.map { it.mode })
        assertNull("ttr of -1 means no correct answer was timed", out[0].bestTtrMs)
    }

    @Test
    fun `a well-formed character map still parses, and one bad entry is skipped`() {
        val json = """
            {"E":{"att":5,"cor":4,"ttrs":[300,400]},
             "T":{"att":"oops"},
             "A":{"att":2,"cor":2,"ttrs":[]}}
        """.trimIndent()
        val out = Stats.parseChars(json)
        assertEquals(setOf("E", "A"), out.keys)
        assertEquals(5, out["E"]!!.attempts)
        assertEquals(listOf(300, 400), out["E"]!!.ttrsMs)
        assertTrue(out["A"]!!.ttrsMs.isEmpty())
    }
}
