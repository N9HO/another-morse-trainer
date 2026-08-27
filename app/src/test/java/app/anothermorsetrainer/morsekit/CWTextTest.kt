package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CW text sanitizer, held to the iOS CWText behavior. */
class CWTextTest {

    @Test
    fun sendability() {
        assertTrue(CWText.isSendable('A'))
        assertTrue(CWText.isSendable('0'))
        assertTrue(CWText.isSendable(' '))
        assertTrue(CWText.isSendable('?'))
        assertTrue(!CWText.isSendable('#'))
        assertTrue(CWText.isFullySendable("CQ DE N9HO"))
        assertTrue(!CWText.isFullySendable("HELLO!"))
    }

    @Test
    fun stripsHtmlAndEntities() {
        assertEquals("Fish & chips", CWText.strippedHTML("Fish &amp; chips"))
        val stripped = CWText.strippedHTML("&lt;p&gt;Breaking: markets &amp; more&lt;/p&gt;")
        assertTrue(stripped.contains("Breaking: markets & more"))
        assertTrue(!stripped.contains("<p>"))
        assertEquals("It’s here", CWText.strippedHTML("It&#8217;s here"))
    }

    @Test
    fun sanitizesToTheSendableSet() {
        assertEquals("DONT PANIC", CWText.sanitized("Don't panic"))
        assertEquals("1234 HAMS", CWText.sanitized("1,234 hams"))
        assertEquals("YES, AND NO", CWText.sanitized("yes; and no"))
        assertEquals("FISH AND CHIPS", CWText.sanitized("Fish & Chips"))
        assertEquals("UP 5 PERCENT TODAY.", CWText.sanitized("Up 5% today!"))
        assertEquals("CAFE AU LAIT", CWText.sanitized("Café au lait"))
        assertEquals("A B", CWText.sanitized("A — B"))
        // Idempotent apart from uppercasing.
        val once = CWText.sanitized("Mixed 3,4 case? Sure — why not…")
        assertEquals(once, CWText.sanitized(once))
    }

    @Test
    fun clipsAtSentenceBoundaries() {
        val text = "One two three. Four five six seven eight nine ten eleven"
        assertEquals("One two three.", CWText.clipped(text, maxWords = 5))
        assertEquals(text, CWText.clipped(text, maxWords = 50))
        val noStop = "one two three four five six"
        assertEquals("one two three.", CWText.clipped(noStop, maxWords = 3))
    }
}
