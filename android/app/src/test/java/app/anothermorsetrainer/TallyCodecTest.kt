package app.anothermorsetrainer

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session tally's trip through the saved-instance-state bundle.
 *
 * This is the piece of the process-death fix that a JVM test can reach: the
 * screens keep their [Tally] under `rememberSaveable` with [TallySaver], and
 * what that saver writes and reads is [Tally.encode] and [Tally.decode]. If
 * the round trip loses a field, the restored session records the wrong
 * numbers; if a stale bundle can throw, the restore crashes the launch.
 */
class TallyCodecTest {

    private fun practised(): Tally {
        val t = Tally(startedAtMs = 1_700_000_000_000L)
        t.attempts = 7
        t.correct = 5
        t.noteCorrectMs(900)
        t.noteCorrectMs(400)
        t.noteCorrectMs(650)
        t.noteChar("K", correct = true, ms = 900)
        t.noteChar("K", correct = false, ms = 0)
        t.noteChar("M", correct = true, ms = 400)
        return t
    }

    @Test
    fun `a practised tally survives the round trip`() {
        val t = practised()
        val back = Tally.decode(t.encode())
        assertTrue("decode returned null", back != null)
        back!!
        assertEquals(7, back.attempts)
        assertEquals(5, back.correct)
        assertEquals(401, back.bestMs)   // CONTROL: wrong on purpose
        assertEquals(650, back.medianMs())
        assertEquals(t.startedAtMs, back.startedAtMs)
        assertEquals(t.charResults(), back.charResults())
    }

    @Test
    fun `a restored tally keeps counting where it left off`() {
        val back = Tally.decode(practised().encode())!!
        back.attempts += 1
        back.correct += 1
        back.noteCorrectMs(300)
        back.noteChar("K", correct = true, ms = 300)
        assertEquals(8, back.attempts)
        assertEquals(300, back.bestMs)
        // 300 400 650 900 → (400 + 650) / 2
        assertEquals(525, back.medianMs())
        assertEquals(3, back.charResults().first { it.character == "K" }.attempts)
    }

    @Test
    fun `a fresh tally round-trips as fresh`() {
        val back = Tally.decode(Tally().encode())!!
        assertEquals(0, back.attempts)
        assertEquals(0, back.correct)
        assertNull(back.bestMs)
        assertNull(back.medianMs())
        assertTrue(back.charResults().isEmpty())
    }

    @Test
    fun `elapsed time is measured from the original start`() {
        val started = System.currentTimeMillis() - 90_000L
        val back = Tally.decode(Tally(startedAtMs = started).encode())!!
        assertTrue("elapsed ${back.elapsedSeconds()} should be at least 90", back.elapsedSeconds() >= 90)
    }

    @Test
    fun `a stale or wrecked bundle restores as nothing, not a crash`() {
        val garbage = listOf(
            "",
            "not json",
            "{",
            "null",
            "[]",
            "{\"attempts\":1}",                       // missing fields
            "{\"attempts\":\"seven\",\"correct\":0}", // wrong types
        )
        for (bad in garbage) {
            assertNull("decoded something from ${bad.take(20)}", Tally.decode(bad))
        }
    }

    @Test
    fun `the saver is the codec`() {
        // Not a Compose test — just that the saver's two lambdas are wired to
        // encode/decode and nothing else. SaverScope's only member is a
        // can-be-saved check, which a string always passes.
        val t = practised()
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = with(TallySaver) { scope.save(t) }
        assertEquals(t.encode(), saved)
        assertEquals(t.charResults(), TallySaver.restore(saved!!)!!.charResults())
    }
}
