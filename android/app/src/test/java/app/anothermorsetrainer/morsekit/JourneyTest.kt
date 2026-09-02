package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Journey drills around sound-twin items (issue #75): the prosign <K> is a
 * lone -.- — audibly identical to the letter K — and both live in the pool at
 * the prosign levels. The two must never compete in one drill's options, and
 * hearing -.- either answer is accepted.
 *
 * And the new-item share (#110): a level exists to teach its new items, so
 * they get at least half its prompts however big the pool has grown, and a
 * prosign is drilled against other prosigns once the pool has them. Twin of
 * the `#110` checks in the iOS harness, MorseKitCheck/main.swift.
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

    /**
     * Where a declared starting level lands on the map (#109). Worked by hand
     * from the Koch order "KMRSUAPTLOWINJEF0YVG5Q9ZH38B?427C1D6X", two
     * characters a level: the first 13 letters run K…N, and J (14th) is level
     * 7's; the 26 letters end before the first digit, 0, which opens level 9;
     * every character including "?" is known by level 19, so the first
     * prosign level, 20, is where that learner starts.
     */
    @Test
    fun aDeclaredStartingLevelUnlocksTheJourneyThatFar() {
        val koch = MorseCode.kochOrder
        val letters = koch.filter { it.isLetter() }
        assertEquals("K M only: nothing beyond level 1 is known", 2, JourneyCurriculum.firstLevelBeyond(koch.take(2).toSet()))
        assertEquals("some letters", 7, JourneyCurriculum.firstLevelBeyond(letters.take(13).toSet()))
        assertEquals("all letters", 12, JourneyCurriculum.firstLevelBeyond(letters.toSet()))
        assertEquals("all letters and numbers", 20, JourneyCurriculum.firstLevelBeyond(koch.toSet()))
        assertEquals("nothing known", 1, JourneyCurriculum.firstLevelBeyond(emptySet()))
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

    private val knLevelIndex = levels.indexOfFirst { lvl -> lvl.newItems.any { it.id == "<KN>" } }
    private val prosignAnswers = MorseData.prosignItems.map { it.answer }.toSet()

    @Test
    fun curriculumIntroducesTheProsignKN() {
        assertTrue(knLevelIndex >= 0)
        val poolProsigns = levels[knLevelIndex].pool.count { it.answer in prosignAnswers }
        assertTrue("the <KN> level's pool should hold enough prosigns to fill its options", poolProsigns >= 4)
    }

    @Test
    fun aLateLevelsNewItemsGetAboutHalfItsPrompts() {
        val quiz = JourneyQuiz(levels, startIndex = knLevelIndex, rng = Random(5))
        val newAnswers = levels[knLevelIndex].newItems.map { it.answer }.toSet()
        val draws = 2000
        var fresh = 0
        repeat(draws) {
            quiz.select(knLevelIndex)   // pin the level; drills never clear it
            val d = quiz.nextDrill()
            if (d.correct in newAnswers) fresh += 1
            quiz.record(d.correct, ttr = 0.5)
        }
        val share = fresh.toDouble() / draws
        assertTrue("new-item share $share", share > 0.4 && share < 0.6)
    }

    @Test
    fun aProsignIsDrilledAgainstOtherProsigns() {
        val quiz = JourneyQuiz(levels, startIndex = knLevelIndex, rng = Random(5))
        var prosignDrills = 0
        var prosignOnly = 0
        repeat(2000) {
            quiz.select(knLevelIndex)
            val d = quiz.nextDrill()
            if (d.correct in prosignAnswers) {
                prosignDrills += 1
                if (d.options.all { it in prosignAnswers }) prosignOnly += 1
            }
            quiz.record(d.correct, ttr = 0.5)
        }
        assertTrue("no prosign drawn — weaken the seed check", prosignDrills > 0)
        assertEquals("prosign drills with only prosign options", prosignDrills, prosignOnly)
    }

    /** The floor is a floor: level 2's two new letters already outweigh its two old ones. */
    @Test
    fun anEarlyLevelsNewItemsKeepMoreThanHalfItsPrompts() {
        val quiz = JourneyQuiz(levels, startIndex = 1, rng = Random(5))
        val newAnswers = levels[1].newItems.map { it.answer }.toSet()
        var fresh = 0
        repeat(2000) {
            quiz.select(1)
            val d = quiz.nextDrill()
            if (d.correct in newAnswers) fresh += 1
            quiz.record(d.correct, ttr = 0.5)
        }
        assertTrue("new-item share ${fresh / 2000.0}", fresh / 2000.0 > 0.5)
    }
}
