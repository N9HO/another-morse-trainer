package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Journey drills around sound-twin items (issue #75): the prosign <K> is a
 * lone -.- — audibly identical to the letter K — and both live in the pool at
 * the prosign levels. The two must never compete in one drill's options, and
 * hearing -.- either answer is accepted.
 */
class JourneyTest {

    private val levels = JourneyCurriculum.levels
    private val goAhead = MorseData.prosigns.first { it.name == "<K>" }.meaning
    private val kLevelIndex = levels.indexOfFirst { lvl -> lvl.newItems.any { it.id == "<K>" } }

    @Test
    fun curriculumIntroducesTheProsignK() {
        assertTrue(kLevelIndex >= 0)
    }

    @Test
    fun soundTwinsNeverShareADrill() {
        val quiz = JourneyQuiz(levels, startIndex = kLevelIndex, rng = Random(11))
        var sawProsignK = false
        var sawLetterK = false
        repeat(400) {
            quiz.select(kLevelIndex)   // pin the level; drills never clear it
            val d = quiz.nextDrill()
            when (d.correct) {
                goAhead -> {
                    sawProsignK = true
                    assertTrue("letter K offered against <K>", "K" !in d.options)
                }
                "K" -> {
                    sawLetterK = true
                    assertTrue("<K>'s meaning offered against letter K", goAhead !in d.options)
                }
            }
            quiz.record(d.correct, ttr = 0.5)
        }
        assertTrue("target <K> never drawn — weaken the seed check", sawProsignK)
        assertTrue("target letter K never drawn — weaken the seed check", sawLetterK)
    }

    @Test
    fun answeringKForAHeardProsignKCounts() {
        val quiz = JourneyQuiz(levels, startIndex = kLevelIndex, rng = Random(7))
        var tested = false
        repeat(400) {
            quiz.select(kLevelIndex)
            val d = quiz.nextDrill()
            if (d.correct == goAhead) {
                tested = true
                assertTrue("K rejected for a heard <K>", quiz.record("K", ttr = 0.5).correct)
            } else {
                quiz.record(d.correct, ttr = 0.5)
            }
        }
        assertTrue("target <K> never drawn — weaken the seed check", tested)
    }
}
