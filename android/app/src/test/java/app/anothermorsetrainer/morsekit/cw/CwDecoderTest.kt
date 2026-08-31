package app.anothermorsetrainer.morsekit.cw

import app.anothermorsetrainer.morsekit.MorseCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin

/**
 * Synthetic-PCM checks for the ported CW decoder core — the same scenarios
 * the firmware bench (iOS `MorseKitCheck`) runs against the vendored C core,
 * so the port is held to the C core's observed behavior: clean copy,
 * mistuned pitch search, seeded noise, 48 kHz decimation, and reset re-arm.
 */
class CwDecoderTest {

    /** The bench's tiny seedable RNG (xorshift64*), so noise is reproducible. */
    private class SeededRng(seed: ULong) {
        var state: ULong = if (seed == 0uL) 0x9E3779B97F4A7C15uL else seed
        fun next(): ULong {
            state = state xor (state shr 12)
            state = state xor (state shl 25)
            state = state xor (state shr 27)
            return state * 0x2545F4914F6CDD1DuL
        }
    }

    /**
     * Render hard-keyed CW as 16-bit PCM: a calibration lead-in of silence,
     * the message at ITU timing, and enough tail to flush the last character.
     * Noise is seeded and uniform, so every run hears identical samples.
     */
    private fun renderCW(
        message: String,
        wpm: Double,
        toneHz: Double,
        sampleRate: Double,
        amplitude: Double,
        noiseAmplitude: Double = 0.0,
        noiseSeed: ULong = 0uL
    ): ShortArray {
        val unit = 1.2 / wpm
        val segments = ArrayList<Pair<Double, Double>>()   // tone, gap
        val words = message.split(" ").filter { it.isNotEmpty() }
        for ((w, word) in words.withIndex()) {
            val chars = word.toCharArray()
            for ((c, ch) in chars.withIndex()) {
                val pattern = MorseCode.pattern(ch) ?: continue
                for ((i, element) in pattern.withIndex()) {
                    val tone = if (element == '.') unit else 3 * unit
                    val lastElement = i == pattern.length - 1
                    val lastChar = c == chars.size - 1
                    val gap = when {
                        !lastElement -> unit
                        !lastChar -> 3 * unit
                        w == words.size - 1 -> 10 * unit
                        else -> 7 * unit
                    }
                    segments.add(tone to gap)
                }
            }
        }
        val rng = SeededRng(if (noiseSeed == 0uL) 1uL else noiseSeed)
        fun noise(): Double =
            if (noiseAmplitude == 0.0) 0.0
            else ((rng.next() shr 40).toLong().toDouble() / 8_388_608.0 - 1.0) * noiseAmplitude

        val samples = ArrayList<Short>()
        fun push(value: Double) {
            samples.add(max(-32767.0, min(32767.0, value + noise())).toInt().toShort())
        }
        repeat((sampleRate * 0.7).toInt()) { push(0.0) }   // noise-floor calibration
        val omega = 2.0 * Math.PI * toneHz / sampleRate
        for ((tone, gap) in segments) {
            for (i in 0 until (tone * sampleRate).toInt()) push(amplitude * sin(omega * i))
            repeat((gap * sampleRate).toInt()) { push(0.0) }
        }
        repeat((sampleRate * 0.5).toInt()) { push(0.0) }   // let the tail flush
        return samples.toShortArray()
    }

    private class Decoded(val text: String, val wpm: Float, val toneHz: Float)

    /**
     * Run PCM through the ported core; passes=2 replays the same audio after
     * a reset() to prove the decoder rearms cleanly.
     */
    private fun decodeCW(samples: ShortArray, inputRate: Int, passes: Int = 1): Decoded {
        val sink = StringBuilder()
        val cfg = CwDecoder.Config()
        cfg.inputRateHz = inputRate
        cfg.targetRateHz = 8000
        cfg.onSymbol = { text, _ -> sink.append(text) }
        val decoder = CwDecoder.create(cfg) ?: return Decoded("<create failed>", 0f, 0f)
        val texts = ArrayList<String>()
        for (pass in 0 until passes) {
            if (pass > 0) {
                decoder.reset()
                sink.setLength(0)
            }
            decoder.feed(samples)
            texts.add(sink.toString().trim())
        }
        return Decoded(texts.joinToString("|"), decoder.wpm, decoder.toneHz)
    }

    private val message = "CQ CQ DE N9HO N9HO K"

    @Test
    fun decodesClean25WpmAt700Hz() {
        val clean = decodeCW(
            renderCW(message, wpm = 25.0, toneHz = 700.0, sampleRate = 8000.0, amplitude = 8000.0),
            inputRate = 8000
        )
        assertEquals(message, clean.text)
        assertTrue("speed estimate ${clean.wpm} should land near 25 WPM", clean.wpm > 20f && clean.wpm < 30f)
    }

    @Test
    fun pitchSearchRecoversMistunedSignal() {
        val mistuned = decodeCW(
            renderCW(message, wpm = 25.0, toneHz = 620.0, sampleRate = 8000.0, amplitude = 8000.0),
            inputRate = 8000
        )
        assertEquals(message, mistuned.text)
        assertTrue(
            "locked tone ${mistuned.toneHz} should sit by the real 620 Hz",
            abs(mistuned.toneHz - 620f) < 25f
        )
    }

    @Test
    fun staysSolidThroughSeededNoise() {
        // ≈20 dB SNR — far inside the ~11 dB limit measured for the C core, so
        // the committed case stays deterministic across platforms.
        val noisy = decodeCW(
            renderCW(
                message, wpm = 25.0, toneHz = 700.0, sampleRate = 8000.0,
                amplitude = 8000.0, noiseAmplitude = 1000.0, noiseSeed = 42uL
            ),
            inputRate = 8000
        )
        assertEquals(message, noisy.text)
    }

    @Test
    fun decimatesFrom48kHz() {
        val hires = decodeCW(
            renderCW("PARIS", wpm = 20.0, toneHz = 700.0, sampleRate = 48000.0, amplitude = 8000.0),
            inputRate = 48000
        )
        assertEquals("PARIS", hires.text)
    }

    @Test
    fun resetRearmsForASecondRun() {
        val twice = decodeCW(
            renderCW("PARIS", wpm = 20.0, toneHz = 700.0, sampleRate = 8000.0, amplitude = 8000.0),
            inputRate = 8000, passes = 2
        )
        assertEquals("PARIS|PARIS", twice.text)
    }
}
