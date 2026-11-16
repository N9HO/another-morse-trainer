package app.anothermorsetrainer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.anothermorsetrainer.morsekit.DailyComprehension
import app.anothermorsetrainer.morsekit.DailyComprehensionGame
import org.json.JSONObject
import java.time.LocalDate

/**
 * Holds and persists today's Daily Comprehension (#353).
 *
 * A process-wide singleton initialised in `main()` beside
 * [DailyDitStore]. The game is saved on every listen and on the answer, so a
 * relaunch cannot hand back a fresh question once one has been answered. An
 * answer counts toward the daily streak (like a Daily Dit guess) and is NOT a
 * ranked run.
 */
object DailyComprehensionStore {
    private lateinit var prefs: Prefs
    private const val GAME_KEY = "game"

    var game by mutableStateOf(DailyComprehensionGame(0))
        private set

    fun init() {
        prefs = Prefs.open("amt_dailycomprehension")
        refresh()
    }

    /** Bring [game] up to date with the calendar (the app can sit open across midnight). */
    fun refresh(today: LocalDate = LocalDate.now()) {
        val number = DailyComprehension.puzzleNumber(today)
        if (game.puzzleNumber == number) return
        val saved = load()
        if (saved != null && saved.puzzleNumber == number) {
            game = saved
        } else {
            game = DailyComprehensionGame(number)
            save()
        }
    }

    /** Count a play (a no-op once answered) and return the sentence to send. */
    fun listen(): String {
        refresh()
        val next = game.withListen()
        if (next != game) {
            game = next
            save()
        }
        return game.item.sentence
    }

    /** Lock in an answer; true when it was taken. Showing up is practice. */
    fun choose(position: Int): Boolean {
        val next = game.choose(position) ?: return false
        game = next
        save()
        Stats.recordPracticeDay()
        return true
    }

    private fun save() {
        prefs.edit { putString(GAME_KEY, encode(game)) }
    }

    private fun load(): DailyComprehensionGame? {
        val raw = prefs.getString(GAME_KEY, null) ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    internal fun encode(game: DailyComprehensionGame): String =
        JSONObject()
            .put("puzzleNumber", game.puzzleNumber)
            .put("chosenPosition", game.chosenPosition ?: JSONObject.NULL)
            .put("listens", game.listens)
            .toString()

    internal fun decode(raw: String): DailyComprehensionGame {
        val json = JSONObject(raw)
        return DailyComprehensionGame(
            puzzleNumber = json.getInt("puzzleNumber"),
            chosenPosition = if (json.isNull("chosenPosition")) null else json.getInt("chosenPosition"),
            listens = json.optInt("listens", 0)
        )
    }
}
