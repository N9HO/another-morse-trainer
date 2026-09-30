package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The alphabet's reverse lookups, held to what the iOS MorseKitCheck harness pins. */
class MorseCodeTest {

    /** Mirrors main.swift's "Morse decoder" reverse-lookup checks. */
    @Test
    fun reverseLookupMatchesTheHarness() {
        assertEquals('X', MorseCode.characterForPattern("-..-"))
        assertEquals('A', MorseCode.character(listOf(MorseCode.Element.DIT, MorseCode.Element.DAH)))
        assertNull(MorseCode.characterForPattern("........"))
    }

    /** The string overload and the element overload agree on every character. */
    @Test
    fun patternOverloadsAgree() {
        for (ch in MorseCode.alphabet) {
            val pattern = MorseCode.pattern(ch)!!
            assertEquals(ch, MorseCode.characterForPattern(pattern))
            assertEquals(ch, MorseCode.character(MorseCode.elements(ch)))
        }
    }
}
