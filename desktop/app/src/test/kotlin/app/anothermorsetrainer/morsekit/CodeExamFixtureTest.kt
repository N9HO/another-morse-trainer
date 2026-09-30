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
 * same file the iOS `MorseKitCheck` harness reads (#262, #263).
 *
 * The pass bars, the required character set, the prosign patterns and the
 * grading cases are derived from the historical FCC/VEC rules written out in
 * the fixture, not captured from either port, so both ports drifting the same
 * way still fails.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class CodeExamFixtureTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("code-exam.json")
        assertNotNull("fixtures/code-exam.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private fun cases(section: String) =
        fixture.getJSONObject(section).getJSONArray("cases").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }

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
            val speed = ExamSpeed.allCases.firstOrNull { it.code == c.getString("speed") }
            assertNotNull("fixture speed ${c.getString("speed")} exists", speed)
            val expected = c.getInt("requiredRun") + 1 // NEGATIVE CONTROL: must fail
            assertEquals("${speed!!.code} effective WPM", c.getInt("effectiveWpm"), speed.effectiveWpm.toInt())
            assertEquals("${speed.code} solid-copy bar", expected, speed.requiredRun)
            assertEquals(
                "${speed.code} session grades against the bar",
                expected,
                ExamSession(speed, ExamGrading.SOLID_COPY, ExamData.examSamples.first().passage)
                    .gradeSolidCopy("").required
            )
            assertEquals(
                "Solid copy ($expected in a row)",
                ExamGrading.SOLID_COPY.label(speed)
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
            val plain = ExamPassage.normalize(sample.passage.sentText)
            assertTrue(
                "bundled passage ${sample.id} is keyable once its prosigns are set aside",
                plain.all { it == ' ' || MorseCode.pattern(it) != null }
            )
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
    fun normalizeDropsProsignsAndKeepsPunctuation() {
        for (c in cases("normalize")) {
            assertEquals(
                "normalize ${c.getString("input")}",
                c.getString("normalized"),
                ExamPassage.normalize(c.getString("input"))
            )
        }
    }

    @Test
    fun longestRunCountsCharactersNotSpaces() {
        for (c in cases("longestRun")) {
            val run = ExamSession.longestCommonRun(
                ExamPassage.normalize(c.getString("typed")).toList(),
                ExamPassage.normalize(c.getString("sent")).toList()
            )
            assertEquals(
                "run of '${c.getString("typed")}' in '${c.getString("sent")}'",
                c.getInt("longestRun"),
                run
            )
        }
    }

    @Test
    fun solidCopyPassesAtTheBarAndNotBelowIt() {
        val sample = ExamData.examSamples.first { it.speed == ExamSpeed.NOVICE5 }
        val copy = sample.passage.copyText

        /** The first [n] counted characters of the copy — spaces ride along free. */
        fun counted(n: Int): String {
            val out = StringBuilder()
            var seen = 0
            for (ch in copy) {
                if (ch != ' ') {
                    if (seen == n) break
                    seen += 1
                }
                out.append(ch)
            }
            return out.toString()
        }

        val novice = ExamSession(ExamSpeed.NOVICE5, ExamGrading.SOLID_COPY, sample.passage)
        assertTrue(novice.gradeSolidCopy(counted(25)).passed)
        assertFalse(novice.gradeSolidCopy(counted(24)).passed)
        assertEquals(24, novice.gradeSolidCopy(counted(24)).longestRun)
        assertTrue("a stray '=' is tolerated", novice.gradeSolidCopy("= " + counted(25)).passed)

        val general = ExamSession(ExamSpeed.GENERAL13, ExamGrading.SOLID_COPY, sample.passage)
        assertFalse("25 is not enough at 13 WPM", general.gradeSolidCopy(counted(25)).passed)
        assertTrue(general.gradeSolidCopy(counted(65)).passed)
        assertFalse(general.gradeSolidCopy(counted(64)).passed)

        for (speed in ExamSpeed.allCases) {
            val shortest = ExamData.examSamples(speed).minOf { s -> s.passage.copyText.count { it != ' ' } }
            assertTrue("${speed.code} passages reach ${speed.requiredRun}", shortest >= speed.requiredRun)
        }
    }
}
