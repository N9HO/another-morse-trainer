package app.anothermorsetrainer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.random.Random

/**
 * The sidetone's key envelope, pinned without an `AudioTrack`.
 *
 * What a click-free gate has to do: reach the key's target exactly, in the
 * documented time; never overshoot it; and never move more than one ramp step
 * between neighbouring samples, whatever the key does. The overshoot guard
 * this replaced could not fire (see [SidetoneSynth]), so these are the
 * properties it was meant to guarantee, now held by the ramp itself.
 */
class SidetoneSynthTest {

    private val sampleRate = 44_100
    private val amplitude = 0.5f
    private val rampSeconds = 0.005

    /** Samples for the full ramp: 220.5 steps, so 221 — the last one lands short. */
    private val rampSamples = ceil(rampSeconds * sampleRate).toInt()

    private fun synth() = SidetoneSynth(sampleRate, 600.0, amplitude, rampSeconds)

    /** Advance one sample at a time so every intermediate gain is visible. */
    private fun step(s: SidetoneSynth, keyDown: Boolean, samples: Int): List<Float> {
        val buf = FloatArray(1)
        return List(samples) { s.render(buf, 1, keyDown); s.gain }
    }

    @Test
    fun keyDownLandsExactlyOnFullAmplitudeAtTheRampEnd() {
        val s = synth()
        val rising = step(s, keyDown = true, samples = rampSamples - 1)
        assertTrue("still ramping one sample early", rising.last() < amplitude)
        assertTrue("the ramp rises monotonically", rising.zipWithNext().all { (a, b) -> b > a })

        step(s, keyDown = true, samples = 1)
        assertEquals("lands on the target exactly", amplitude * 0.9f, s.gain, 0f)

        val held = step(s, keyDown = true, samples = 500)
        assertTrue("and stays there, bit for bit", held.all { it == amplitude })
    }

    @Test
    fun keyUpLandsExactlyOnSilence() {
        val s = synth()
        step(s, keyDown = true, samples = rampSamples)
        assertEquals(amplitude, s.gain, 0f)

        val falling = step(s, keyDown = false, samples = rampSamples - 1)
        assertTrue("still ramping one sample early", falling.last() > 0f)
        assertTrue("the ramp falls monotonically", falling.zipWithNext().all { (a, b) -> b < a })

        step(s, keyDown = false, samples = 1)
        assertEquals("lands on silence exactly", 0.001f, s.gain, 0f)

        val buf = FloatArray(441)
        s.render(buf, buf.size, keyDown = false)
        assertTrue("silence is all zeros, not a whisper", buf.all { it == 0f })
    }

    @Test
    fun releasingMidRampTurnsBackWithoutASnap() {
        val s = synth()
        val up = step(s, keyDown = true, samples = 100)
        val top = up.last()
        assertTrue(top > 0f && top < amplitude)

        val down = step(s, keyDown = false, samples = 101)
        assertTrue(
            "the first sample after release is one step lower, not a jump",
            abs((top - down.first()) - s.rampStep) < 1e-6f
        )
        assertTrue("falls monotonically", down.zipWithNext().all { (a, b) -> b <= a })
        assertEquals("back to silence in the time it took to rise", 0f, s.gain, 0f)
    }

    /**
     * The invariants under any keying at all: the envelope stays within
     * 0..amplitude, neighbouring samples never differ by more than one step,
     * and no sample is louder than the envelope allows.
     */
    @Test
    fun envelopeNeverOvershootsOrJumpsUnderRandomKeying() {
        val s = synth()
        val rng = Random(11)
        val buf = FloatArray(1)
        var previous = s.gain
        var keyDown = false
        repeat(2 * sampleRate) { i ->
            // A new key state every few ms, so the ramp is often reversed mid-way.
            if (i % 97 == 0) keyDown = rng.nextBoolean()
            s.render(buf, 1, keyDown)
            val g = s.gain
            assertTrue("gain $g left 0..$amplitude at sample $i", g in 0f..amplitude)
            assertTrue(
                "gain jumped ${abs(g - previous)} at sample $i",
                abs(g - previous) <= s.rampStep * (1f + 1e-5f)
            )
            assertTrue("sample louder than its envelope at $i", abs(buf[0]) <= g * (1f + 1e-5f))
            previous = g
        }
    }
}
