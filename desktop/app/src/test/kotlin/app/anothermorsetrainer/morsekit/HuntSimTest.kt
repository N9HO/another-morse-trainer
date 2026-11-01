package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hunt simulator's logic (#313), held to the same expectations as the
 * Swift MorseKitCheck "Hunt simulator" section and the Android tree's own copy
 * of this test. Pinned by `fixtures/hunt-model.json` and
 * `fixtures/hunt-sessions.json`, worked out from docs/operating-procedure-design.md
 * by a reference script, not captured from a port.
 */
class HuntSimTest {

    private fun load(name: String): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
        assertNotNull("fixtures/$name is not on the test classpath", stream)
        return JSONObject(stream!!.bufferedReader().readText())
    }

    private val model: JSONObject by lazy { load("hunt-model.json") }
    private val sessions: JSONObject by lazy { load("hunt-sessions.json") }

    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }
    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }
    private fun doubles(a: JSONArray): List<Double> = (0 until a.length()).map { a.getDouble(it) }

    private val eps = 1e-9

    private fun config(o: JSONObject) = HuntConfig(
        pileupSize = o.getInt("pileupSize"), wpmMin = o.getDouble("wpmMin"), wpmMax = o.getDouble("wpmMax"),
        activatorWpm = o.getDouble("activatorWpm"), qsb = o.getBoolean("qsb"), qrn = o.getDouble("qrn"),
        inaudiblePercent = o.getDouble("inaudiblePercent"), bustRate = o.getDouble("bustRate"),
        toneSpread = o.getDouble("toneSpread"), youLevel = o.getDouble("youLevel")
    )

    @Test
    fun constantsAndPresetsAreTheFixtures() {
        val c = model.getJSONObject("constants")
        assertEquals(c.getDouble("noiseFloor"), HuntModel.NOISE_FLOOR, 0.0)
        assertEquals(c.getDouble("maxOdds"), HuntModel.MAX_ODDS, 0.0)
        assertEquals(c.getDouble("maskWeight"), HuntModel.MASK_WEIGHT, 0.0)
        assertEquals(c.getDouble("zeroBeatHz"), HuntModel.ZERO_BEAT_HZ, 0.0)
        assertEquals(OperatingProcedure.ZERO_BEAT_TOLERANCE_HZ, HuntModel.ZERO_BEAT_HZ, 0.0)
        assertEquals(c.getDouble("sepFarStart"), HuntModel.SEP_FAR_START, 0.0)
        assertEquals(c.getDouble("sepSlope"), HuntModel.SEP_SLOPE, 0.0)
        assertEquals(c.getDouble("sepMin"), HuntModel.SEP_MIN, 0.0)
        assertEquals(c.getDouble("qsbFactor"), HuntModel.QSB_FACTOR, 0.0)
        assertEquals(c.getDouble("doubtOdds"), HuntModel.DOUBT_ODDS, 0.0)
        assertEquals(c.getDouble("listenSeconds"), HuntModel.LISTEN_SECONDS, 0.0)
        assertEquals(c.getDouble("asProb"), HuntModel.AS_PROB, 0.0)
        assertEquals(c.getInt("sriAfter"), HuntModel.SRI_AFTER)
        assertEquals(c.getInt("maxExchangeTries"), HuntModel.MAX_EXCHANGE_TRIES)
        assertEquals(c.getInt("maxQueries"), HuntModel.MAX_QUERIES)
        assertEquals(c.getInt("maxRounds"), HuntModel.MAX_ROUNDS)
        assertEquals(c.getDouble("callProb"), HuntModel.CALL_PROB, 0.0)
        assertEquals(c.getDouble("pickJitter"), HuntModel.PICK_JITTER, 0.0)
        assertEquals(c.getInt("pileupMin"), HuntModel.PILEUP_MIN)
        assertEquals(c.getDouble("offsetMinHz"), HuntModel.OFFSET_MIN_HZ, 0.0)
        assertEquals(c.getDouble("offsetMaxHz"), HuntModel.OFFSET_MAX_HZ, 0.0)
        assertEquals(strings(model.getJSONArray("prefixes")), HuntModel.prefixes)
        assertEquals(strings(model.getJSONArray("states")), HuntModel.states)
        assertEquals(
            objects(model.getJSONArray("typeWeights")).map { it.getString("type") to it.getInt("weight") },
            HuntType.entries.map { it.raw to it.weight }
        )
        assertEquals(config(model.getJSONObject("defaults")), HuntConfig())
        val p = model.getJSONObject("presets")
        assertEquals(config(p.getJSONObject("easy")), HuntConfig.EASY)
        assertEquals(config(p.getJSONObject("medium")), HuntConfig.MEDIUM)
        assertEquals(config(p.getJSONObject("hard")), HuntConfig.HARD)
    }

    @Test
    fun randomSourceIsSplitMix64() {
        for (r in objects(model.getJSONArray("rng"))) {
            val seed = r.getLong("seed")
            val g = HuntRandom(seed)
            assertEquals("u64 seed $seed", strings(r.getJSONArray("u64")), List(3) { java.lang.Long.toUnsignedString(g.next64()) })
            val d = HuntRandom(seed)
            doubles(r.getJSONArray("doubles")).forEach { assertEquals(it, d.nextDouble(), 0.0) }
            val n = HuntRandom(seed)
            assertEquals((0 until 5).map { r.getJSONArray("ints0to9").getInt(it) }, List(5) { n.nextInt(0, 9) })
            val rg = HuntRandom(seed)
            doubles(r.getJSONArray("ranges")).forEach { assertEquals(it, rg.range(-250.0, 250.0), 0.0) }
        }
    }

    @Test
    fun charTimingAndSeparation() {
        for (s in objects(model.getJSONArray("spans"))) {
            val spans = HuntModel.charSpans(s.getString("text"), s.getDouble("wpm"), s.getDouble("start"))
            val label = s.getString("text")
            assertEquals(label, strings(s.getJSONArray("chars")).joinToString(""), spans.map { it.ch }.joinToString(""))
            val starts = doubles(s.getJSONArray("starts"))
            val ends = doubles(s.getJSONArray("ends"))
            spans.forEachIndexed { i, c ->
                assertEquals("$label start $i", starts[i], c.start, eps)
                assertEquals("$label end $i", ends[i], c.end, eps)
            }
            assertEquals(label, s.getDouble("end"), HuntModel.txEnd(s.getString("text"), s.getDouble("wpm"), s.getDouble("start")), eps)
        }
        for (s in objects(model.getJSONArray("sep"))) {
            assertEquals("sep ${s.getDouble("diffHz")}", s.getDouble("sep"), HuntModel.sep(s.getDouble("diffHz")), eps)
        }
    }

    @Test
    fun copyOddsAndDecode() {
        for ((n, o) in objects(model.getJSONArray("odds")).withIndex()) {
            val others = objects(o.getJSONArray("others")).map {
                HuntModel.Other(it.getDouble("overlap"), it.getDouble("level"), it.getDouble("diffHz"))
            }
            assertEquals("odds #$n", o.getDouble("odds"), HuntModel.oddsFor(o.getDouble("level"), o.getDouble("qrn"), others), eps)
        }
        for (d in objects(model.getJSONArray("decode"))) {
            val txs = objects(d.getJSONArray("txs")).mapIndexed { i, t ->
                HuntTx(i, t.getString("text"), t.getDouble("wpm"), t.getDouble("start"), t.getDouble("level"), t.getDouble("pitch"))
            }
            val rng = HuntRandom(d.getLong("seed"))
            val want = strings(d.getJSONArray("decoded"))
            val mins = doubles(d.getJSONArray("minOdds"))
            for (i in txs.indices) {
                val got = HuntModel.decode(i, txs, d.getDouble("qrn"), d.getDouble("bust"), rng)
                assertEquals("decode seed ${d.getLong("seed")} #$i", want[i], got.text)
                assertEquals("minOdds #$i", mins[i], got.minOdds, eps)
            }
        }
    }

    @Test
    fun mergeAndCandidates() {
        for (m in objects(model.getJSONArray("merge"))) {
            val copies = strings(m.getJSONArray("copies"))
            assertEquals("merge $copies", m.getString("merged"), HuntModel.mergeCopies(copies))
        }
        for (c in objects(model.getJSONArray("candidates"))) {
            val d = c.getString("decoded")
            val merged = HuntModel.candidateOf(d)
            assertEquals("candidate '$d'", c.getString("merged"), merged)
            assertEquals("isCandidate '$d'", c.getBoolean("isCandidate"), HuntModel.isCandidate(merged))
            assertEquals("run '$d'", c.getString("run"), HuntModel.longestRun(merged))
        }
    }

    @Test
    fun queryRules() {
        for (q in objects(model.getJSONArray("queries"))) {
            val query = q.getString("query")
            val call = q.getString("call")
            val label = "'$query' vs $call"
            assertEquals("answers $label", q.getBoolean("answers"), HuntModel.answers(query, call))
            assertEquals("partial $label", q.getBoolean("partial"), OperatingProcedure.partialMatches(query, call))
            assertEquals("nearMiss $label", q.getBoolean("nearMiss"), HuntModel.nearMissQuery(query, call))
            assertEquals("loose $label", q.getBoolean("loose"), HuntModel.looseMatch(query, call))
        }
    }

    @Test
    fun exchangeAndSignoffForms() {
        for (e in objects(model.getJSONArray("exchangeForms"))) {
            val t = e.getString("text")
            val st = e.getString("state")
            assertEquals("form '$t'", e.getString("form"), HuntModel.exchangeForm(t, st, e.getString("call"), e.getString("activator")).raw)
            assertEquals("accepts '$t'", e.getBoolean("accepts"), HuntModel.exchangeAccepts(t, st))
            assertEquals("signoff '$t'", e.getBoolean("signoff"), HuntModel.signoffForm(t, e.getString("call")))
        }
    }

    @Test
    fun huntersAreTheFixtures() {
        for (h in objects(model.getJSONArray("hunters"))) {
            val cfg = config(h.getJSONObject("config"))
            val rng = HuntRandom(h.getLong("seed"))
            val taken = mutableListOf(h.getString("myCall"))
            for (w in objects(h.getJSONArray("hunters"))) {
                val got = HuntModel.makeHunter(cfg, rng, w.getInt("id"), taken)
                taken += got.call
                val label = "seed ${h.getLong("seed")} #${w.getInt("id")}"
                assertEquals(label, w.getString("call"), got.call)
                assertEquals(label, w.getString("type"), got.type.raw)
                assertEquals(label, w.getString("state"), got.state)
                assertEquals(label, w.getDouble("wpm"), got.wpm, eps)
                assertEquals(label, w.getDouble("offset"), got.offset, eps)
                assertEquals(label, w.getDouble("levelAct"), got.levelAct, eps)
                assertEquals(label, w.getDouble("levelYou"), got.levelYou, eps)
                assertEquals(label, w.getBoolean("qsb"), got.qsb)
                assertEquals(label, w.getInt("repeats"), got.repeats)
            }
        }
    }

    @Test
    fun graderIsTheFixtures() {
        for (g in objects(model.getJSONArray("grades"))) {
            val c = g.getJSONObject("ctx")
            val ctx = HuntGradeContext(
                kind = HuntKind.fromRaw(c.getString("kind"))!!, query = c.getString("query"),
                queryForYou = c.getBoolean("queryForYou"), pickedIsYou = c.getBoolean("pickedIsYou"),
                pileup = c.getBoolean("pileup"), myCall = c.getString("myCall"), myState = c.getString("myState"),
                actCall = c.getString("actCall"), pickedCall = if (c.isNull("pickedCall")) null else c.getString("pickedCall"),
                activatorText = c.getString("activatorText")
            )
            val y = g.optJSONObject("you")
            val you = y?.let { HuntGradeSend(it.getString("text"), it.getDouble("delay"), it.getDouble("offsetHz"), it.getBoolean("late")) }
            val want = objects(g.getJSONArray("expected")).map { Triple(it.getString("code"), it.getBoolean("good"), it.getString("lesson")) }
            val got = HuntModel.grade(ctx, you).map { Triple(it.code, it.good, it.lesson.raw) }
            assertEquals(g.getString("name"), want, got)
        }
    }

    // Policies: the fixture names them; this is their (small) logic.

    private fun policy(name: String, s: HuntSession): HuntYouSend? {
        val t = s.turn
        val me = s.myCall
        fun y(text: String, delay: Double, off: Double) = HuntYouSend(text, 20.0, delay, off)
        return when (name) {
            "silent" -> null
            "good" -> when (t.kind) {
                HuntKind.CALLING -> y(me, 0.6, 60.0)
                HuntKind.QUERY -> if (HuntModel.answers(t.query, me)) y(me, 0.5, 60.0) else null
                HuntKind.EXCHANGE -> if (s.pickedCall == me) y("5NN ${s.myState} 73", 0.4, 0.0) else null
                HuntKind.CLOSE -> y("73 E E", 0.3, 0.0)
                HuntKind.AS -> null
            }
            "pushy" -> when (t.kind) {
                HuntKind.CALLING -> y("$me $me", 0.1, 0.0)
                HuntKind.QUERY -> y(me, 0.1, 0.0)
                HuntKind.EXCHANGE ->
                    if (s.pickedCall == me) y("TNX FER CALL UR 5NN ${s.myState}", 0.2, 0.0) else y(me, 0.1, 150.0)
                HuntKind.AS -> y(me, 0.1, 0.0)
                HuntKind.CLOSE -> null
            }
            else -> throw AssertionError("no policy $name")
        }
    }

    private fun heard(a: JSONArray) = objects(a).map { Triple(it.get("who").toString(), it.getString("sent"), it.getString("decoded")) }

    @Test
    fun scriptedSessionsAreTheFixtures() {
        for (f in objects(sessions.getJSONArray("sessions"))) {
            val name = f.getString("name")
            val s = HuntSession(config(f.getJSONObject("config")), f.getLong("seed"), f.getString("myCall"), f.getString("myState"))
            val turns = objects(f.getJSONArray("turns"))
            val records = objects(f.getJSONArray("records"))
            var i = 0
            while (!s.ended) {
                assertTrue("$name: more windows than the fixture", i < turns.size)
                val want = turns[i].getJSONObject("turn")
                val t = s.turn
                assertEquals("$name #$i kind", want.getString("kind"), t.kind.raw)
                assertEquals("$name #$i text", want.getString("text"), t.text)
                assertEquals("$name #$i query", want.getString("query"), t.query)
                assertEquals("$name #$i round", want.getInt("round"), t.round)
                assertEquals("$name #$i wpm", want.getDouble("wpm"), t.wpm, eps)
                val wv = objects(want.getJSONArray("voices"))
                assertEquals("$name #$i voices", wv.size, t.voices.size)
                wv.forEachIndexed { k, w ->
                    val v = t.voices[k]
                    val label = "$name #$i voice $k"
                    assertEquals(label, w.getInt("id"), v.id)
                    assertEquals(label, w.getString("call"), v.call)
                    assertEquals(label, w.getString("type"), v.type.raw)
                    assertEquals(label, w.getString("text"), v.text)
                    assertEquals(label, w.getDouble("wpm"), v.wpm, eps)
                    assertEquals(label, w.getDouble("offsetHz"), v.offsetHz, eps)
                    assertEquals(label, w.getDouble("levelAct"), v.levelAct, eps)
                    assertEquals(label, w.getDouble("levelYou"), v.levelYou, eps)
                    assertEquals(label, w.getBoolean("qsb"), v.qsb)
                    assertEquals(label, w.getDouble("delay"), v.delay, eps)
                }
                val you = policy(f.getString("policy"), s)
                val wantYou = turns[i].optJSONObject("you")
                assertEquals("$name #$i you", wantYou?.getString("text"), you?.text)
                s.respond(you)
                val r = records[i]
                val rec = s.records[i]
                assertEquals("$name #$i activatorHeard", heard(r.getJSONArray("activatorHeard")), rec.activatorHeard.map { Triple(it.who, it.sent, it.decoded) })
                assertEquals("$name #$i youHeard", heard(r.getJSONArray("youHeard")), rec.youHeard.map { Triple(it.who, it.sent, it.decoded) })
                assertEquals(
                    "$name #$i grade",
                    objects(r.getJSONArray("grade")).map { Triple(it.getString("code"), it.getBoolean("good"), it.getString("lesson")) },
                    rec.grade.map { Triple(it.code, it.good, it.lesson.raw) }
                )
                i++
            }
            assertEquals("$name windows", turns.size, i)
            assertEquals("$name rounds", f.getInt("rounds"), s.round)
            assertEquals("$name outcome", f.getString("outcome"), s.outcome?.raw)
        }
    }

    @Test
    fun anEndedSessionIgnoresFurtherSends() {
        val s = HuntSession(HuntConfig.EASY, 22L, "W1AW", "WI")
        s.quit()
        val n = s.records.size
        s.respond(HuntYouSend("W1AW", 20.0, 0.5, 60.0))
        assertEquals(n, s.records.size)
        assertEquals(HuntOutcome.QUIT, s.outcome)
        assertTrue(s.ended)
        assertFalse(s.records.isNotEmpty())
    }
}
