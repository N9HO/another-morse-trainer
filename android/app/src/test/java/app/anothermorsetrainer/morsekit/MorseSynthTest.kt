package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Streaming synthesis, pinned against `fixtures/render.json` at the repo root —
 * the same file the iOS `MorseKitCheck` harness reads.
 *
 * The fixture exists to guard a rewrite: [MorseSynth] walks a segment list a
 * sample at a time instead of materialising the whole sound, and the one thing
 * that must not change is what it sounds like. Its expected values are
 * re-implemented from the documented algorithm rather than captured from either
 * port, so they pin the two together as well as pinning them still.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class MorseSynthTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("render.json")
        assertNotNull("fixtures/render.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private val derivation: JSONObject get() = fixture.getJSONObject("derivation")
    private val sampleRate: Double get() = derivation.getDouble("sampleRate")
    private val sampleTolerance: Double get() = fixture.getJSONObject("tolerance").getDouble("sample")
    private val sumTolerance: Double get() = fixture.getJSONObject("tolerance").getDouble("absSumRelative")

    private fun synthFor(c: JSONObject): MorseSynth {
        val text = c.getString("playable")
        val playable = if (c.getString("kind") == "pattern") {
            MorseItem.Playable.Pattern(text)
        } else {
            MorseItem.Playable.Text(text)
        }
        val cw = c.getDouble("characterWpm")
        val ew = c.getDouble("effectiveWpm")
        val timing = if (ew < cw) MorseTiming.farnsworth(cw, ew) else MorseTiming(cw)
        return MorseSynth.forPlayable(
            playable, timing, sampleRate, c.getDouble("frequency"),
            derivation.getDouble("amplitude").toFloat(),
            derivation.getDouble("rampSeconds")
        )
    }

    private fun cases(): List<JSONObject> {
        val arr = fixture.getJSONArray("cases")
        assertTrue("fixture has no cases", arr.length() > 0)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun label(c: JSONObject) =
        "${c.getString("kind")} '${c.getString("playable")}' @ " +
            "${c.getDouble("characterWpm")}/${c.getDouble("effectiveWpm")}"

    @Test
    fun `segment lengths match the shared fixture`() {
        for (c in cases()) {
            val synth = synthFor(c)
            val expected = c.getJSONArray("segmentSamples")
            assertEquals("${label(c)} segment count", expected.length(), synth.segments.size)
            for (i in 0 until expected.length()) {
                val pair = expected.getJSONArray(i)
                assertEquals("${label(c)} segment $i tone", pair.getInt(0), synth.segments[i].toneSamples)
                assertEquals("${label(c)} segment $i gap", pair.getInt(1), synth.segments[i].gapSamples)
            }
            assertEquals("${label(c)} totalSamples", c.getInt("totalSamples"), synth.totalSamples)
        }
    }

    @Test
    fun `synthesised samples match the shared fixture`() {
        for (c in cases()) {
            val synth = synthFor(c)
            val probes = c.getJSONArray("probes")
            assertTrue("${label(c)} has no probes", probes.length() > 0)

            // Walk once, checking probes on the way past and accumulating the
            // whole-signal sum — a probe set can miss a regression between
            // probes, the sum cannot.
            val cursor = MorseSynth.Cursor()
            var probeIndex = 0
            var absSum = 0.0
            for (i in 0 until synth.totalSamples) {
                val v = synth.next(cursor)
                absSum += abs(v.toDouble())
                if (probeIndex < probes.length()) {
                    val probe = probes.getJSONObject(probeIndex)
                    if (probe.getInt("index") == i) {
                        assertEquals(
                            "${label(c)} sample $i",
                            probe.getDouble("value"), v.toDouble(), sampleTolerance
                        )
                        probeIndex += 1
                    }
                }
            }
            assertEquals("${label(c)} probes reached", probes.length(), probeIndex)

            val expectedSum = c.getDouble("absSum")
            assertEquals(
                "${label(c)} |sum|",
                expectedSum, absSum, maxOf(1e-6, expectedSum * sumTolerance)
            )
            assertTrue(
                "${label(c)} cursor did not finish after totalSamples",
                synth.isFinished(cursor)
            )
        }
    }

    @Test
    fun `renderAll agrees with walking the cursor`() {
        // The pileup mixer still materialises, so the two paths must not drift.
        for (c in cases()) {
            val synth = synthFor(c)
            val bulk = synth.renderAll()
            assertEquals("${label(c)} renderAll length", synth.totalSamples, bulk.size)
            val cursor = MorseSynth.Cursor()
            for (i in bulk.indices) {
                val streamed = synth.next(cursor)
                if (bulk[i] != streamed) {
                    throw AssertionError("${label(c)} sample $i: renderAll ${bulk[i]}, streamed $streamed")
                }
            }
        }
    }

    @Test
    fun `rendered duration agrees with the timing model`() {
        for (c in cases()) {
            if (c.getString("kind") != "text") continue
            val text = c.getString("playable")
            if (text.contains(" ")) continue
            val cw = c.getDouble("characterWpm")
            val ew = c.getDouble("effectiveWpm")
            val timing = if (ew < cw) MorseTiming.farnsworth(cw, ew) else MorseTiming(cw)
            val synth = synthFor(c)
            var expected = text.sumOf { timing.duration(it) }
            expected += (text.length - 1) * timing.characterGap
            // Every segment truncates twice on the way to samples — its tone and
            // its gap — so the slack is two samples per segment.
            val slack = 2.0 * synth.segments.size / sampleRate
            assertEquals("'$text' duration", expected, synth.totalSamples / sampleRate, slack)
        }
    }
}
