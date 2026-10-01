package app.anothermorsetrainer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Farnsworth switch decides; the remembered effective speed does not (#180).
 *
 * In 1.14.1 "off" was implied by an effective speed equal to the character
 * speed, so raising the character speed left the old effective value behind
 * and Farnsworth quietly came back on. [resolveTiming] is the rule behind
 * [Settings.timing] and [Settings.effectiveWpmInUse], pulled out as a pure
 * function so this test can reach it without the settings store.
 */
class FarnsworthSwitchTest {

    @Test
    fun switchOffPlaysStandardTimingWhateverTheEffectiveSpeedRemembers() {
        // The reported path: Farnsworth off, effective speed still remembering
        // 18, character speed raised past it.
        val t = resolveTiming(characterWpm = 40.0, farnsworthEnabled = false, effectiveWpm = 18.0)
        assertEquals(40.0, t.wpm, 0.0)
        assertEquals(40.0, t.effectiveWpm, 0.0)
        assertEquals(t.elementGap, t.spacingUnit, 1e-12)
    }

    @Test
    fun switchOnStretchesSpacingToTheEffectiveSpeed() {
        val t = resolveTiming(characterWpm = 40.0, farnsworthEnabled = true, effectiveWpm = 18.0)
        assertEquals(40.0, t.wpm, 0.0)
        assertEquals(18.0, t.effectiveWpm, 0.0)
    }

    @Test
    fun switchOnWithEffectiveAtOrAboveCharacterIsStandardTiming() {
        // Equal: nothing to stretch. Above: clamped to the character speed, as
        // the slider's top is.
        assertEquals(25.0, resolveTiming(25.0, true, 25.0).effectiveWpm, 0.0)
        assertEquals(25.0, resolveTiming(25.0, true, 30.0).effectiveWpm, 0.0)
    }
}
