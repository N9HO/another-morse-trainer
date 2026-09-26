package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Sending Analyzer's core (#241, #234, #235) — [SendingAnalysis],
 * [SendingRecord], [KeyingRecorder] and [ToneKeyingDetector] — pinned against
 * `fixtures/sending-analysis.json`, the same file the iOS `MorseKitCheck`
 * harness reads. The inputs are key schedules built from the 1:3:7 standard;
 * every expected value was worked by hand from the fixture's `derivation`,
 * not captured from either port.
 */
class SendingAnalysisTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("sending-analysis.json")
        assertNotNull("fixtures/sending-analysis.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun doubles(a: JSONArray) = (0 until a.length()).map { a.getDouble(it) }
    private fun ints(a: JSONArray) = (0 until a.length()).map { a.getInt(it) }
    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    private fun analyse(c: JSONObject): SendingAnalysis {
        val m = c.getJSONArray("marks")
        val marks = (0 until m.length()).map {
            val pair = m.getJSONArray(it)
            KeyMark(pair.getDouble(0), pair.getDouble(1))
        }
        return SendingAnalysis(
            marks, c.getString("target"), SendingKeyType.fromId(c.getString("keyType")),
            c.getDouble("characterWpm"), c.getDouble("effectiveWpm")
        )
    }

    private fun analyses(): Map<String, SendingAnalysis> {
        val cases = fixture.getJSONArray("cases")
        return (0 until cases.length()).associate {
            val c = cases.getJSONObject(it)
            c.getString("name") to analyse(c)
        }
    }

    @Test
    fun histogramEdgesAreTheFixtures() {
        assertEquals(doubles(fixture.getJSONArray("charGapBinEdges")), SendingAnalysis.CHAR_GAP_BIN_EDGES)
        assertEquals(doubles(fixture.getJSONArray("wordGapBinEdges")), SendingAnalysis.WORD_GAP_BIN_EDGES)
    }

    @Test
    fun analysisMatchesTheFixture() {
        val tol = fixture.getDouble("tolerance")
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val a = analyse(c)
            val e = c.getJSONObject("expected")
            fun num(key: String, got: Double?) {
                if (!e.has(key)) return
                assertNotNull("$name: $key", got)
                assertEquals("$name: $key", e.getDouble(key), got!!, tol)
            }
            fun stat(key: String, got: SendingAnalysis.Stat) {
                if (!e.has(key)) return
                val s = e.getJSONObject(key)
                assertEquals("$name: $key.count", s.getInt("count"), got.count)
                if (s.has("mean")) assertEquals("$name: $key.mean", s.getDouble("mean"), got.mean, tol)
                if (s.has("sd")) assertEquals("$name: $key.sd", s.getDouble("sd"), got.sd, tol)
            }
            if (e.has("decoded")) assertEquals("$name: decoded", e.getString("decoded"), a.decodedText)
            if (e.has("correct")) assertEquals("$name: correct", e.getInt("correct"), a.correctCharacterCount)
            if (e.has("targetCharacters")) assertEquals("$name: targetCharacters", e.getInt("targetCharacters"), a.targetCharacterCount)
            if (e.has("marks")) assertEquals("$name: marks", e.getInt("marks"), a.marks.size)
            num("accuracy", a.accuracy)
            num("unitMs", a.unitMs)
            num("characterWpm", a.characterWpm)
            num("effectiveWpm", a.effectiveWpm)
            num("dahDitRatio", a.dahDitRatio)
            num("farnsworthFactor", a.farnsworthFactor)
            stat("dits", a.dits)
            stat("dahs", a.dahs)
            stat("elementGaps", a.elementGaps)
            stat("characterGaps", a.characterGaps)
            stat("wordGaps", a.wordGaps)
            if (e.has("characterGapHistogram")) {
                assertEquals("$name: character-gap histogram", ints(e.getJSONArray("characterGapHistogram")), a.characterGapHistogram)
            }
            if (e.has("wordGapHistogram")) {
                assertEquals("$name: word-gap histogram", ints(e.getJSONArray("wordGapHistogram")), a.wordGapHistogram)
            }
            if (e.has("alignment")) {
                assertEquals("$name: alignment", strings(e.getJSONArray("alignment")), a.alignment.map { it.code })
            }
            if (e.has("missingWordBreaks")) assertEquals("$name: missingWordBreaks", e.getInt("missingWordBreaks"), a.missingWordBreaks)
            if (e.has("extraWordBreaks")) assertEquals("$name: extraWordBreaks", e.getInt("extraWordBreaks"), a.extraWordBreaks)
            assertEquals("$name: feedback", strings(e.getJSONArray("feedback")), a.feedback.map { it.code })
        }
    }

    @Test
    fun everyFeedbackCodeHasWording() {
        val a = analyses().values.first()
        for (code in SendingFeedback.entries) assertTrue(code.code, a.message(code).isNotEmpty())
    }

    @Test
    fun sendingRecordMatchesTheFixture() {
        val r = fixture.getJSONObject("record")
        val all = analyses()
        val record = SendingRecord()
        for (name in strings(r.getJSONArray("cases"))) record.record(all.getValue(name), 0L)

        fun tallies(o: JSONObject) = o.keys().asSequence().associateWith { ints(o.getJSONArray(it)) }
        assertEquals(tallies(r.getJSONObject("characters")),
            record.characters.mapValues { listOf(it.value.attempts, it.value.misses) })
        assertEquals(tallies(r.getJSONObject("pairs")),
            record.pairs.mapValues { listOf(it.value.attempts, it.value.misses) })
        val mix = r.getJSONArray("mixups")
        assertEquals(
            (0 until mix.length()).map { mix.getJSONObject(it).let { m -> "${m.getString("target")}>${m.getString("sent")}×${m.getInt("count")}" } },
            record.mixups.entries().map { "${it.target}>${it.chosen}×${it.count}" }
        )
        assertEquals(r.getInt("attempts"), record.attempts.size)
        assertEquals(strings(r.getJSONArray("problemCharacters")), record.problemCharacters(minAttempts = 1).map { it.first })
        assertEquals(strings(r.getJSONArray("problemPairs")), record.problemPairs(minAttempts = 1).map { it.first })
    }

    @Test
    fun keyingRecorderPairsEdgesAndIgnoresRepeats() {
        val rec = KeyingRecorder()
        rec.keyDown(10.0); rec.keyDown(12.0); rec.keyUp(70.0); rec.keyUp(80.0)
        rec.keyDown(130.0); rec.keyUp(310.0)
        assertEquals(listOf(KeyMark(10.0, 70.0), KeyMark(130.0, 310.0)), rec.marks)
    }

    /** The test signal the fixture's `toneDetector.derivation.signal` describes. */
    private fun signal(tc: JSONObject): FloatArray {
        val rate = tc.getDouble("sampleRate")
        val total = (tc.getDouble("durationMs") * rate / 1000).toInt()
        val m = tc.getJSONArray("marks")
        val marks = (0 until m.length()).map { m.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } }
        val impulses = doubles(tc.getJSONArray("impulses")).map { (it * rate / 1000).roundToInt() }.toSet()
        val pitch = tc.getDouble("pitchHz")
        val amp = tc.getDouble("amplitude")
        val noise = tc.getDouble("noiseAmplitude")
        val impulseAmp = tc.getDouble("impulseAmplitude")
        var x = 1L
        return FloatArray(total) { n ->
            val tMs = n * 1000.0 / rate
            var v = 0.0
            if (marks.any { tMs >= it.first && tMs < it.second }) v = amp * sin(2 * PI * pitch * n / rate)
            x = (1103515245L * x + 12345L) % 2147483648L
            v += (x.toDouble() / 2147483648.0 * 2 - 1) * noise
            if (n in impulses) v += impulseAmp
            v.toFloat()
        }
    }

    @Test
    fun toneDetectorFindsEveryEdgeAndLocksThePitch() {
        val cases = fixture.getJSONObject("toneDetector").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val tc = cases.getJSONObject(i)
            val name = tc.getString("name")
            val samples = signal(tc)
            val det = ToneKeyingDetector(tc.getDouble("sampleRate"))
            val edges = mutableListOf<ToneKeyingDetector.Edge>()
            // Fed in uneven chunks, as a capture callback would.
            val chunks = intArrayOf(1000, 333, 2048, 77)
            var at = 0
            var c = 0
            while (at < samples.size) {
                val len = minOf(chunks[c % chunks.size], samples.size - at)
                edges += det.process(samples.copyOfRange(at, at + len))
                at += len; c++
            }
            val m = tc.getJSONArray("marks")
            val want = (0 until m.length()).flatMap {
                val p = m.getJSONArray(it)
                listOf(true to p.getDouble(0), false to p.getDouble(1))
            }
            val tol = tc.getDouble("toleranceMs")
            val shown = edges.map { (if (it.isDown) "↓" else "↑") + it.timeMs }
            if (edges.size != want.size) fail("$name: edges $shown")
            for ((g, w) in edges.zip(want)) {
                if (g.isDown != w.first || abs(g.timeMs - w.second) > tol) fail("$name: edges $shown")
            }
            val pitch = det.pitchHz
            assertTrue("$name: pitch locked", det.isLocked)
            assertNotNull("$name: pitch", pitch)
            assertEquals("$name: pitch", tc.getDouble("expectedPitchHz"), pitch!!, tc.getDouble("pitchToleranceHz"))
        }
    }

    @Test
    fun aManualPitchIsLockedAndHearsItsToneAtOnce() {
        val det = ToneKeyingDetector(8000.0, pitchHz = 700.0)
        val samples = FloatArray(2400) { n ->
            if (n in 800 until 1280) (0.3 * sin(2 * PI * 700 * n / 8000)).toFloat() else 0f
        }
        val edges = det.process(samples)
        assertTrue(det.isLocked)
        assertEquals(2, edges.size)
        assertEquals(100.0, edges[0].timeMs, 8.0)
        assertEquals(160.0, edges[1].timeMs, 8.0)
    }
}
