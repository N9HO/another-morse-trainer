package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * First Four (#265), held to the same expectations as the Swift
 * MorseKitCheck "First Four" section: the two are twins. Everything is
 * pinned by `fixtures/first-four.json`, the same file the Swift harness reads.
 */
class FirstFourTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("first-four.json")
        assertNotNull("fixtures/first-four.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun objects(key: String): List<JSONObject> {
        val a = fixture.getJSONArray(key)
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun stage(raw: String): FirstFourStage =
        FirstFourStage.fromRaw(raw) ?: throw AssertionError("no stage '$raw'")

    @Test
    fun stagesAndConstantsAreTheFixtures() {
        assertEquals(strings(fixture.getJSONArray("stages")), FirstFourStage.entries.map { it.raw })
        assertEquals(
            strings(fixture.getJSONArray("elementStages")),
            FirstFourStage.entries.filter { it.isElement }.map { it.raw }
        )
        val c = fixture.getJSONObject("constants")
        assertEquals(c.getInt("copyStreakToPass") + 1, FirstFour.COPY_STREAK_TO_PASS)
        assertEquals(c.getInt("sendStreakToPass"), FirstFour.SEND_STREAK_TO_PASS)
        assertEquals(c.getInt("bustedRoundsToPass"), FirstFour.BUSTED_ROUNDS_TO_PASS)
        assertEquals(c.getInt("noReplyScenesToPass"), FirstFour.NO_REPLY_SCENES_TO_PASS)
        assertEquals(c.getInt("walkthroughRunsToPass"), FirstFour.WALKTHROUGH_RUNS_TO_PASS)
        assertEquals(fixture.getJSONObject("derivation").getString("defaultCall"), FirstFour.DEFAULT_CALL)
    }

    @Test
    fun stationTablesAndPicks() {
        assertEquals(
            objects("activators").map { FirstFourStation(it.getString("call"), it.getString("state")) },
            FirstFour.activators
        )
        assertEquals(strings(fixture.getJSONArray("otherHunters")), FirstFour.otherHunters)
        for (p in objects("activatorPicks")) {
            assertEquals(
                "activator pick $p",
                p.getString("call"),
                FirstFour.activator(p.getInt("index"), p.getString("excluding")).call
            )
        }
        for (p in objects("otherHunterPicks")) {
            assertEquals(
                "other hunter pick $p",
                p.getString("call"),
                FirstFour.otherHunter(p.getInt("index"), p.getString("excluding"))
            )
        }
    }

    @Test
    fun callAndStateValidation() {
        for (c in objects("callCases")) {
            val input = c.getString("input")
            assertEquals("normalise call '$input'", c.getString("normalized"), FirstFour.normalizeCall(input))
            assertEquals("validate call '$input'", c.getBoolean("valid"), FirstFour.isValidCall(input))
        }
        for (c in objects("stateCases")) {
            val input = c.getString("input")
            assertEquals("normalise state '$input'", c.getString("normalized"), FirstFour.normalizeState(input))
            assertEquals("validate state '$input'", c.getBoolean("valid"), FirstFour.isValidState(input))
        }
        for (c in objects("prefillCases")) {
            val saved = c.getString("saved")
            assertEquals("prefill from '$saved'", c.getString("prefill"), FirstFour.prefillCall(saved))
        }
    }

    @Test
    fun elementTexts() {
        for (c in objects("elements")) {
            val got = FirstFour.element(stage(c.getString("stage")), c.getString("call"), c.getString("state"))
            val want = if (c.isNull("text")) null else c.getString("text")
            assertEquals("element text for ${c.getString("stage")}", want, got)
        }
    }

    @Test
    fun copyAndSendMatching() {
        for (c in objects("copyCases")) {
            assertEquals(
                "copy '${c.getString("typed")}' for '${c.getString("expected")}'",
                c.getBoolean("match"),
                FirstFour.copyMatches(c.getString("typed"), c.getString("expected"))
            )
        }
        for (c in objects("sendCases")) {
            assertEquals(
                "send '${c.getString("sent")}' for '${c.getString("expected")}'",
                c.getBoolean("match"),
                FirstFour.sendMatches(c.getString("sent"), c.getString("expected"))
            )
        }
    }

    @Test
    fun bustedCallPartials() {
        for (c in objects("partialCases")) {
            val call = c.getString("call")
            assertEquals("partials of $call", strings(c.getJSONArray("partials")), FirstFour.partials(call))
        }
        for (c in objects("partialRounds")) {
            val call = c.getString("call")
            val round = c.getInt("round")
            assertEquals("partial for $call round $round", c.getString("partial"), FirstFour.partial(call, round))
        }
    }

    private fun build(s: JSONObject): List<FirstFourBeat> {
        val call = s.getString("call")
        val station = FirstFourStation(s.getString("activator"), s.getString("activatorState"))
        return when (val scene = s.getString("scene")) {
            "bustedCall" -> FirstFour.bustedCallScene(call, s.getString("partial"), station)
            "noReplyA" -> FirstFour.noReplySceneA(call, station)
            "noReplyB" -> FirstFour.noReplySceneB(call, station, s.getString("otherHunter"))
            "walkthrough" -> FirstFour.walkthroughScene(call, s.getString("state"), station)
            else -> throw AssertionError("unknown scene '$scene'")
        }
    }

    private fun wantBeats(s: JSONObject): List<FirstFourBeat> {
        val rows = s.getJSONArray("beats")
        return (0 until rows.length()).map { i ->
            val b = rows.getJSONObject(i)
            FirstFourBeat(
                kind = FirstFourBeat.Kind.fromRaw(b.getString("kind")) ?: throw AssertionError("kind in $b"),
                text = b.getString("text"),
                cue = FirstFourBeat.Cue.fromRaw(b.getString("cue")) ?: throw AssertionError("cue in $b"),
                answer = if (b.has("answer")) b.getString("answer") else null
            )
        }
    }

    @Test
    fun everySceneBeatByBeat() {
        for (s in objects("scenes")) {
            assertEquals("scene: ${s.getString("name")}", wantBeats(s), build(s))
        }
    }

    @Test
    fun gradedSceneRuns() {
        val scenes = objects("scenes").associate { it.getString("name") to build(it) }
        for (r in objects("sceneRuns")) {
            val name = r.getString("name")
            val beats = scenes[r.getString("scene")] ?: throw AssertionError("scene for run '$name'")
            val scene = FirstFourScene(beats)
            val steps = r.getJSONArray("steps")
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val text = step.optString("text", "")
                val response = when (step.getString("response")) {
                    "continued" -> FirstFourResponse.Continued
                    "sent" -> FirstFourResponse.Sent(text)
                    "copied" -> FirstFourResponse.Copied(text)
                    "waited" -> FirstFourResponse.Waited
                    else -> throw AssertionError("response in $step")
                }
                assertEquals("$name, step $i", step.getString("verdict"), scene.respond(response).raw)
            }
            assertEquals("$name finished", r.getBoolean("finished"), scene.isFinished)
            assertEquals("$name mistakes", r.getInt("mistakes"), scene.mistakes)
            assertEquals("$name clean", r.getBoolean("clean"), scene.isClean)
        }
    }

    @Test
    fun progressAnswerByAnswer() {
        val steps = fixture.getJSONObject("progressRun").getJSONArray("steps")
        assertTrue(steps.length() > 0)
        val p = FirstFourProgress()
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val at = "progress step $i ($step)"
            when (step.getString("op")) {
                "element" -> {
                    val done = p.recordElement(
                        stage(step.getString("stage")),
                        FirstFourPhase.fromRaw(step.getString("phase")) ?: throw AssertionError(at),
                        step.getBoolean("correct")
                    )
                    assertEquals(at, step.getBoolean("phaseDone"), done)
                    assertEquals(at, step.getInt("streak"), p.streak)
                    assertEquals(at, strings(step.getJSONArray("passed")).toSet(), p.passed.map { it.raw }.toSet())
                    assertEquals(at, strings(step.getJSONArray("copyPassed")).toSet(), p.copyPassed.map { it.raw }.toSet())
                    assertEquals(at, step.optString("next"), p.nextStage?.raw ?: "")
                }
                "scene" -> {
                    val s = stage(step.getString("stage"))
                    val passedNow = p.recordScene(s, step.getBoolean("clean"))
                    assertEquals(at, step.getBoolean("stagePassed"), passedNow)
                    assertEquals(at, step.getInt("cleanRuns"), p.cleanRuns(s))
                    assertEquals(at, strings(step.getJSONArray("passed")).toSet(), p.passed.map { it.raw }.toSet())
                    assertEquals(at, step.optString("next"), p.nextStage?.raw ?: "")
                }
                "noReplyScene" -> {
                    val b = FirstFour.noReplyUsesSceneB(p.cleanRuns(FirstFourStage.NO_REPLY))
                    assertEquals(at, step.getString("scene"), if (b) "B" else "A")
                }
                "complete" -> {
                    assertEquals(at, step.getBoolean("complete"), p.isComplete)
                    assertEquals(at, step.getInt("passedCount"), p.passedCount)
                }
                else -> fail("unknown op in $at")
            }
        }
    }

    @Test
    fun restoreKeepsCountersButNotTheStreak() {
        val p = FirstFourProgress()
        p.recordElement(FirstFourStage.CALL, FirstFourPhase.COPY, true)
        repeat(FirstFour.BUSTED_ROUNDS_TO_PASS) { p.recordScene(FirstFourStage.BUSTED_CALL, true) }
        val r = FirstFourProgress.restore(p.passed, p.copyPassed, p.cleanRunsByStage)
        assertEquals(p.passed, r.passed)
        assertEquals(FirstFour.BUSTED_ROUNDS_TO_PASS, r.cleanRuns(FirstFourStage.BUSTED_CALL))
        assertEquals(0, r.streak)
        assertFalse(r.isComplete)
    }

    @Test
    fun elementStageOpensOnSendOnceCopyIsDone() {
        val p = FirstFourProgress()
        repeat(FirstFour.COPY_STREAK_TO_PASS) { p.recordElement(FirstFourStage.STATE, FirstFourPhase.COPY, true) }
        assertEquals(FirstFourPhase.SEND, p.openingPhase(FirstFourStage.STATE))
        assertEquals(FirstFourPhase.COPY, p.openingPhase(FirstFourStage.CALL))
    }
}
