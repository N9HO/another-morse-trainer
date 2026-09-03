package app.anothermorsetrainer.morsekit

import java.time.LocalDate
import java.util.Locale
import kotlin.math.floor

/**
 * **Daily Dit** — one five-letter word, sent in Morse, the same for every
 * player on a given date.
 *
 * The shape of the game:
 *
 *  - Tap play to hear the day's word. Replays are free; guesses are the
 *    currency.
 *  - Up to [MAX_GUESSES] guesses, each a real five-letter word.
 *  - Every guess is scored per letter — right letter/right place, right
 *    letter/wrong place, or not in the word at all.
 *  - The code starts at whatever speed you chose (QRQ territory if you like)
 *    and steps down [SPEED_STEP_WPM] every [GUESSES_PER_SPEED_STEP] guesses, so
 *    a word you can't catch at 75 WPM comes back within reach. What you brag
 *    about is the speed you were still at when you got it.
 *
 * Pure logic: no audio, no storage, no clock of its own — the caller passes the
 * date in. Translated from MorseKit/DailyDit.swift, and pinned to the same
 * `fixtures/daily-dit.json` the Swift harness reads, because a *shared* daily
 * puzzle is only shared if both ports agree on the word.
 *
 * Kotlin differences from the Swift twin, all idiom rather than behaviour:
 * Swift's `mutating func submit` on a struct becomes a [submit] that returns
 * the next [DailyDitGame] inside a [DailyDitSubmission], and Swift's `Codable`
 * conformance is not here — persistence is the app layer's job, as it is for
 * `EngineStore` and `Stats`.
 */
object DailyDit {

    // MARK: Rules

    /** Total guesses allowed in a day. */
    const val MAX_GUESSES = 21

    /** Guesses between one speed step and the next. */
    const val GUESSES_PER_SPEED_STEP = 3

    /** How much the speed drops at each step. */
    const val SPEED_STEP_WPM = 5.0

    /** The ladder never goes below this, however many guesses are spent. */
    const val MINIMUM_WPM = 10.0

    /** Every answer and every guess is this long. */
    const val WORD_LENGTH = 5

    /** Where the share text sends people. Our own page, per the brief. */
    const val SHARE_LINK = "anothermorsetrainer.app"

    /**
     * Speeds offered at the start of a puzzle, up to the 75 WPM the mode was
     * asked for. Ordered easiest first.
     */
    val startingSpeeds: List<Double> = listOf(20.0, 25.0, 30.0, 40.0, 50.0, 60.0, 75.0)

    /**
     * Puzzle #1 is this civil date. Fixed forever: moving it renumbers every
     * puzzle and changes which word every past share text refers to.
     */
    val epoch: LocalDate = LocalDate.of(2026, 1, 1)

    /**
     * Walks the answer list in steps of this size instead of straight through,
     * so consecutive days aren't consecutive dictionary entries.
     *
     * It is prime, and the answer count (800) factors as 2⁵·5², so the two are
     * coprime — which is the point: a stride coprime with the list length
     * visits every word exactly once before repeating any of them. Sharing a
     * factor would trap the puzzle in a short cycle, so the fixture pins the
     * coprimality rather than trusting whoever next regenerates the words.
     */
    const val SELECTION_STRIDE = 389

    // MARK: Which word, which day

    /**
     * Days from 1970-01-01 to the given proleptic-Gregorian civil date.
     *
     * Howard Hinnant's `days_from_civil`. [LocalDate.toEpochDay] would answer
     * the same question, and the test pins that it does — but the arithmetic is
     * written out here so this port and the Swift one are demonstrably running
     * the same algorithm rather than two platform calendars that happen to
     * agree today.
     */
    fun daysFromCivil(year: Int, month: Int, day: Int): Int {
        val y = year - if (month <= 2) 1 else 0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400                                              // [0, 399]
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1  // [0, 365]
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy                      // [0, 146096]
        return era * 146097 + doe - 719468
    }

    /**
     * The puzzle number for a civil date. Puzzle #1 is [epoch]; dates before it
     * clamp to #1 rather than going negative.
     */
    fun puzzleNumber(year: Int, month: Int, day: Int): Int {
        val today = daysFromCivil(year, month, day)
        val start = daysFromCivil(epoch.year, epoch.monthValue, epoch.dayOfMonth)
        return maxOf(1, today - start + 1)
    }

    /**
     * The puzzle number for a date. Local date, not UTC: a player's "today" is
     * the one on their own wall, and everyone playing on the 3rd gets the 3rd's
     * word.
     */
    fun puzzleNumber(today: LocalDate = LocalDate.now()): Int =
        puzzleNumber(today.year, today.monthValue, today.dayOfMonth)

    /** The answer for a puzzle number. */
    fun answer(puzzleNumber: Int): String {
        val pool = MorseData.dailyDitAnswers
        if (pool.isEmpty()) return ""
        // (number - 1) so puzzle #1 is the first step, not a no-op.
        val index = ((puzzleNumber - 1) % pool.size) * SELECTION_STRIDE % pool.size
        return pool[index]
    }

    /** The answer for a date. */
    fun answer(today: LocalDate = LocalDate.now()): String = answer(puzzleNumber(today))

    // MARK: Guess validation

    /**
     * The answer pool is folded in, so the day's word is always guessable even
     * if the corpus that built the allowed list moves under us. Built once, on
     * first use — 15,930 strings is not a thing to hash on every keystroke.
     */
    private val allowedSet: Set<String> by lazy {
        MorseData.dailyDitAllowed.toSet() + MorseData.dailyDitAnswers.toSet()
    }

    /** Is this a word we accept as a guess? Case-insensitive. */
    fun isAllowedGuess(word: String): Boolean = normalize(word) in allowedSet

    /** Upper-cased and stripped of anything that isn't a letter. */
    fun normalize(word: String): String = word.uppercase().filter { it.isLetter() }

    // MARK: Scoring

    /**
     * Score a guess against the answer.
     *
     * Two passes, and the second pass is the reason: exact matches are claimed
     * first, then each remaining guess letter can only be marked
     * [DailyDitTile.PRESENT] if an *unclaimed* copy of it is left in the
     * answer. One pass would tell a player guessing SPEED against SPEND that
     * both Es are somewhere in the word, when only one is.
     */
    fun score(guess: String, answer: String): List<DailyDitTile> {
        val g = normalize(guess)
        val a = normalize(answer)
        if (g.length != a.length) return emptyList()

        val tiles = MutableList(g.length) { DailyDitTile.ABSENT }
        val unclaimed = HashMap<Char, Int>()

        for (i in g.indices) {
            if (g[i] == a[i]) tiles[i] = DailyDitTile.CORRECT
            else unclaimed[a[i]] = (unclaimed[a[i]] ?: 0) + 1
        }
        for (i in g.indices) {
            if (tiles[i] == DailyDitTile.CORRECT) continue
            val left = unclaimed[g[i]] ?: 0
            if (left > 0) {
                tiles[i] = DailyDitTile.PRESENT
                unclaimed[g[i]] = left - 1
            }
        }
        return tiles
    }

    // MARK: The speed ladder

    /** The speed the next word is sent at, having spent [guessesUsed] guesses. */
    fun wpm(startingWpm: Double, guessesUsed: Int): Double {
        val steps = maxOf(0, guessesUsed) / GUESSES_PER_SPEED_STEP
        return maxOf(MINIMUM_WPM, startingWpm - SPEED_STEP_WPM * steps)
    }

    /**
     * Speeds are whole numbers in practice; don't print "60.0 WPM".
     *
     * [Locale.US] rather than the default: this string goes into the share
     * text, which is pinned to a fixture and compared against the Swift port. A
     * device set to a comma-decimal locale would otherwise brag "37,5 WPM".
     */
    fun formatWpm(wpm: Double): String =
        if (wpm == floor(wpm)) wpm.toInt().toString() else String.format(Locale.US, "%.1f", wpm)
}

/** How one letter of a guess came out. */
enum class DailyDitTile(
    /** The fixture's name for this tile, and the key persistence writes. */
    val key: String,
    /** The square used in the shareable grid. */
    val emoji: String
) {
    /** Right letter, right place. */
    CORRECT("correct", "🟩"),

    /** The word contains this letter, but not here. */
    PRESENT("present", "🟨"),

    /** Not in the word (or not in it this many times). */
    ABSENT("absent", "⬜");

    companion object {
        fun fromKey(key: String): DailyDitTile? = entries.firstOrNull { it.key == key }
    }
}

/** One scored guess, and the speed the word was being sent at when it was made. */
data class DailyDitRound(
    val guess: String,
    val tiles: List<DailyDitTile>,
    val wpm: Double
) {
    val solved: Boolean
        get() = tiles.isNotEmpty() && tiles.all { it == DailyDitTile.CORRECT }
}

/** Where a day's game stands. */
enum class DailyDitOutcome(val key: String) {
    PLAYING("playing"), SOLVED("solved"), LOST("lost")
}

/** Why a guess wasn't scored. Each one is a message the UI shows as-is. */
enum class DailyDitRejection(val message: String) {
    /** Not five letters. */
    WRONG_LENGTH("Five letters."),

    /** Five letters, but not a word we know. */
    NOT_A_WORD("Not in the word list."),

    /** The day is already won or lost. */
    FINISHED("Today's Daily Dit is done.")
}

/**
 * The result of offering a guess. [Scored] carries the game *after* the guess —
 * [DailyDitGame] is immutable, so the caller swaps its state for this one.
 */
sealed class DailyDitSubmission {
    data class Scored(val game: DailyDitGame, val round: DailyDitRound) : DailyDitSubmission()
    data class Rejected(val reason: DailyDitRejection) : DailyDitSubmission()
}

/**
 * A single day's play, from first guess to share text.
 *
 * Immutable on purpose: it is the *whole* saved state of the day, so persisting
 * a game is writing this and nothing else, and reloading it can't half-restore.
 * Repeating a guess you've already made is deliberately legal — guesses buy
 * speed steps, so spending one to drag the code slower is a real tactic, not a
 * mistake to guard against.
 */
data class DailyDitGame(
    val puzzleNumber: Int,
    /** The day's word. Compared case-insensitively, so its case doesn't matter. */
    val answer: String,
    /** The speed the first guess was played at. */
    val startingWpm: Double,
    /**
     * Playing without the dit-dah chart on screen. Recorded here because it
     * belongs in the share text — the logic doesn't care.
     */
    val hideReference: Boolean = false,
    val rounds: List<DailyDitRound> = emptyList()
) {
    val guessesUsed: Int get() = rounds.size
    val guessesLeft: Int get() = maxOf(0, DailyDit.MAX_GUESSES - guessesUsed)

    /** The speed the word plays at right now. */
    val currentWpm: Double get() = DailyDit.wpm(startingWpm, guessesUsed)

    /** The speed the winning guess was made at — what the brag sheet reports. */
    val solvedWpm: Double? get() = rounds.lastOrNull { it.solved }?.wpm

    val outcome: DailyDitOutcome
        get() = when {
            rounds.any { it.solved } -> DailyDitOutcome.SOLVED
            guessesUsed >= DailyDit.MAX_GUESSES -> DailyDitOutcome.LOST
            else -> DailyDitOutcome.PLAYING
        }

    val isFinished: Boolean get() = outcome != DailyDitOutcome.PLAYING

    /** Score a guess and, if it's a real one, spend a guess on it. */
    fun submit(raw: String): DailyDitSubmission {
        if (isFinished) return DailyDitSubmission.Rejected(DailyDitRejection.FINISHED)
        val word = DailyDit.normalize(raw)
        if (word.length != DailyDit.WORD_LENGTH) {
            return DailyDitSubmission.Rejected(DailyDitRejection.WRONG_LENGTH)
        }
        if (!DailyDit.isAllowedGuess(word)) {
            return DailyDitSubmission.Rejected(DailyDitRejection.NOT_A_WORD)
        }
        // Read the speed *before* appending: a guess is played at the speed it
        // was made at, and only the next one feels the step down.
        val round = DailyDitRound(word, DailyDit.score(word, answer), currentWpm)
        return DailyDitSubmission.Scored(copy(rounds = rounds + round), round)
    }

    /**
     * Letters ruled out so far, for greying the on-screen keyboard. A letter
     * only appears here if no guess has ever placed it in the word.
     */
    val eliminatedLetters: Set<Char>
        get() {
            val seen = HashSet<Char>()
            val found = HashSet<Char>()
            for (round in rounds) {
                round.guess.forEachIndexed { i, letter ->
                    seen += letter
                    if (round.tiles.getOrNull(i) != DailyDitTile.ABSENT) found += letter
                }
            }
            return seen - found
        }

    /** "Daily Dit #245 — 4/21 at 60 WPM" */
    val headline: String
        get() {
            var line = "Daily Dit #$puzzleNumber — "
            line += when (outcome) {
                DailyDitOutcome.SOLVED -> {
                    val at = solvedWpm?.let { " at ${DailyDit.formatWpm(it)} WPM" } ?: ""
                    "$guessesUsed/${DailyDit.MAX_GUESSES}$at"
                }
                DailyDitOutcome.LOST -> "X/${DailyDit.MAX_GUESSES}"
                DailyDitOutcome.PLAYING -> "$guessesUsed/${DailyDit.MAX_GUESSES} so far"
            }
            if (hideReference) line += " (no reference)"
            return line
        }

    /**
     * The pasteable brag sheet: a headline, the emoji grid, and where to play.
     *
     * Text, not an image, because it has to survive being pasted into a chat
     * window. The grid is every guess — a long grid is the story of a hard day,
     * and trimming it would misreport the score in the headline.
     */
    val shareText: String
        get() = buildList {
            add(headline)
            rounds.forEach { round -> add(round.tiles.joinToString("") { it.emoji }) }
            add(DailyDit.SHARE_LINK)
        }.joinToString("\n")

    companion object {
        /** Today's game, ready to play. */
        fun today(
            startingWpm: Double,
            hideReference: Boolean = false,
            today: LocalDate = LocalDate.now()
        ): DailyDitGame {
            val number = DailyDit.puzzleNumber(today)
            return DailyDitGame(
                puzzleNumber = number,
                answer = DailyDit.answer(number),
                startingWpm = startingWpm,
                hideReference = hideReference
            )
        }
    }
}
