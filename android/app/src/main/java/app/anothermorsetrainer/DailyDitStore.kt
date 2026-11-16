package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.anothermorsetrainer.morsekit.DailyDit
import app.anothermorsetrainer.morsekit.DailyDitGame
import app.anothermorsetrainer.morsekit.DailyDitRound
import app.anothermorsetrainer.morsekit.DailyDitSubmission
import app.anothermorsetrainer.morsekit.DailyDitTile
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Holds and persists today's Daily Dit (#155).
 *
 * A process-wide singleton initialised in [MainActivity] alongside [Settings]
 * and [Stats]. The iOS app keeps the equivalent on `AppModel`, encoded with
 * `Codable` into UserDefaults; here the game is written as JSON with `org.json`
 * like [Stats] and [EngineStore], and exposed as Compose state so the home
 * card and the screen both recompose off one source.
 *
 * The whole game is saved on every accepted guess and every listen, not just
 * at the end: a puzzle abandoned halfway through and reopened must not hand
 * back a fresh ladder and a clean share text, which is exactly what "save on
 * completion" would do.
 */
object DailyDitStore {
    private lateinit var prefs: SharedPreferences

    private const val GAME_KEY = "game"
    private const val START_WPM_KEY = "startingWpm"
    private const val HIDE_REFERENCE_KEY = "hideReference"
    private const val HISTORY_KEY = "history"

    /**
     * The speed a puzzle starts at, before the ladder steps it down. 40 WPM by
     * default: fast enough that the ladder is the point, slow enough that a
     * first-timer isn't just staring at noise.
     */
    var startingWpm: Double = 40.0
        private set

    /** Play without the dit-dah chart on screen — the share text says so. */
    var hideReference: Boolean = false
        private set

    /**
     * Today's game. Starts as puzzle 0 so [refresh] always has something to
     * replace on first use.
     */
    var game by mutableStateOf(DailyDitGame(0, "", 40.0))
        private set

    /**
     * The days the history remembers (#333): every Daily Dit started in the
     * last [DailyDit.HISTORY_DAYS] days, by puzzle number, exactly as it was
     * left — today's included. Written only from [game] (in [save]); a past day
     * played from the history is practice, held by the screen and never
     * handed to this store, so practising a day can't change its result.
     * Pruned to the window on every write. Kept by a progress reset, like
     * today's game.
     */
    var history by mutableStateOf<Map<Int, DailyDitGame>>(emptyMap())
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("amt_dailydit", Context.MODE_PRIVATE)
        history = prefs.getString(HISTORY_KEY, null)?.let { decodeHistory(it) } ?: emptyMap()
        startingWpm = prefs.getFloat(START_WPM_KEY, 40f).toDouble()
        hideReference = prefs.getBoolean(HIDE_REFERENCE_KEY, false)
        refresh()
    }

    /**
     * Bring [game] up to date with the calendar: restore today's saved game, or
     * start a fresh one.
     *
     * Called on launch *and* whenever the home screen or the puzzle screen
     * appears, because the app can sit open across midnight — coming back to
     * yesterday's finished grid at breakfast is the bug this prevents.
     * Yesterday's game is not carried forward: it stays yesterday's, in
     * [history] (#333), where it can only be replayed as practice.
     */
    fun refresh(today: LocalDate = LocalDate.now()) {
        val number = DailyDit.puzzleNumber(today)
        if (game.puzzleNumber == number) return
        val saved = load()
        if (saved != null && saved.puzzleNumber == number) {
            game = saved
        } else {
            // A game left from an earlier day (the app wasn't opened since, or
            // was last saved by a version without the history) joins the
            // history before today's replaces it.
            if (saved != null) archive(saved, number)
            game = DailyDitGame.today(startingWpm, hideReference, today)
            save()
        }
    }

    /**
     * Set the starting speed and reference preference.
     *
     * Both are always stored as the preference for next time, but they only
     * reach *today's* game while no guess has been made. Once a guess is spent
     * the ladder is running, and re-basing it would let a player rewrite the
     * speed their share text claims; the same goes for turning the chart back
     * on after playing half a puzzle without it. The UI matches this — the card
     * carrying these controls is only composed before the first guess.
     *
     * Listens made before that first guess are kept (#168) — `copy` leaves
     * [DailyDitGame.heard] alone: they already stepped the ladder and were
     * heard at a speed, and the share text reports the *slowest* speed heard,
     * so raising the dial after a few listens can speed the next play up but
     * can never improve the brag.
     */
    fun configure(startingWpm: Double, hideReference: Boolean) {
        this.startingWpm = startingWpm
        this.hideReference = hideReference
        prefs.edit {
            putFloat(START_WPM_KEY, startingWpm.toFloat())
            putBoolean(HIDE_REFERENCE_KEY, hideReference)
        }
        if (game.guessesUsed > 0) return
        game = game.copy(startingWpm = startingWpm, hideReference = hideReference)
        save()
    }

    /**
     * Play the word. Every play counts (#168): the listen is recorded at the
     * speed it is sent at — and saved, since a relaunch must not forget it —
     * and that speed comes back for the player. Replays after the win are
     * free; [DailyDitGame.listen] leaves a finished game alone.
     */
    fun listen(): Double {
        val play = game.listen()
        if (play.game != game) {
            game = play.game
            save()
        }
        return play.wpm
    }

    /** Offer a guess. A rejected guess costs nothing and is not saved. */
    fun submit(word: String): DailyDitSubmission {
        val result = game.submit(word)
        if (result is DailyDitSubmission.Scored) {
            game = result.game
            save()
            Stats.recordPracticeDay()   // showing up for the daily puzzle is showing up
        }
        return result
    }

    // ---- Persistence -------------------------------------------------------

    private fun save() {
        prefs.edit { putString(GAME_KEY, encode(game)) }
        archive(game, DailyDit.puzzleNumber())
    }

    /** File a game in [history] as it stands, and persist it if that changed anything. */
    private fun archive(game: DailyDitGame, todayPuzzle: Int) {
        val next = archived(history, game, todayPuzzle)
        if (next == history) return
        history = next
        prefs.edit { putString(HISTORY_KEY, encodeHistory(next)) }
    }

    /**
     * [history] with [game] filed under its puzzle number and anything that
     * has aged out of the window dropped. A game never started is not a
     * result and is left out, so the history reads that day as missed.
     * Pure, so `DailyDitStoreCodecTest` can pin it without a store.
     */
    internal fun archived(
        history: Map<Int, DailyDitGame>,
        game: DailyDitGame,
        todayPuzzle: Int
    ): Map<Int, DailyDitGame> {
        val kept = history.filterKeys { DailyDit.isInHistory(it, todayPuzzle) }
        return if (game.hasStarted && DailyDit.isInHistory(game.puzzleNumber, todayPuzzle)) {
            kept + (game.puzzleNumber to game)
        } else {
            kept
        }
    }

    /** The history as a JSON array of [encode]d games, newest first. */
    internal fun encodeHistory(history: Map<Int, DailyDitGame>): String =
        JSONArray(history.values.sortedByDescending { it.puzzleNumber }.map { JSONObject(encode(it)) })
            .toString()

    /** One unreadable day is dropped, not the whole history. */
    internal fun decodeHistory(raw: String): Map<Int, DailyDitGame> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyMap()
        return (0 until array.length())
            .mapNotNull { i -> runCatching { decode(array.getJSONObject(i).toString()) }.getOrNull() }
            .associateBy { it.puzzleNumber }
    }

    private fun load(): DailyDitGame? {
        val raw = prefs.getString(GAME_KEY, null) ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    /**
     * Round-tripped by `DailyDitStoreCodecTest`. Tiles are stored by their
     * stable [DailyDitTile.key] rather than by ordinal, so reordering the enum
     * can't silently re-colour a saved grid.
     */
    internal fun encode(game: DailyDitGame): String {
        val rounds = JSONArray()
        for (round in game.rounds) {
            rounds.put(
                JSONObject()
                    .put("guess", round.guess)
                    .put("wpm", round.wpm)
                    .put("tiles", JSONArray(round.tiles.map { it.key }))
            )
        }
        return JSONObject()
            .put("puzzleNumber", game.puzzleNumber)
            .put("answer", game.answer)
            .put("startingWpm", game.startingWpm)
            .put("hideReference", game.hideReference)
            .put("rounds", rounds)
            .put("heard", JSONArray(game.heard))
            .toString()
    }

    internal fun decode(raw: String): DailyDitGame {
        val json = JSONObject(raw)
        val roundsJson = json.optJSONArray("rounds") ?: JSONArray()
        val rounds = ArrayList<DailyDitRound>(roundsJson.length())
        for (i in 0 until roundsJson.length()) {
            val r = roundsJson.getJSONObject(i)
            val tilesJson = r.optJSONArray("tiles") ?: JSONArray()
            val tiles = (0 until tilesJson.length()).mapNotNull {
                DailyDitTile.fromKey(tilesJson.getString(it))
            }
            rounds += DailyDitRound(r.getString("guess"), tiles, r.getDouble("wpm"))
        }
        // A game saved before #168 has no "heard": it restores as a game that
        // was never listened to, not as a failed decode and a fresh day.
        val heardJson = json.optJSONArray("heard") ?: JSONArray()
        val heard = (0 until heardJson.length()).map { heardJson.getDouble(it) }
        return DailyDitGame(
            puzzleNumber = json.getInt("puzzleNumber"),
            answer = json.getString("answer"),
            startingWpm = json.getDouble("startingWpm"),
            hideReference = json.optBoolean("hideReference", false),
            rounds = rounds,
            heard = heard
        )
    }
}
