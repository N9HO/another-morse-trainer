package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.DailyComprehensionGame
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The JSON [DailyComprehensionStore] writes to SharedPreferences, round-tripped
 * without a Context. Per-tree work, not a fixture: only the rules about what a
 * save must mean are shared. An answered question that failed to decode would
 * hand the player the day's question again.
 */
class DailyComprehensionStoreCodecTest {

    @Test
    fun anUnansweredGameRoundTrips() {
        val game = DailyComprehensionGame(puzzleNumber = 301, listens = 3)
        assertEquals(game, DailyComprehensionStore.decode(DailyComprehensionStore.encode(game)))
    }

    @Test
    fun anAnsweredGameRoundTrips() {
        val game = DailyComprehensionGame(puzzleNumber = 301, chosenPosition = 2, listens = 5)
        assertEquals(game, DailyComprehensionStore.decode(DailyComprehensionStore.encode(game)))
    }

    @Test
    fun aGameSavedWithoutListensStillRestores() {
        val game = DailyComprehensionStore.decode("""{"puzzleNumber":7,"chosenPosition":1}""")
        assertEquals(DailyComprehensionGame(7, 1, 0), game)
    }
}
