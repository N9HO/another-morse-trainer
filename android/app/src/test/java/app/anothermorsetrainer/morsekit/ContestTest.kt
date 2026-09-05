package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Test

/** Contest scoring rules, held to the iOS MorseKitCheck behavior. */
class ContestTest {

    /**
     * Swift's `split(separator: " ")` drops empty pieces, so a logged exchange
     * with a trailing space ("599 OH ") still counts its SPC. The Kotlin port
     * used `lastOrNull()` on the raw split and silently dropped that entry.
     */
    @Test
    fun spcMultiplierSurvivesATrailingSpace() {
        val spc = ContestType.allCases.first { it.multiplierKind == MultiplierKind.SPC }
        assertEquals(2, spc.multiplierCount(emptyList(), listOf("599 OH ", "599 TX", "599 OH")))
        // A blank exchange still contributes nothing.
        assertEquals(1, spc.multiplierCount(emptyList(), listOf("", "   ", "599 OH")))
    }
}
