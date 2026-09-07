package app.anothermorsetrainer.morsekit.cw

import app.anothermorsetrainer.morsekit.CwPrefilter
import app.anothermorsetrainer.morsekit.MorseCode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The ported CW decoder core, pinned against `fixtures/decoder.json` at the
 * repo root — the same file the iOS `MorseKitCheck` harness reads against the
 * vendored C core.
 *
 * The scenarios here used to be a line-for-line transcription of the Swift
 * harness, the last instance of the copied-test-code habit that `fixtures/`
 * was built to replace. The fixture now carries the scenarios and their
 * expectations as data; the bench render and the decode loop below stay
 * per-tree, in this tree's idiom, so no behaviour is shared. The fixture also
 * pins the render itself (sample count and |sum|), so the two benches are
 * proven to feed the two decoders the same audio before either decodes it.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class CwDecoderTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("decoder.json")
        assertNotNull("fixtures/decoder.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private val sumTolerance: Double get() = fixture.getJSONObject("tolerance").getDouble("absSumRelative")

    private fun cases(): List<JSONObject> {
        val arr = fixture.getJSONArray("cases")
        assertTrue("fixture has no cases", arr.length() > 0)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

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

    /** QRN static crashes (the fixture's `derivation.qrn`). */
    private class Crashes(
        val rate: Double, val amplitude: Double, val minMs: Double, val maxMs: Double, val seed: ULong
    )

    /**
     * Render hard-keyed CW as 16-bit PCM: a calibration lead-in of silence,
     * the message at ITU timing, and enough tail to flush the last character.
     * Noise is seeded and uniform, so every run hears identical samples.
     * The fixture's `derivation.render` block is the spec this implements;
     * `derivation.qrn` adds the static crashes.
     */
    private fun renderCW(
        message: String,
        wpm: Double,
        toneHz: Double,
        sampleRate: Double,
        amplitude: Double,
        noiseAmplitude: Double = 0.0,
        noiseSeed: ULong = 0uL,
        crashes: Crashes? = null
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

        val clean = ArrayList<Double>()
        repeat((sampleRate * 0.7).toInt()) { clean.add(0.0) }   // noise-floor calibration
        val omega = 2.0 * Math.PI * toneHz / sampleRate
        for ((tone, gap) in segments) {
            for (i in 0 until (tone * sampleRate).toInt()) clean.add(amplitude * sin(omega * i))
            repeat((gap * sampleRate).toInt()) { clean.add(0.0) }
        }
        repeat((sampleRate * 0.5).toInt()) { clean.add(0.0) }   // let the tail flush

        // Static crashes: decaying broadband bursts at seeded, jittered
        // intervals, from their own generator so the hiss stream is untouched
        // by their presence.
        if (crashes != null && crashes.rate > 0) {
            val crng = SeededRng(crashes.seed)
            fun unit(): Double = (crng.next() shr 40).toLong().toDouble() / 16_777_216.0
            var t = 0.0
            while (true) {
                t += (0.5 + unit()) / crashes.rate
                val start = (t * sampleRate).toInt()
                if (start >= clean.size) break
                val ms = crashes.minMs + unit() * (crashes.maxMs - crashes.minMs)
                val len = (ms * sampleRate / 1000).toInt()
                for (k in 0 until len) {
                    val signed = (crng.next() shr 40).toLong().toDouble() / 8_388_608.0 - 1.0
                    if (start + k < clean.size) {
                        clean[start + k] = clean[start + k] + crashes.amplitude * (1 - k.toDouble() / len) * signed
                    }
                }
            }
        }
        val samples = ShortArray(clean.size)
        for (i in clean.indices) {
            samples[i] = max(-32767.0, min(32767.0, clean[i] + noise())).toInt().toShort()
        }
        return samples
    }

    private fun render(c: JSONObject): ShortArray = renderCW(
        c.getString("message"),
        wpm = c.getDouble("wpm"),
        toneHz = c.getDouble("toneHz"),
        sampleRate = c.getDouble("sampleRate"),
        amplitude = c.getDouble("amplitude"),
        noiseAmplitude = c.getDouble("noiseAmplitude"),
        noiseSeed = c.getLong("noiseSeed").toULong(),
        crashes = if (c.optDouble("crashRate", 0.0) > 0) Crashes(
            c.getDouble("crashRate"), c.getDouble("crashAmplitude"),
            c.getDouble("crashMinMs"), c.getDouble("crashMaxMs"), c.getLong("crashSeed").toULong()
        ) else null
    )

    private class Decoded(val text: String, val wpm: Float, val toneHz: Float)

    /**
     * Run PCM through the ported core; passes=2 replays the same audio after
     * a reset() to prove the decoder rearms cleanly.
     */
    private fun decodeCW(samples: ShortArray, inputRate: Int, passes: Int = 1, prefilter: Boolean = false): Decoded {
        val sink = StringBuilder()
        val cfg = CwDecoder.Config()
        cfg.inputRateHz = inputRate
        cfg.targetRateHz = 8000
        cfg.onSymbol = { text, _ -> sink.append(text) }
        val decoder = CwDecoder.create(cfg) ?: return Decoded("<create failed>", 0f, 0f)
        val texts = ArrayList<String>()
        val filter = CwPrefilter(inputRate.toFloat())
        val chunk = ShortArray(2048)
        for (pass in 0 until passes) {
            if (pass > 0) {
                decoder.reset()
                filter.reset()
                sink.setLength(0)
            }
            if (prefilter) {
                // The apps' capture paths, in miniature (the fixture's
                // `derivation.prefilter`): retune to the core's lock before
                // every buffer, filter, then feed.
                var offset = 0
                while (offset < samples.size) {
                    val n = min(2048, samples.size - offset)
                    filter.retune(decoder.toneHz)
                    for (i in 0 until n) {
                        val y = filter.process(samples[offset + i] / 32768f)
                        chunk[i] = max(-32767f, min(32767f, y * 32768f)).toInt().toShort()
                    }
                    decoder.feed(chunk, n)
                    offset += n
                }
            } else {
                decoder.feed(samples)
            }
            texts.add(sink.toString().trim())
        }
        return Decoded(texts.joinToString("|"), decoder.wpm, decoder.toneHz)
    }

    private fun decode(c: JSONObject, prefilter: Boolean = c.optBoolean("prefilter", false)): Decoded =
        decodeCW(render(c), inputRate = c.getInt("inputRate"), passes = c.getInt("passes"), prefilter = prefilter)

    private fun label(c: JSONObject) = c.getString("name")

    @Test
    fun `bench render matches the shared fixture`() {
        for (c in cases()) {
            val pcm = render(c)
            assertEquals("${label(c)} sample count", c.getInt("renderedSamples"), pcm.size)
            val absSum = pcm.sumOf { abs(it.toDouble()) }
            val expected = c.getDouble("absSum")
            assertEquals("${label(c)} |sum|", expected, absSum, max(1e-6, expected * sumTolerance))
        }
    }

    @Test
    fun `decoded text matches the shared fixture for every case`() {
        for (c in cases()) {
            assertEquals(label(c), c.getString("expectedText"), decode(c).text)
        }
    }

    @Test
    fun `bare core fails the QRN cases that say so`() {
        // A QRN case earns its place by breaking the core without the
        // pre-filter; if it stops doing so it has drifted easy.
        var pinned = 0
        for (c in cases()) {
            if (!c.optBoolean("bareCoreFails", false)) continue
            pinned++
            val bare = decode(c, prefilter = false).text
            assertTrue("${label(c)}: the bare core should not copy this, but decoded '$bare'",
                bare != c.getString("expectedText"))
        }
        assertTrue("fixture no longer carries QRN cases the bare core fails", pinned > 0)
    }

    @Test
    fun `speed estimate lands in the fixture's range`() {
        var pinned = 0
        for (c in cases()) {
            if (!c.has("wpmRange")) continue
            pinned++
            val range = c.getJSONArray("wpmRange")
            val lo = range.getDouble(0)
            val hi = range.getDouble(1)
            val wpm = decode(c).wpm.toDouble()
            assertTrue("${label(c)}: speed estimate $wpm should land in $lo-$hi WPM", wpm > lo && wpm < hi)
        }
        // A fixture edit that dropped the expectation would otherwise pass quietly.
        assertTrue("fixture no longer pins a speed estimate", pinned > 0)
    }

    @Test
    fun `locked tone sits by the fixture's real pitch`() {
        var pinned = 0
        for (c in cases()) {
            if (!c.has("expectedToneHz")) continue
            pinned++
            val expected = c.getDouble("expectedToneHz")
            val tolerance = c.getDouble("toneToleranceHz")
            val tone = decode(c).toneHz.toDouble()
            assertTrue(
                "${label(c)}: locked tone $tone should sit within $tolerance Hz of $expected Hz",
                abs(tone - expected) < tolerance
            )
        }
        assertTrue("fixture no longer pins a pitch lock", pinned > 0)
    }
}
