package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CW Operating Procedure (#294, #295), held to the same expectations as the
 * Swift MorseKitCheck "CW Operating Procedure" section and the desktop tree's
 * own copy of this test. Everything is pinned by
 * `fixtures/operating-procedure.json`, worked out by hand from
 * docs/operating-procedure-design.md.
 */
class OperatingProcedureTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("operating-procedure.json")
        assertNotNull("fixtures/operating-procedure.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun objects(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }

    private fun doubles(a: JSONArray): List<Double> = (0 until a.length()).map { a.getDouble(it) }

    private fun choice(o: JSONObject): OpChoice = when {
        o.has("send") -> OpChoice.Send(o.getString("send"))
        o.optBoolean("silent", false) -> OpChoice.Silent
        o.has("option") -> OpChoice.Option(o.getString("option"))
        else -> throw AssertionError("unreadable choice $o")
    }

    private fun lesson(raw: String): OpLesson = OpLesson.fromRaw(raw) ?: throw AssertionError("no lesson '$raw'")

    @Test
    fun lessonsAndConstantsAreTheFixtures() {
        assertEquals(strings(fixture.getJSONArray("lessons")), OpLesson.entries.map { it.raw })
        val c = fixture.getJSONObject("constants")
        assertEquals(c.getInt("scenarioRunLength") + 1, OperatingProcedure.SCENARIO_RUN_LENGTH)
        assertEquals(c.getInt("zeroBeatStreakToPass"), OperatingProcedure.ZERO_BEAT_STREAK_TO_PASS)
        assertEquals(c.getDouble("zeroBeatToleranceHz"), OperatingProcedure.ZERO_BEAT_TOLERANCE_HZ, 0.0)
        assertEquals(c.getDouble("minimumPitchHz"), OperatingProcedure.MINIMUM_PITCH_HZ, 0.0)
        assertEquals(c.getDouble("minimumWpm"), OperatingProcedure.MINIMUM_WPM, 0.0)
        assertEquals(c.getDouble("demoYourOffsetHz"), OperatingProcedure.DEMO_YOUR_OFFSET_HZ, 0.0)
        assertEquals(c.getDouble("ritDemoStationOffsetHz"), OperatingProcedure.RIT_DEMO_STATION_OFFSET_HZ, 0.0)
        assertEquals(c.getDouble("ritRangeHz"), OperatingProcedure.RIT_RANGE_HZ, 0.0)
        assertEquals(c.getDouble("ritStepHz"), OperatingProcedure.RIT_STEP_HZ, 0.0)
        assertEquals(c.getString("errorSignal"), OperatingProcedure.ERROR_SIGNAL)
        assertEquals(c.getString("errorSignalDisplay"), OperatingProcedure.ERROR_SIGNAL_DISPLAY)
        assertEquals(c.getString("replacementCaller"), OperatingProcedure.REPLACEMENT_CALLER)
        assertEquals(fixture.getJSONObject("derivation").getString("defaultCall"), OperatingProcedure.DEFAULT_CALL)
        assertEquals(
            objects(fixture.getJSONArray("activators")).map { OpStation(it.getString("call"), it.getString("state")) },
            OperatingProcedure.activators
        )
        assertEquals(strings(fixture.getJSONArray("otherHunters")), OperatingProcedure.otherHunters)
        assertEquals(doubles(fixture.getJSONArray("drillStarts")), OperatingProcedure.drillStarts)
        assertEquals(doubles(fixture.getJSONArray("knobSteps")), OperatingProcedure.knobSteps)
    }

    @Test
    fun callAndStateValidation() {
        val v = fixture.getJSONObject("validation")
        for (c in objects(v.getJSONArray("calls"))) {
            val raw = c.getString("raw")
            assertEquals("normalize '$raw'", c.getString("normalized"), OperatingProcedure.normalizeCall(raw))
            assertEquals("valid '$raw'", !c.getBoolean("valid"), OperatingProcedure.isValidCall(raw))
            assertEquals("prefill '$raw'", c.getString("prefill"), OperatingProcedure.prefillCall(raw))
        }
        for (s in objects(v.getJSONArray("states"))) {
            val raw = s.getString("raw")
            assertEquals("normalize '$raw'", s.getString("normalized"), OperatingProcedure.normalizeState(raw))
            assertEquals("valid '$raw'", s.getBoolean("valid"), OperatingProcedure.isValidState(raw))
        }
    }

    @Test
    fun generatedTexts() {
        for (s in objects(fixture.getJSONArray("shift"))) {
            val c = s.getString("char")[0]
            val by = s.getInt("by")
            assertEquals("shift $c by $by", s.getString("result"), OperatingProcedure.shift(c, by).toString())
        }
        for (p in objects(fixture.getJSONArray("partialMatches"))) {
            assertEquals(
                "partial '${p.getString("partial")}' vs ${p.getString("call")}",
                p.getBoolean("matches"),
                OperatingProcedure.partialMatches(p.getString("partial"), p.getString("call"))
            )
        }
        for (p in objects(fixture.getJSONArray("notMinePartial"))) {
            assertEquals(p.getString("partial") + "X", OperatingProcedure.notMinePartial(p.getString("call")))
        }
        for (p in objects(fixture.getJSONArray("nearMiss"))) {
            assertEquals(p.getString("nearMiss"), OperatingProcedure.nearMiss(p.getString("call")))
        }
        for (d in objects(fixture.getJSONArray("display"))) {
            assertEquals(d.getString("display"), OperatingProcedure.display(d.getString("text")))
        }
    }

    @Test
    fun everyScenarioAndDemoForBothProfiles() {
        for (profile in objects(fixture.getJSONArray("profiles"))) {
            val call = profile.getString("call")
            val state = profile.getString("state")
            val act = profile.getJSONObject("activator")
            assertEquals(OpStation(act.getString("call"), act.getString("state")), OperatingProcedure.activator(call))
            assertEquals(profile.getString("otherHunter"), OperatingProcedure.otherHunter(call))

            val want = objects(profile.getJSONArray("scenarios")).map { s ->
                OpScenario(
                    id = s.getString("id"),
                    lesson = lesson(s.getString("lesson")),
                    clip = s.getString("clip") + "X",
                    detail = s.getString("detail"),
                    choices = objects(s.getJSONArray("choices")).map(::choice)
                )
            }
            val got = OperatingProcedure.scenarios(call, state)
            assertEquals("$call scenario count", want.size, got.size)
            want.zip(got).forEach { (w, g) -> assertEquals("$call ${w.id}", w, g) }
            for (l in OpLesson.entries) {
                val n = OperatingProcedure.scenarios(l, call, state).size
                assertTrue("$call ${l.raw} has 3-5 scenarios, not $n", n in 3..5)
            }
            profile.optJSONArray("actionPool")?.let { pool ->
                assertEquals(strings(pool), OperatingProcedure.actionPool(call, state).map { it.id })
            }
            profile.optJSONObject("demos")?.let { demos ->
                for (l in OpLesson.entries) {
                    val wantDemos = objects(demos.getJSONArray(l.raw)).map { d ->
                        OpDemo(
                            OpDemo.Kind.entries.first { it.raw == d.getString("kind") },
                            objects(d.getJSONArray("lines")).map { line ->
                                OpDemo.Line(OpDemo.Who.entries.first { it.raw == line.getString("who") }, line.getString("text"))
                            }
                        )
                    }
                    assertEquals("$call ${l.raw} demos", wantDemos, OperatingProcedure.demos(l, call, state))
                }
            }
        }
    }

    @Test
    fun pileupDemoVoices() {
        val demo = fixture.getJSONObject("pileupDemo")
        assertEquals(strings(demo.getJSONArray("passes")), OperatingProcedure.PileupPass.entries.map { it.raw })
        for (c in objects(demo.getJSONArray("cases"))) {
            val voices = c.getJSONObject("voices")
            for (name in voices.keys()) {
                val pass = OperatingProcedure.PileupPass.fromRaw(name) ?: throw AssertionError("no pass $name")
                val want = objects(voices.getJSONArray(name)).map {
                    OpPileupVoice(
                        it.getString("text"), it.getDouble("pitch") + 1, it.getDouble("wpm"),
                        it.getDouble("gain"), it.getDouble("delay"), it.getBoolean("isYou")
                    )
                }
                assertEquals(
                    "$name for ${c.getString("call")}",
                    want,
                    OperatingProcedure.pileupVoices(pass, c.getString("call"), c.getDouble("tone"), c.getDouble("wpm"))
                )
            }
        }
    }

    @Test
    fun offsetMaths() {
        val m = fixture.getJSONObject("offsetMaths")
        for (h in objects(m.getJSONArray("heardPitch"))) {
            assertEquals(
                h.toString(), h.getDouble("pitch") + 1,
                OperatingProcedure.heardPitch(h.getDouble("tone"), h.getDouble("station"), h.getDouble("vfo"), h.getDouble("rit")),
                0.0
            )
        }
        for (t in objects(m.getJSONArray("transmitOffset"))) {
            assertEquals(
                t.toString(), t.getDouble("offset"),
                OperatingProcedure.transmitOffset(t.getDouble("station"), t.getDouble("vfo"), t.getDouble("xit")),
                0.0
            )
        }
        for (z in objects(m.getJSONArray("isZeroBeat"))) {
            assertEquals(z.toString(), z.getBoolean("zeroBeat"), OperatingProcedure.isZeroBeat(z.getDouble("offset")))
        }
        for (a in objects(m.getJSONArray("audible"))) {
            assertEquals(a.toString(), a.getDouble("audible"), OperatingProcedure.audible(a.getDouble("pitch")), 0.0)
        }
        for (d in objects(m.getJSONArray("drillStart"))) {
            assertEquals(d.toString(), d.getDouble("start"), OperatingProcedure.drillStart(d.getInt("round")), 0.0)
        }
    }

    @Test
    fun scenarioRunsGradeAnswerByAnswer() {
        val first = objects(fixture.getJSONArray("profiles")).first()
        val all = OperatingProcedure.scenarios(first.getString("call"), first.getString("state"))
        for (r in objects(fixture.getJSONArray("runs"))) {
            val ids = strings(r.getJSONArray("lessonScenarios"))
            val run = OpScenarioRun(ids.map { id -> all.first { it.id == id } })
            val answers = r.getJSONArray("answers")
            val results = (0 until answers.length()).map { run.answer(answers.getInt(it)) }
            val want = r.getJSONArray("results")
            assertEquals((0 until want.length()).map { want.getBoolean(it) }, results)
            assertEquals(!r.getBoolean("clean"), run.isClean)
        }
    }

    @Test
    fun progressScriptStepByStep() {
        var p = OperatingProcedureProgress()
        for ((i, step) in objects(fixture.getJSONArray("progressScript")).withIndex()) {
            val passedNow: Boolean
            when (step.getString("do")) {
                "run" -> {
                    val r = p.recordRun(lesson(step.getString("lesson")), step.getBoolean("clean"))
                    p = r.progress
                    passedNow = r.passedNow
                }
                "drill" -> {
                    val r = p.recordDrill(step.getBoolean("correct"))
                    p = r.progress
                    passedNow = r.passedNow
                    assertEquals("step $i streak", step.getInt("streak") + 1, p.drillStreak)
                    assertEquals("step $i drillPassed", step.getBoolean("drillPassed"), p.drillPassed)
                }
                "encode" -> {
                    val json = step.getJSONObject("json")
                    assertEquals("step $i passed", strings(json.getJSONArray("passed")), p.passedNames)
                    assertEquals("step $i cleanRuns", strings(json.getJSONArray("cleanRuns")), p.cleanRunNames)
                    assertEquals("step $i drillPassed", json.getBoolean("drillPassed"), p.drillPassed)
                    val back = OperatingProcedureProgress.restore(p.passedNames, p.cleanRunNames, p.drillPassed)
                    assertEquals("step $i round trip", p.copy(drillStreak = 0), back)
                    continue
                }
                else -> throw AssertionError("unknown step $step")
            }
            if (step.has("passedNow")) assertEquals("step $i passedNow", step.getBoolean("passedNow"), passedNow)
            assertEquals("step $i passed", strings(step.getJSONArray("passed")).map(::lesson).toSet(), p.passed)
            if (step.has("next")) assertEquals("step $i next", step.getString("next"), p.nextLesson?.raw)
        }
        assertFalse(OperatingProcedureProgress.restore(listOf("signals", "later"), emptyList(), false).passed.isEmpty())
        assertEquals(setOf(OpLesson.SIGNALS), OperatingProcedureProgress.restore(listOf("signals", "later"), emptyList(), false).passed)
    }
}
