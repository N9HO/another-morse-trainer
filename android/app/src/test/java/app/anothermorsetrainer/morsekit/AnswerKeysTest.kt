package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hardware-keyboard answer mapping (issue #69) and the off-by-one it caused
 * (issue #30). Twin of the `Answer keys` section in MorseKitCheck/main.swift.
 */
class AnswerKeysTest {

    @Test
    fun `every option is its own key when all are single characters`() {
        val options = listOf("A", "B", "4", "5")
        assertEquals(listOf('a', 'b', '4', '5'), AnswerKeys.assign(options))
        assertEquals(2, AnswerKeys.optionFor('4', options))
    }

    /**
     * The reported bug: one multi-character distractor flipped the whole grid to
     * positional numbering, so hearing `4` and pressing `4` answered the fourth
     * option. With the options in order that reads as a consistent off-by-one —
     * press 4, get 3.
     */
    @Test
    fun `a lone multi-character option no longer renumbers the digits`() {
        val options = listOf("3", "4", "5", "<AR>")
        assertEquals(1, AnswerKeys.optionFor('4', options))
        assertEquals(0, AnswerKeys.optionFor('3', options))
        assertEquals(2, AnswerKeys.optionFor('5', options))
        // The one option with no character of its own takes the first free digit.
        assertEquals('1', AnswerKeys.assign(options).last())
        assertEquals(3, AnswerKeys.optionFor('1', options))
    }

    @Test
    fun `positions skip a digit an option already claims`() {
        val options = listOf("1", "GOING QRT", "BEST REGARDS", "OVER")
        val keys = AnswerKeys.assign(options)
        assertEquals(listOf('1', '2', '3', '4'), keys)
        val assigned = keys.filterNotNull()
        assertEquals("no two options may share a key", assigned.size, assigned.toSet().size)
    }

    @Test
    fun `no two options share a key even when digits are scattered`() {
        val options = listOf("<AR>", "2", "ES", "73", "1", "9")
        val assigned = AnswerKeys.assign(options).filterNotNull()
        assertEquals(assigned.size, assigned.toSet().size)
        // Every single-character option still answers to itself.
        assertEquals(1, AnswerKeys.optionFor('2', options))
        assertEquals(4, AnswerKeys.optionFor('1', options))
        assertEquals(5, AnswerKeys.optionFor('9', options))
    }

    @Test
    fun `matching is case-insensitive`() {
        val options = listOf("A", "B", "ES")
        assertEquals(1, AnswerKeys.optionFor('b', options))
        assertEquals(1, AnswerKeys.optionFor('B', options))
    }

    @Test
    fun `an unmapped key answers nothing`() {
        assertNull(AnswerKeys.optionFor('z', listOf("A", "B", "4")))
        assertNull(AnswerKeys.optionFor('1', listOf("A", "B", "4")))
        assertNull(AnswerKeys.optionFor('a', emptyList()))
    }

    @Test
    fun `only the longer options carry a position hint`() {
        val options = listOf("3", "4", "5", "<AR>")
        assertFalse(AnswerKeys.needsPositionHint(options, 0))
        assertTrue(AnswerKeys.needsPositionHint(options, 3))
        assertFalse(AnswerKeys.needsPositionHint(options, 9))
    }

    @Test
    fun `options past the ninth get no key`() {
        val options = (1..12).map { "OPTION $it" }
        assertEquals(9, AnswerKeys.assign(options).count { it != null })
        assertNull(AnswerKeys.assign(options)[9])
    }

    /** A duplicated single-character option can't claim the same key twice. */
    @Test
    fun `a duplicate option falls back to a position`() {
        val options = listOf("A", "A", "ES")
        val keys = AnswerKeys.assign(options)
        assertEquals('a', keys[0])
        assertEquals(0, AnswerKeys.optionFor('a', options))
        assertEquals(keys.filterNotNull().size, keys.filterNotNull().toSet().size)
    }
}
