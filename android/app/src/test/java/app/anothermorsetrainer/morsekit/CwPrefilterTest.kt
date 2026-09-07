package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The CW pre-filter (noise blanker), pinned against `fixtures/cw-prefilter.json`
 * at the repo root — the same file the iOS `MorseKitCheck` harness reads
 * against the Swift `CWPrefilter`. The fixture's `derivation` block is the
 * spec; its values were derived from those equations, not captured from
 * either port, so both ports are held to one arithmetic.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class CwPrefilterTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("cw-prefilter.json")
        assertNotNull("fixtures/cw-prefilter.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private val coefficientTolerance: Double get() = fixture.getJSONObject("tolerance").getDouble("coefficient")
    private val sampleTolerance: Double get() = fixture.getJSONObject("tolerance").getDouble("sample")

    private fun objects(key: String): List<JSONObject> {
        val arr = fixture.getJSONArray(key)
        assertTrue("fixture has no $key", arr.length() > 0)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** The bench's seedable RNG (xorshift64*), as in decoder.json's noise term. */
    private class SeededRng(seed: ULong) {
        var state: ULong = if (seed == 0uL) 0x9E3779B97F4A7C15uL else seed
        fun next(): ULong {
            state = state xor (state shr 12)
            state = state xor (state shl 25)
            state = state xor (state shr 27)
            return state * 0x2545F4914F6CDD1DuL
        }
    }

    /** Render a vector from its description (the fixture's `derivation.vectors`). */
    private fun render(v: JSONObject): FloatArray {
        val n = v.getInt("length")
        val fs = v.getDouble("sampleRate")
        val pitch = v.getDouble("pitchHz")
        val signal = v.getJSONObject("signal")
        val x = FloatArray(n)
        when (signal.getString("type")) {
            "tone" -> {
                val amp = signal.getDouble("amplitude")
                for (i in 0 until n) x[i] = (amp * sin(2.0 * Math.PI * pitch * i / fs)).toFloat()
            }
            "noise" -> {
                val amp = signal.getDouble("amplitude")
                val rng = SeededRng(signal.getLong("noiseSeed").toULong())
                for (i in 0 until n) {
                    x[i] = (((rng.next() shr 40).toLong().toDouble() / 8_388_608.0 - 1.0) * amp).toFloat()
                }
            }
            else -> {}   // silence
        }
        v.optJSONObject("burst")?.let { b ->
            val start = b.getInt("start")
            val length = b.getInt("length")
            val amp = b.getDouble("amplitude")
            for (k in 0 until length) {
                val sign = if (k % 2 == 0) 1.0 else -1.0
                x[start + k] = x[start + k] + (amp * (1.0 - k.toDouble() / length) * sign).toFloat()
            }
        }
        return x
    }

    private class Run(val input: FloatArray, val output: FloatArray, val blanked: BooleanArray)

    private fun run(v: JSONObject): Run {
        val x = render(v)
        val filter = CwPrefilter(v.getDouble("sampleRate").toFloat(), v.getDouble("pitchHz").toFloat())
        val out = FloatArray(x.size)
        val blanked = BooleanArray(x.size)
        for (i in x.indices) {
            out[i] = filter.process(x[i])
            blanked[i] = filter.isBlanking
        }
        return Run(x, out, blanked)
    }

    @Test
    fun `band-pass coefficients match the fixture`() {
        for (c in objects("coefficients")) {
            val k = CwPrefilter.bandPassCoefficients(
                c.getDouble("sampleRate").toFloat(), c.getDouble("pitchHz").toFloat(), c.getDouble("q").toFloat()
            )
            val label = "${c.getInt("pitchHz")} Hz, Q ${c.getDouble("q")}, ${c.getInt("sampleRate")} Hz"
            assertEquals("$label b0", c.getDouble("b0"), k[0].toDouble(), coefficientTolerance)
            assertEquals("$label b2", c.getDouble("b2"), k[1].toDouble(), coefficientTolerance)
            assertEquals("$label a1", c.getDouble("a1"), k[2].toDouble(), coefficientTolerance)
            assertEquals("$label a2", c.getDouble("a2"), k[3].toDouble(), coefficientTolerance)
        }
    }

    @Test
    fun `vectors behave as the fixture says`() {
        var checks = 0
        for (v in objects("vectors")) {
            val name = v.getString("name")
            val r = run(v)
            val e = v.getJSONObject("expect")
            e.optJSONArray("blankedAll")?.let { range ->
                checks++
                for (i in range.getInt(0)..range.getInt(1)) {
                    assertTrue("$name: sample $i should be blanked", r.blanked[i])
                }
            }
            if (e.has("clearFrom")) {
                checks++
                for (i in e.getInt("clearFrom") until r.blanked.size) {
                    assertTrue("$name: sample $i should be clear", !r.blanked[i])
                }
            }
            if (e.has("onsetBlankBefore")) {
                checks++
                val before = e.getInt("onsetBlankBefore")
                val hits = r.blanked.indices.filter { r.blanked[it] }
                assertTrue("$name: expected a short onset blank", hits.isNotEmpty())
                assertTrue("$name: onset blank runs past $before (last at ${hits.last()})", hits.all { it < before })
            }
            e.optJSONArray("exact")?.let { idx ->
                checks++
                for (j in 0 until idx.length()) {
                    val i = idx.getInt(j)
                    assertEquals("$name: sample $i should pass through untouched", r.input[i], r.output[i])
                }
            }
            e.optJSONArray("values")?.let { values ->
                checks++
                for (j in 0 until values.length()) {
                    val value = values.getJSONObject(j)
                    val i = value.getInt("n")
                    assertEquals("$name: out[$i]", value.getDouble("out"), r.output[i].toDouble(), sampleTolerance)
                }
            }
            e.optJSONObject("maxAbs")?.let { span ->
                checks++
                for (i in span.getInt("from") until span.getInt("to")) {
                    assertTrue("$name: |out[$i]| = ${abs(r.output[i])} exceeds ${span.getDouble("max")}",
                        abs(r.output[i].toDouble()) <= span.getDouble("max"))
                }
            }
            e.optJSONObject("blankedFraction")?.let { span ->
                checks++
                val from = span.getInt("from")
                val to = span.getInt("to")
                val share = (from until to).count { r.blanked[it] }.toDouble() / (to - from)
                assertTrue("$name: blanked $share of samples over $from..$to, ceiling ${span.getDouble("max")}",
                    share <= span.getDouble("max"))
            }
            if (e.optBoolean("blankedNone", false)) {
                checks++
                assertTrue("$name: should never blank", r.blanked.none { it })
            }
        }
        assertTrue("fixture vectors carry no expectations", checks > 0)
    }
}
