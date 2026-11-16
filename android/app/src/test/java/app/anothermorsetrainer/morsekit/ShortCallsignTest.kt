package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * "Short callsigns only" (#371): every generated call is four characters, US or
 * DX, whatever shapes are on. Twin of the short-callsign checks in the iOS
 * MorseKitCheck/main.swift.
 */
class ShortCallsignTest {

    private val all = CallsignFormat.entries

    @Test
    fun `short callsigns are four characters, US and DX`() {
        val rng = Random(371)
        repeat(400) {
            val us = CallsignGenerator.generate(all, usOnly = true, rng = rng, shortOnly = true)
            val any = CallsignGenerator.generate(all, usOnly = false, rng = rng, shortOnly = true)
            assertEquals(us, 4, us.length)
            assertEquals(any, 4, any.length)
        }
    }

    @Test
    fun `short callsigns still mix in DX prefixes`() {
        val rng = Random(371)
        val sawDx = (0 until 400).any {
            CallsignGenerator.generate(all, usOnly = false, rng = rng, shortOnly = true)[0] !in "KNWA"
        }
        assertTrue(sawDx)
    }

    @Test
    fun `with only long shapes on it falls back to 1x2 and 2x1`() {
        val rng = Random(371)
        repeat(200) {
            val c = CallsignGenerator.generate(listOf(CallsignFormat.OneByThree), usOnly = false, rng = rng, shortOnly = true)
            assertEquals(c, 4, c.length)
        }
    }

    @Test
    fun `off, a 1x3 call is five characters as before`() {
        val rng = Random(371)
        repeat(200) {
            val c = CallsignGenerator.generate(listOf(CallsignFormat.OneByThree), usOnly = true, rng = rng)
            assertEquals(c, 5, c.length)
        }
    }

    @Test
    fun `only 1x2 and 2x1 are four characters long`() {
        assertEquals(
            listOf(CallsignFormat.OneByTwo, CallsignFormat.TwoByOne),
            all.filter { it.length == CallsignFormat.SHORT_LENGTH }
        )
    }

    @Test
    fun `Rapid Fire short call signs are four characters`() {
        val q = RapidFireQuiz(
            RapidFireQuiz.Config(
                content = RapidFireContent.CALLSIGNS,
                callsignFormats = listOf(CallsignFormat.TwoByThree, CallsignFormat.OneByThree),
                callsignUSOnly = false,
                callsignShortOnly = true
            ),
            Random(371)
        )
        repeat(100) { assertEquals(4, q.nextDrill().correct.length) }
    }
}
