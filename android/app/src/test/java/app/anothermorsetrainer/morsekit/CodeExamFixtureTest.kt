package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Code Exam, pinned against `fixtures/code-exam.json` at the repo root — the
 * same file the iOS `MorseKitCheck` harness reads.
 *
 * The pass bars, the ARRL counting weights, the required character set, the
 * prosign patterns and every grading case are worked out by hand from the ARRL
 * VEC rules written out (with sources) in the fixture, not captured from either
 * port, so both ports drifting the same way still fails.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class CodeExamFixtureTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("code-exam.json")
        assertNotNull("fixtures/code-exam.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun cases(section: String, key: String = "cases") =
        fixture.getJSONObject(section).getJSONArray(key).let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }

    private fun speed(code: String): ExamSpeed {
        val speed = ExamSpeed.allCases.firstOrNull { it.code == code }
        assertNotNull("fixture speed $code exists", speed)
        return speed!!
    }

    /** The ARRL-weighted run of [typed] in [sent], both normalized first. */
    private fun run(typed: String, sent: String) = ExamSession.longestCommonRun(
        ExamPassage.symbols(ExamPassage.normalize(typed)),
        ExamPassage.symbols(ExamPassage.normalize(sent))
    )

    /** Every character and prosign the fixture says a passage must send. */
    private val requiredTokens: List<String> by lazy {
        val req = fixture.getJSONObject("requiredCharacters")
        val punctuation = req.getJSONArray("punctuation")
        val prosigns = req.getJSONArray("prosigns")
        req.getString("letters").map { it.toString() } +
            req.getString("digits").map { it.toString() } +
            (0 until punctuation.length()).map { punctuation.getString(it) } +
            (0 until prosigns.length()).map { prosigns.getString(it) }
    }

    private fun missing(text: String) = requiredTokens.filter { !text.contains(it) }

    @Test
    fun passBarIsOneMinuteAtTheEffectiveSpeed() {
        assertEquals(
            fixture.getJSONObject("passBar").getInt("charactersPerWord"),
            ExamSpeed.CHARACTERS_PER_WORD
        )
        val list = cases("passBar")
        assertEquals(ExamSpeed.allCases.size, list.size)
        for (c in list) {
            val speed = speed(c.getString("speed"))
            val expected = c.getInt("requiredRun")
            assertEquals("${speed.code} effective WPM", c.getInt("effectiveWpm"), speed.effectiveWpm.toInt())
            assertEquals("${speed.code} solid-copy bar", expected + 1, speed.requiredRun)
            assertEquals(
                "${speed.code} session grades against the bar",
                expected,
                ExamSession(speed, ExamData.examSamples.first().passage).gradeSolidCopy("").required
            )
        }
    }

    @Test
    fun countingWeightsFollowTheArrlRule() {
        for (c in cases("weights")) {
            assertEquals(
                "'${c.getString("symbol")}' counts",
                c.getInt("weight") + 1,
                ExamPassage.weight(c.getString("symbol"))
            )
        }
    }

    @Test
    fun everyBundledPassageSendsTheRequiredCharacters() {
        for (sample in ExamData.examSamples) {
            assertTrue(
                "bundled passage ${sample.id} is missing ${missing(sample.passage.sentText)}",
                missing(sample.passage.sentText).isEmpty()
            )
            for (s in ExamPassage.symbols(sample.passage.copyText)) {
                if (s == " ") continue
                val keyable = if (s.length == 1) MorseCode.pattern(s[0]) != null
                else MorseData.prosigns.any { it.name == s }
                assertTrue("bundled passage ${sample.id}: '$s' is keyable", keyable)
            }
        }
    }

    @Test
    fun everyGeneratedPassageSendsTheRequiredCharacters() {
        val rng = Random(263)
        repeat(200) {
            val text = ExamSession.randomPassage(rng).sentText
            assertTrue("generated passage is missing ${missing(text)}: $text", missing(text).isEmpty())
        }
    }

    @Test
    fun bracketedProsignsKeyRunTogether() {
        val timing = MorseTiming(20.0)
        for (c in cases("prosignPatterns")) {
            val token = c.getString("token")
            val pattern = c.getString("pattern")
            assertEquals(
                "$token in sent text keys run-together as $pattern",
                MorseSynth.segments(MorseItem.Playable.Pattern(pattern), timing, 44_100.0),
                MorseSynth.segments(MorseItem.Playable.Text(token), timing, 44_100.0)
            )
            assertEquals(
                "the app's prosign table spells $token as $pattern",
                pattern,
                MorseData.prosigns.firstOrNull { it.name == token }?.pattern
            )
        }
    }

    @Test
    fun normalizeKeepsProsignsAsTokens() {
        for (c in cases("normalize")) {
            assertEquals(
                "normalize ${c.getString("input")}",
                c.getString("normalized") + "X",
                ExamPassage.normalize(c.getString("input"))
            )
        }
    }

    @Test
    fun longestRunIsWeightedTheArrlWay() {
        for (c in cases("longestRun")) {
            assertEquals(
                "run of '${c.getString("typed")}' in '${c.getString("sent")}'",
                c.getInt("longestRun") + 1,
                run(c.getString("typed"), c.getString("sent"))
            )
        }
    }

    @Test
    fun solidCopyPassesAtTheBarInCountedCharacters() {
        for (c in cases("solidCopy")) {
            val speed = speed(c.getString("speed"))
            val r = run(c.getString("typed"), c.getString("sent"))
            assertEquals("${speed.code}: '${c.getString("typed")}' counts", c.getInt("longestRun") + 1, r)
            assertEquals(
                "${speed.code}: '${c.getString("typed")}' passes",
                c.getBoolean("passed"),
                ExamCopyResult(r, speed.requiredRun).passed
            )
        }
    }

    @Test
    fun questionsPassOnSevenOfTenAndTheResultNamesThePath() {
        val q = fixture.getJSONObject("questions")
        assertEquals(q.getInt("asked"), ExamSession.QUESTION_COUNT)
        assertEquals(q.getInt("required") + 1, ExamSession.QUESTIONS_TO_PASS)
        assertEquals(
            q.getInt("asked"),
            ExamSession(ExamSpeed.GENERAL13, ExamData.examSamples.first().passage).questions.size
        )
        for (c in cases("questions", "results")) {
            val speed = speed(c.getString("speed"))
            val r = ExamResult(
                copy = ExamCopyResult(c.getInt("longestRun"), speed.requiredRun),
                questionsCorrect = c.getInt("questionsCorrect"),
                questionsAsked = ExamSession.QUESTION_COUNT,
                questionsRequired = ExamSession.QUESTIONS_TO_PASS
            )
            val label = "${speed.code} run ${c.getInt("longestRun")} + ${c.getInt("questionsCorrect")}/10"
            assertEquals("$label passes", c.getBoolean("passed"), r.passed)
            val expectedPath = when (c.getString("path")) {
                "solidCopy" -> ExamPassPath.SOLID_COPY
                "questions" -> ExamPassPath.QUESTIONS
                "both" -> ExamPassPath.BOTH
                else -> ExamPassPath.NONE
            }
            assertEquals("$label path", expectedPath, r.path)
        }
    }

    @Test
    fun fillInAnswersCompareLikeCopy() {
        for (c in cases("questions", "answers")) {
            val arr = c.getJSONArray("accepted")
            val accepted = (0 until arr.length()).map { arr.getString(it) }
            val q = ExamQuestion("____", accepted.first(), accepted)
            assertEquals(
                "'${c.getString("typed")}' fills a blank of $accepted",
                !c.getBoolean("right"),
                q.accepts(c.getString("typed"))
            )
        }
    }

    @Test
    fun aSittingGradesBothPaths() {
        val sample = ExamData.examSamples.first { it.speed == ExamSpeed.NOVICE5 }
        val session = ExamSession(ExamSpeed.NOVICE5, sample.passage)
        val copy = session.passage.copyText
        val full = ExamPassage.symbols(copy).sumOf { ExamPassage.weight(it) }

        assertTrue("copy keeps the prosigns", copy.contains("<BT>") && copy.contains("<AR>") && copy.contains("<SK>"))
        assertEquals("a perfect copy counts the whole passage", full, session.gradeSolidCopy(copy).longestRun)
        assertEquals(
            "writing <BT> as = and <AR> as + grades the same",
            session.gradeSolidCopy(copy),
            session.gradeSolidCopy(copy.replace("<BT>", "=").replace("<AR>", "+"))
        )
        assertTrue(
            "leaving the prosigns out costs the run",
            session.gradeSolidCopy(copy.replace("<BT>", " ")).longestRun < full
        )
        for (speed in ExamSpeed.allCases) {
            val shortest = ExamData.examSamples(speed).minOf { s ->
                ExamPassage.symbols(s.passage.copyText).sumOf { ExamPassage.weight(it) }
            }
            assertTrue("${speed.code} passages reach ${speed.requiredRun}", shortest >= speed.requiredRun)
        }

        assertTrue("every answer can be read off the copy", session.questions.all { copy.contains(it.answer) })
        assertFalse("a wrong answer scores incorrect", session.answer("\u0001nope"))
        val second = session.currentQuestion!!
        assertTrue("a right answer in lower case scores", session.answer(second.answer.lowercase()))
        while (true) {
            val q = session.currentQuestion ?: break
            session.answer(q.answer)
        }
        assertTrue(session.isComplete)
        assertEquals(8, session.correctCount)
        assertEquals("no copy, nine right: passed on the questions", ExamPassPath.QUESTIONS, session.result.path)
        session.submitCopy(copy)
        assertEquals("then a full copy: passed both ways", ExamPassPath.BOTH, session.result.path)
    }
}
