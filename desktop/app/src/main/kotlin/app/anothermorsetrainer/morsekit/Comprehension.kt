package app.anothermorsetrainer.morsekit

import java.time.LocalDate

/**
 * One authored question for **Daily Comprehension** (#353): a short sentence
 * sent in Morse, a question about it, the right answer and three wrong ones.
 *
 * [sentence] is upper-case A-Z and spaces only. [kochLevel] is 1 + the highest
 * position, in [MorseCode.kochOrder], of any letter in the sentence; it is
 * informational (docs/daily-comprehension-design.md).
 */
data class ComprehensionItem(
    val id: String,
    val sentence: String,
    val question: String,
    val answer: String,
    val distractors: List<String>,
    val kochLevel: Int
)

/** The question bank. The table is the generated `ComprehensionBank.kt`. */
object ComprehensionBank {
    val items: List<ComprehensionItem> get() = comprehensionBankItems
}

/** The four options in display order, and where the right one landed. */
data class ComprehensionOptions(val texts: List<String>, val correctPosition: Int)

/**
 * **Daily Comprehension** (#353): one sentence in Morse, the same for every
 * player on a given date, answered by choosing among four options rather than
 * by copying it exactly.
 *
 * Pure logic: no audio, no storage, no clock of its own. Selection mirrors
 * [DailyDit] (same epoch, same puzzle number, same coprime-stride walk) and is
 * pinned by `fixtures/comprehension-selection.json`.
 */
object DailyComprehension {
    /**
     * Walks the bank in steps of this size so consecutive days are not
     * consecutive entries. Coprime with the bank size (the fixture pins it),
     * so every item is used once before any repeats.
     */
    const val SELECTION_STRIDE = 53

    /** Every question has its answer plus three distractors. */
    const val OPTION_COUNT = 4

    private const val LCG_MULTIPLIER = 1664525L
    private const val LCG_INCREMENT = 1013904223L
    private const val MASK_32 = 0xFFFFFFFFL

    /** Which bank entry a puzzle number selects. */
    fun itemIndex(puzzleNumber: Int, itemCount: Int): Int {
        if (itemCount <= 0) return 0
        return ((maxOf(1, puzzleNumber) - 1) % itemCount) * SELECTION_STRIDE % itemCount
    }

    fun item(puzzleNumber: Int): ComprehensionItem {
        val bank = ComprehensionBank.items
        return bank[itemIndex(puzzleNumber, bank.size)]
    }

    /**
     * The order the four options are shown in, seeded by the puzzle number so
     * every player sees the same order. Element `k` indexes
     * `[answer, d1, d2, d3]` and is the option shown at position `k`.
     */
    fun optionOrder(puzzleNumber: Int): List<Int> {
        val order = IntArray(OPTION_COUNT) { it }
        var state = puzzleNumber.toLong() and MASK_32
        for (i in OPTION_COUNT - 1 downTo 1) {
            state = (state * LCG_MULTIPLIER + LCG_INCREMENT) and MASK_32
            val j = ((state shr 16) % (i + 1)).toInt()
            val t = order[i]; order[i] = order[j]; order[j] = t
        }
        return order.toList()
    }

    fun options(puzzleNumber: Int): ComprehensionOptions {
        val item = item(puzzleNumber)
        val pool = listOf(item.answer) + item.distractors
        val order = optionOrder(puzzleNumber)
        return ComprehensionOptions(order.map { pool[it] }, order.indexOf(0))
    }

    /** Puzzle number for a date: the same numbering as Daily Dit. */
    fun puzzleNumber(today: LocalDate = LocalDate.now()): Int = DailyDit.puzzleNumber(today)
}

/**
 * Today's Daily Comprehension: one answer, then it is over; listens are free
 * and counted. Immutable; every change returns a new game.
 */
data class DailyComprehensionGame(
    val puzzleNumber: Int,
    /** The position the player picked, null until they answer. */
    val chosenPosition: Int? = null,
    val listens: Int = 0
) {
    val item: ComprehensionItem get() = DailyComprehension.item(puzzleNumber)
    val options: List<String> get() = DailyComprehension.options(puzzleNumber).texts
    val correctPosition: Int get() = DailyComprehension.options(puzzleNumber).correctPosition
    val isFinished: Boolean get() = chosenPosition != null
    val isCorrect: Boolean get() = chosenPosition == correctPosition

    /** Count a play of the sentence. Replays after answering are free. */
    fun withListen(): DailyComprehensionGame = if (isFinished) this else copy(listens = listens + 1)

    /** Lock in an answer; null when the day is already answered or the position is out of range. */
    fun choose(position: Int): DailyComprehensionGame? =
        if (isFinished || position !in 0 until DailyComprehension.OPTION_COUNT) null
        else copy(chosenPosition = position)
}
