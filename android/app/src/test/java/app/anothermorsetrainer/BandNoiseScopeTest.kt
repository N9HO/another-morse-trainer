package app.anothermorsetrainer

import app.anothermorsetrainer.BackgroundNoiseLevel.HIGH
import app.anothermorsetrainer.BackgroundNoiseLevel.KEEP_ALIVE
import app.anothermorsetrainer.BackgroundNoiseLevel.LOW
import app.anothermorsetrainer.BackgroundNoiseLevel.OFF
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Band noise is scoped to practice (#331). [BackgroundNoiseLevel.effective] is
 * the rule [BackgroundNoise.refresh] renders: the band level only while it is
 * audible (a practice screen is open, or the Settings preview is running),
 * otherwise what the keep-alive switch alone gives. Mirrors iOS
 * `BackgroundNoiseLevel.effective(bluetoothKeepAlive:bandNoise:bandNoiseAudible:)`.
 */
class BandNoiseScopeTest {

    @Test
    fun bandNoiseSoundsWhilePractising() {
        assertEquals(LOW, BackgroundNoiseLevel.effective(false, LOW, bandNoiseAudible = true))
        assertEquals(HIGH, BackgroundNoiseLevel.effective(true, HIGH, bandNoiseAudible = true))
    }

    @Test
    fun awayFromPracticeOnlyTheKeepAliveSwitchCounts() {
        // The reported bug: band noise on, sitting on a menu — it must not hiss.
        assertEquals(OFF, BackgroundNoiseLevel.effective(false, HIGH, bandNoiseAudible = false))
        // The inaudible keep-alive floor still keeps an earbud link awake.
        assertEquals(KEEP_ALIVE, BackgroundNoiseLevel.effective(true, HIGH, bandNoiseAudible = false))
        assertEquals(KEEP_ALIVE, BackgroundNoiseLevel.effective(true, OFF, bandNoiseAudible = false))
        assertEquals(OFF, BackgroundNoiseLevel.effective(false, OFF, bandNoiseAudible = false))
    }

    @Test
    fun defaultIsAudibleSoTheStoredLevelIsUnchanged() {
        // Settings.backgroundNoise (persisted for older builds) still reads the
        // two controls alone, as before #331.
        assertEquals(LOW, BackgroundNoiseLevel.effective(true, LOW))
        assertEquals(KEEP_ALIVE, BackgroundNoiseLevel.effective(true, OFF))
        assertEquals(OFF, BackgroundNoiseLevel.effective(false, OFF))
    }
}
