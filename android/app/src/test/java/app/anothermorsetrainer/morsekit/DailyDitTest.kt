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
        assertEquals(rules.getInt("listensPerSpeedStep"), DailyDit.LISTENS_PER_SPEED_STEP)
        assertEquals(rules.getInt("guessesPerSpeedStep"), DailyDit.GUESSES_PER_SPEED_STEP)
        // #168: no guess cap. The fixture has no such rule and neither does the port.
        assertTrue("the fixture must not reintroduce a guess cap", !rules.has("maxGuesses"))
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

    /**
     * The ladder has two independent counters (#168): listens and wrong
     * guesses each step the speed on their own, and the steps add. The fixture
     * table walks both axes — listens alone, guesses alone, both, and partials
     * of each that must *not* combine into a step.
     */
    @Test
    fun theSpeedLadderMatchesTheFixture() {
        val cases = fixture.getJSONArray("ladder")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(
                "${c.getDouble("startingWpm")} WPM after ${c.getInt("listens")} listens " +
                    "and ${c.getInt("wrongGuesses")} wrong guesses",
                c.getDouble("wpm"),
                DailyDit.wpm(c.getDouble("startingWpm"), c.getInt("listens"), c.getInt("wrongGuesses")),
                0.0
            )
        }
        assertEquals(DailyDit.MINIMUM_WPM, DailyDit.wpm(20.0, 500, 0), 0.0)
        assertEquals(DailyDit.MINIMUM_WPM, DailyDit.wpm(20.0, 0, 500), 0.0)
    }

    /** Runs the fixture's script of listens and guesses against a fresh game. */
    private fun play(share: JSONObject): DailyDitGame {
        var game = DailyDitGame(
            puzzleNumber = share.getInt("puzzleNumber"),
            answer = share.getString("answer"),
            startingWpm = share.getDouble("startingWpm"),
            hideReference = share.getBoolean("hideReference")
        )
        val plays = share.getJSONArray("plays")
        for (i in 0 until plays.length()) {
            val p = plays.getJSONObject(i)
            when (p.getString("action")) {
                "listen" -> game = game.listen().game
                "guess" -> {
                    val result = game.submit(p.getString("word"))
                    assertTrue("${p.getString("word")} should be accepted", result is DailyDitSubmission.Scored)
                    game = (result as DailyDitSubmission.Scored).game
                }
                else -> throw AssertionError("unknown play ${p.getString("action")}")
            }
        }
        return game
    }

    @Test
    fun aPlayedGameProducesTheFixturesShareText() {
        val share = fixture.getJSONObject("share")
        val game = play(share)
        assertEquals(DailyDitOutcome.SOLVED, game.outcome)
        assertEquals(share.getInt("listens"), game.listens)
        assertEquals(share.getInt("guessesUsed"), game.guessesUsed)
        assertEquals(share.getDouble("solvedWpm"), game.solvedWpm!!, 0.0)
        assertEquals(share.getString("shareText"), game.shareText)
        assertTrue(game.shareText.endsWith(DailyDit.SHARE_LINK))
    }

    /**
     * The same script, watched step by step: each listen is heard at the speed
     * in effect before it counted, each guess is made at the speed the listens
     * and earlier wrong guesses had reached.
     */
    @Test
    fun listensAndWrongGuessesStepTheSpeedAsTheFixtureSays() {
        val share = fixture.getJSONObject("share")
        val game = play(share)
        val heard = share.getJSONArray("heard")
        assertEquals(heard.length(), game.heard.size)
        for (i in 0 until heard.length()) {
            assertEquals("listen ${i + 1}", heard.getDouble(i), game.heard[i], 0.0)
        }
        val roundWpms = share.getJSONArray("roundWpms")
        assertEquals(roundWpms.length(), game.rounds.size)
        for (i in 0 until roundWpms.length()) {
            assertEquals("guess ${i + 1}", roundWpms.getDouble(i), game.rounds[i].wpm, 0.0)
        }
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
            val solvedWpm = if (c.isNull("solvedWpm")) 0.0 else c.getDouble("solvedWpm")
            if (outcome == "solved") {
                rounds += DailyDitRound("SPEND", hit, solvedWpm)
            }
            val game = DailyDitGame(
                puzzleNumber = c.getInt("puzzleNumber"),
                answer = "SPEND",
                startingWpm = 60.0,
                hideReference = c.getBoolean("hideReference"),
                rounds = rounds,
                heard = List(c.getInt("listens")) { solvedWpm }
            )
            assertEquals(outcome, game.outcome.key)
            assertEquals(c.getString("headline"), game.headline)
        }
    }

    /**
     * The reported speed is the slowest the word was *heard* at, not the speed
     * at the winning guess. The dial can be raised before the first guess, so
     * a later listen can be faster than an earlier one; the minimum still wins.
     */
    @Test
    fun theReportedSpeedIsTheLowestHeard() {
        val hit = List(DailyDit.WORD_LENGTH) { DailyDitTile.CORRECT }
        val cases = fixture.getJSONArray("solvedSpeeds")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val heardJson = c.getJSONArray("heard")
            val heard = (0 until heardJson.length()).map { heardJson.getDouble(it) }
            val game = DailyDitGame(
                puzzleNumber = 1,
                answer = "SPEND",
                startingWpm = 75.0,
                rounds = listOf(DailyDitRound("SPEND", hit, c.getDouble("winningGuessWpm"))),
                heard = heard
            )
            assertEquals("heard $heard", c.getDouble("solvedWpm"), game.solvedWpm!!, 0.0)
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
    fun listensStepTheSpeedDown() {
        var game = DailyDitGame(1, "SPEND", 40.0)
        val sentAt = ArrayList<Double>()
        repeat(4) {
            val play = game.listen()
            sentAt += play.wpm
            game = play.game
        }
        // Three listens at the starting speed; the fourth feels the step.
        assertEquals(listOf(40.0, 40.0, 40.0, 35.0), sentAt)
        assertEquals(sentAt, game.heard)
        assertEquals(4, game.listens)
        assertEquals(35.0, game.currentWpm, 0.0)
        assertEquals(0, game.guessesUsed)
    }

    @Test
    fun listensAndWrongGuessesStepIndependentlyAndAdd() {
        var game = DailyDitGame(1, "SPEND", 40.0)
        repeat(2) { game = game.listen().game }
        repeat(2) { game = (game.submit("MOUND") as DailyDitSubmission.Scored).game }
        // Two of each is a step of neither.
        assertEquals(40.0, game.currentWpm, 0.0)
        game = game.listen().game
        assertEquals(35.0, game.currentWpm, 0.0)
        game = (game.submit("MOUND") as DailyDitSubmission.Scored).game
        assertEquals(30.0, game.currentWpm, 0.0)
        assertEquals(3, game.listens)
        assertEquals(3, game.wrongGuesses)
    }

    @Test
    fun aGameNeverRunsOutOfGuesses() {
        var game = DailyDitGame(1, "SPEND", 40.0)
        repeat(100) {
            val result = game.submit("MOUND")
            assertTrue("guess ${it + 1} should still be accepted", result is DailyDitSubmission.Scored)
            game = (result as DailyDitSubmission.Scored).game
        }
        assertEquals(DailyDitOutcome.PLAYING, game.outcome)
        assertEquals(DailyDit.MINIMUM_WPM, game.currentWpm, 0.0)
        val win = game.submit("SPEND")
        assertTrue(win is DailyDitSubmission.Scored)
        game = (win as DailyDitSubmission.Scored).game
        assertEquals(DailyDitOutcome.SOLVED, game.outcome)
        assertEquals(101, game.guessesUsed)
        assertEquals(100, game.wrongGuesses)
    }

    @Test
    fun aSolvedGameTakesNoMoreGuessesAndReplaysFree() {
        var game = DailyDitGame(1, "SPEND", 40.0)
        game = game.listen().game
        game = (game.submit("SPEND") as DailyDitSubmission.Scored).game
        assertTrue(game.isFinished)
        val result = game.submit("MOUND")
        assertTrue(result is DailyDitSubmission.Rejected)
        assertEquals(DailyDitRejection.FINISHED, (result as DailyDitSubmission.Rejected).reason)
        // Hearing it again after the win is not a listen the share text counts.
        val replay = game.listen()
        assertEquals(40.0, replay.wpm, 0.0)
        assertEquals(game, replay.game)
        assertEquals(1, replay.game.listens)
    }

    /** A word guessed blind still reports a speed: the one the guess was made at. */
    @Test
    fun aWordGuessedWithoutListeningReportsTheGuessSpeed() {
        val game = (DailyDitGame(1, "SPEND", 40.0).submit("SPEND") as DailyDitSubmission.Scored).game
        assertEquals(0, game.listens)
        assertEquals(40.0, game.solvedWpm!!, 0.0)
        assertEquals("Daily Dit #1 — 40 WPM · 1 guess · 0 listens", game.headline)
    }
}
