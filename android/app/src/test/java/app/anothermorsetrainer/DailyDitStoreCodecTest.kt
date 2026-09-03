package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.DailyDit
import app.anothermorsetrainer.morsekit.DailyDitGame
import app.anothermorsetrainer.morsekit.DailyDitOutcome
import app.anothermorsetrainer.morsekit.DailyDitRound
import app.anothermorsetrainer.morsekit.DailyDitSubmission
import app.anothermorsetrainer.morsekit.DailyDitTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON [DailyDitStore] writes to SharedPreferences, round-tripped without a
 * Context.
 *
 * Per-tree work, not a fixture: the two ports do not share a serialisation
 * format (iOS keeps a `Codable` snapshot under `MorseTrainer.dailyDit`), only
 * the rules about what a save must mean. Those rules matter more here than in
 * most modes — a half-played puzzle that fails to decode hands the player a
 * fresh 21 guesses on a word they have already been told 6 letters about, which
 * is not a crash, just a quietly broken game.
 */
class DailyDitStoreCodecTest {

    private fun playedGame(): DailyDitGame {
        var game = DailyDitGame(
            puzzleNumber = 245,
            answer = "SPEND",
            startingWpm = 60.0,
            hideReference = true
        )
        for (guess in listOf("MOUND", "CRANE", "SPEED")) {
            val result = game.submit(guess)
            assertTrue("$guess should be a legal guess", result is DailyDitSubmission.Scored)
            game = (result as DailyDitSubmission.Scored).game
        }
        return game
    }

    @Test
    fun aPlayedGameRoundTrips() {
        val game = playedGame()
        val back = DailyDitStore.decode(DailyDitStore.encode(game))
        assertEquals(game, back)
        // The share text is the thing a player actually keeps; check it survives
        // rather than trusting field equality to imply it.
        assertEquals(game.shareText, back.shareText)
        assertEquals(game.currentWpm, back.currentWpm, 0.0)
        assertEquals(DailyDitOutcome.PLAYING, back.outcome)
    }

    @Test
    fun aFinishedGameRoundTripsWithItsWinningSpeed() {
        var game = playedGame()
        game = (game.submit("SPEND") as DailyDitSubmission.Scored).game
        val back = DailyDitStore.decode(DailyDitStore.encode(game))
        assertEquals(DailyDitOutcome.SOLVED, back.outcome)
        assertNotNull(back.solvedWpm)
        assertEquals(game.solvedWpm!!, back.solvedWpm!!, 0.0)
        assertEquals(game.headline, back.headline)
    }

    /**
     * Tiles are stored by their stable key, not by ordinal, so reordering the
     * enum can't silently re-colour a saved grid.
     */
    @Test
    fun tilesAreStoredByNameNotOrdinal() {
        val game = playedGame()
        val json = DailyDitStore.encode(game)
        assertTrue("tiles should be written as names", json.contains("\"absent\""))
        for (tile in DailyDitTile.entries) {
            assertEquals(tile, DailyDitTile.fromKey(tile.key))
        }
        assertEquals(null, DailyDitTile.fromKey("chartreuse"))
    }

    /** An empty, untouched day is as important to restore as a played one. */
    @Test
    fun anUntouchedGameRoundTrips() {
        val game = DailyDitGame(1, DailyDit.answer(1), 75.0)
        val back = DailyDitStore.decode(DailyDitStore.encode(game))
        assertEquals(game, back)
        assertEquals(0, back.guessesUsed)
        assertEquals(75.0, back.currentWpm, 0.0)
    }

    /** A round carries the speed it was played at, and that has to survive too. */
    @Test
    fun perRoundSpeedsSurvive() {
        val miss = List(DailyDit.WORD_LENGTH) { DailyDitTile.ABSENT }
        val game = DailyDitGame(
            puzzleNumber = 3,
            answer = "ATLAS",
            startingWpm = 75.0,
            rounds = listOf(
                DailyDitRound("MOUND", miss, 75.0),
                DailyDitRound("CRANE", miss, 70.0),
                DailyDitRound("SPEND", miss, 65.0)
            )
        )
        val back = DailyDitStore.decode(DailyDitStore.encode(game))
        assertEquals(listOf(75.0, 70.0, 65.0), back.rounds.map { it.wpm })
    }
}
