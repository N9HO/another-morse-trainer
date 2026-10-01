package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertTrue
import org.junit.Test

/** The Reference's lingo glossary (issue #76), held to the iOS data rules. */
class MorseReferenceTest {

    @Test
    fun lingoIsWellFormed() {
        val terms = MorseReference.lingo.map { it.token }
        assertTrue("expected a healthy number of terms", terms.size >= 20)
        assertTrue("terms must be unique", terms.toSet().size == terms.size)
        assertTrue("every term must be fully sendable in Morse",
            terms.all { CWText.isFullySendable(it) })
        assertTrue("every term needs a meaning",
            MorseReference.lingo.all { it.meaning.isNotEmpty() })
    }

    @Test
    fun lingoStaysDisjointFromShorthandTables() {
        // Shorthand that is *sent* on the air lives in abbreviations/Q-codes,
        // not in the lingo glossary.
        val shorthand = (MorseData.abbreviations.map { it.token } +
            MorseData.qCodes.map { it.token }).toSet()
        assertTrue(MorseReference.lingo.none { it.token in shorthand })
    }

    @Test
    fun lingoItemIdsAreNamespacedAndUnique() {
        val ids = MorseReference.lingoItems.map { it.id }
        assertTrue(ids.toSet().size == ids.size)
        assertTrue(ids.all { it.startsWith("lingo-") })
    }
}
