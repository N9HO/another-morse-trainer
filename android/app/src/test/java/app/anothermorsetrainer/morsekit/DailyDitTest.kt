package app.anothermorsetrainer.morsekit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Daily Dit, pinned against `fixtures/daily-dit.json` at the repo root — the
 * same file the iOS `MorseKitCheck` harness reads.
 *
 * Daily Dit raises the stakes on the two-ports rule. Every other mode can drift
 * between the trees and merely be *different*; this one is a **shared** puzzle,
 * so an iOS player and an Android player comparing share texts on the same day
 * must be talking about the same word, and a share text posted today has to
 * still mean what it said a year from now. The fixture therefore pins the real
 * words, not just the arithmetic: regenerating the answer list fails here
 * first, which is the announcement CLAUDE.md asks for.
 *
 * Expected values were derived from the documented rules independently of
 * either implementation, so both ports drifting the same way still fails.
 *
 * Put on the classpath by `sourceSets["test"].resources` in build.gradle.kts.
 */
class DailyDitTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("daily-dit.json")
        assertNotNull("fixtures/daily-dit.json is not on the test classpath", stream)
        JSONObject(stream!!.bufferedReader().readText())
    }

    private val rules: JSONObject get() = fixture.getJSONObject("rules")

    @Test
    fun ruleConstantsMatchTheFixture() {
        assertEquals(rules.getInt("maxGuesses"), DailyDit.MAX_GUESSES)
        assertEquals(rules.getInt("guessesPerSpeedStep"), DailyDit.GUESSES_PER_SPEED_STEP)
        assertEquals(rules.getInt("wordLength"), DailyDit.WORD_LENGTH)
        assertEquals(rules.getInt("selectionStride"), DailyDit.SELECTION_STRIDE)
        assertEquals(rules.getDouble("speedStepWpm"), DailyDit.SPEED_STEP_WPM, 0.0)
        assertEquals(rules.getDouble("minimumWpm"), DailyDit.MINIMUM_WPM, 0.0)
        assertEquals(rules.getString("shareLink"), DailyDit.SHARE_LINK)

        val speeds = rules.getJSONArray("startingSpeeds")
        assertEquals(speeds.length(), DailyDit.startingSpeeds.size)
        for (i in 0 until speeds.length()) {
            assertEquals(speeds.getDouble(i), DailyDit.startingSpeeds[i], 0.0)
        }
        // The whole point of the brief's QRQ ask.
        assertTrue("75 WPM must be on the dial", DailyDit.startingSpeeds.contains(75.0))

        val epoch = rules.getJSONObject("epoch")
        assertEquals(epoch.getInt("year"), DailyDit.epoch.year)
        assertEquals(epoch.getInt("month"), DailyDit.epoch.monthValue)
        assertEquals(epoch.getInt("day"), DailyDit.epoch.dayOfMonth)

        val emoji = rules.getJSONObject("emoji")
        assertEquals(emoji.getString("correct"), DailyDitTile.CORRECT.emoji)
        assertEquals(emoji.getString("present"), DailyDitTile.PRESENT.emoji)
        assertEquals(emoji.getString("absent"), DailyDitTile.ABSENT.emoji)
    }

    @Test
    fun wordListsAreTheShapeTheFixturePins() {
        val lists = fixture.getJSONObject("wordLists")
        assertEquals(lists.getInt("answerCount"), MorseData.dailyDitAnswers.size)
        assertEquals(lists.getInt("allowedCount"), MorseData.dailyDitAllowed.size)

        assertTrue(
            "every allowed word is five letters, A-Z",
            MorseData.dailyDitAllowed.all { w ->
                w.length == DailyDit.WORD_LENGTH && w.all { it in 'A'..'Z' }
            }
        )
        assertTrue(
            "every answer is a legal guess",
            MorseData.dailyDitAnswers.all { DailyDit.isAllowedGuess(it) }
        )

        val allowed = lists.getJSONArray("sampleAllowed")
        for (i in 0 until allowed.length()) {
            val w = allowed.getString(i)
            assertTrue("$w should be a legal guess", DailyDit.isAllowedGuess(w))
        }
        val rejected = lists.getJSONArray("sampleRejected")
        for (i in 0 until rejected.length()) {
            val w = rejected.getString(i)
            assertTrue("$w should not be a legal guess", !DailyDit.isAllowedGuess(w))
        }
    }

    /**
     * A stride sharing a factor with the pool size would trap the puzzle in a
     * short cycle, repeating words long before the pool is used up.
     */
    @Test
    fun everyAnswerIsUsedOnceBeforeAnyRepeats() {
        assertTrue(
            fixture.getJSONObject("wordLists").getBoolean("strideCoprimeWithAnswerCount")
        )
        val pool = MorseData.dailyDitAnswers
        val walked = (1..pool.size).map { DailyDit.answer(it) }.toSet()
        assertEquals(pool.toSet().size, walked.size)
        // …and then it comes back round to the start.
        assertEquals(DailyDit.answer(1), DailyDit.answer(pool.size + 1))
    }

    @Test
    fun civilDateArithmeticMatchesTheFixture() {
        val cases = fixture.getJSONArray("civilDates")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val y = c.getInt("year"); val m = c.getInt("month"); val d = c.getInt("day")
            assertEquals(
                "days from 1970 to $y-$m-$d",
                c.getInt("daysFrom1970").toLong(),
                DailyDit.daysFromCivil(y, m, d).toLong()
            )
            assertEquals(
                "puzzle number for $y-$m-$d",
                c.getInt("puzzleNumber"),
                DailyDit.puzzleNumber(y, m, d)
            )
            // The hand-written algorithm and the platform calendar must agree —
            // this is the check that would catch a typo in one of them.
            assertEquals(
                "days_from_civil agrees with LocalDate for $y-$m-$d",
                LocalDate.of(y, m, d).toEpochDay(),
                DailyDit.daysFromCivil(y, m, d).toLong()
            )
        }
    }

    @Test
    fun theDailyWordMatchesTheFixture() {
        val cases = fixture.getJSONArray("puzzles")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val n = c.getInt("puzzleNumber")
            assertEquals("answer for puzzle #$n", c.getString("answer"), DailyDit.answer(n))
            assertEquals(
                "answer index for puzzle #$n",
                MorseData.dailyDitAnswers[c.getInt("answerIndex")],
                DailyDit.answer(n)
            )
        }
    }

    @Test
    fun aDateResolvesToItsOwnLocalDaysPuzzle() {
        // 2026-09-02 is puzzle #245 wherever you are; the day rolls over on the
        // player's own clock, which is what makes "today's puzzle" mean the
        // same thing to everyone.
        assertEquals(245, DailyDit.puzzleNumber(LocalDate.of(2026, 9, 2)))
        assertEquals(246, DailyDit.puzzleNumber(LocalDate.of(2026, 9, 3)))
    }

    @Test
    fun guessScoringMatchesTheFixture() {
        val cases = fixture.getJSONArray("scoring")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val expected = c.getJSONArray("tiles")
            val tiles = DailyDit.score(c.getString("guess"), c.getString("answer"))
            assertEquals("tile count for ${c.getString("guess")}", expected.length(), tiles.size)
            for (t in tiles.indices) {
                assertEquals(
                    "${c.getString("guess")}/${c.getString("answer")} tile $t",
                    expected.getString(t),
                    tiles[t].key
                )
            }
        }
    }

    @Test
    fun theSpeedLadderMatchesTheFixture() {
        val cases = fixture.getJSONArray("ladder")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(
                "${c.getDouble("startingWpm")} WPM after ${c.getInt("guessesUsed")} guesses",
                c.getDouble("wpm"),
                DailyDit.wpm(c.getDouble("startingWpm"), c.getInt("guessesUsed")),
                0.0
            )
        }
        assertEquals(DailyDit.MINIMUM_WPM, DailyDit.wpm(20.0, 500), 0.0)
    }

    @Test
    fun aPlayedGameProducesTheFixturesShareText() {
        val share = fixture.getJSONObject("share")
        var game = DailyDitGame(
            puzzleNumber = share.getInt("puzzleNumber"),
            answer = share.getString("answer"),
            startingWpm = share.getDouble("startingWpm"),
            hideReference = share.getBoolean("hideReference")
        )
        val guesses = share.getJSONArray("guesses")
        for (i in 0 until guesses.length()) {
            val result = game.submit(guesses.getString(i))
            assertTrue(
                "${guesses.getString(i)} should be accepted",
                result is DailyDitSubmission.Scored
            )
            game = (result as DailyDitSubmission.Scored).game
        }
        assertEquals(DailyDitOutcome.SOLVED, game.outcome)
        assertEquals(share.getDouble("startingWpm"), game.solvedWpm!!, 0.0)
        assertEquals(share.getString("shareText"), game.shareText)
        assertTrue(game.shareText.endsWith(DailyDit.SHARE_LINK))
    }

    @Test
    fun headlinesMatchTheFixture() {
        val miss = List(DailyDit.WORD_LENGTH) { DailyDitTile.ABSENT }
        val hit = List(DailyDit.WORD_LENGTH) { DailyDitTile.CORRECT }
        val cases = fixture.getJSONArray("headlines")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val outcome = c.getString("outcome")
            val fillers = c.getInt("guessesUsed") - if (outcome == "solved") 1 else 0
            val rounds = MutableList(fillers) { DailyDitRound("MOUND", miss, 0.0) }
            if (outcome == "solved") {
                rounds += DailyDitRound("SPEND", hit, c.getDouble("solvedWpm"))
            }
            val game = DailyDitGame(
                puzzleNumber = c.getInt("puzzleNumber"),
                answer = "SPEND",
                startingWpm = 60.0,
                hideReference = c.getBoolean("hideReference"),
                rounds = rounds
            )
            assertEquals(outcome, game.outcome.key)
            assertEquals(c.getString("headline"), game.headline)
        }
    }

    // ---- Rules the fixture can't express as a table -------------------------

    @Test
    fun aRejectedGuessCostsNothing() {
        val game = DailyDitGame(1, "SPEND", 40.0)
        val short = game.submit("SPEN")
        assertTrue(short is DailyDitSubmission.Rejected)
        assertEquals(DailyDitRejection.WRONG_LENGTH, (short as DailyDitSubmission.Rejected).reason)

        val nonsense = game.submit("ZZZZZ")
        assertTrue(nonsense is DailyDitSubmission.Rejected)
        assertEquals(DailyDitRejection.NOT_A_WORD, (nonsense as DailyDitSubmission.Rejected).reason)

        assertEquals(0, game.guessesUsed)
    }

    /**
     * Repeating a guess is legal: guesses buy speed steps, so spending one to
     * drag the code slower is a tactic, not a mistake to guard against.
     */
    @Test
    fun aRepeatedGuessIsLegalAndStepsTheSpeedDown() {
        var game = DailyDitGame(1, "SPEND", 40.0)
        repeat(3) {
            val result = game.submit("mound")   // lower case is fine
            assertTrue(result is DailyDitSubmission.Scored)
            game = (result as DailyDitSubmission.Scored).game
        }
        assertEquals(3, game.guessesUsed)
        assertEquals(35.0, game.currentWpm, 0.0)
        // Each of those was *played* at the pre-step speed.
        assertTrue(game.rounds.all { it.wpm == 40.0 })
        assertEquals(setOf('M', 'O', 'U'), game.eliminatedLetters)
    }

    @Test
    fun aFinishedGameTakesNoMoreGuesses() {
        val miss = List(DailyDit.WORD_LENGTH) { DailyDitTile.ABSENT }
        val game = DailyDitGame(
            puzzleNumber = 1,
            answer = "SPEND",
            startingWpm = 40.0,
            rounds = List(DailyDit.MAX_GUESSES) { DailyDitRound("MOUND", miss, 40.0) }
        )
        assertEquals(DailyDitOutcome.LOST, game.outcome)
        assertEquals(0, game.guessesLeft)
        val result = game.submit("SPEND")
        assertTrue(result is DailyDitSubmission.Rejected)
        assertEquals(DailyDitRejection.FINISHED, (result as DailyDitSubmission.Rejected).reason)
    }
}
