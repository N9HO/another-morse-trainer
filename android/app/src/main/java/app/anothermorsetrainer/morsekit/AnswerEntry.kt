package app.anothermorsetrainer.morsekit

/**
 * How the choice drills (Characters, Words, Abbreviations, Q-Codes, Prosigns,
 * Confusion Drill) take an answer (#232). Multiple choice narrows the answer
 * space and lets a learner eliminate and guess; typing the answer asks for
 * full recall, the closer match to real copying, so it is offered as the next
 * difficulty tier rather than as a separate mode. Twin of the Swift
 * `AnswerEntryMode`.
 */
enum class AnswerEntryMode(val id: String) {
    /** Tap one of the offered answers — the default; the count is the Answer choices setting. */
    CHOICES("choices"),
    /** Climb the per-level ladder: four choices, six, then typed ([AnswerEntryLadder]). */
    PROGRESSIVE("progressive"),
    /** Always type the answer: the manual jump ahead. */
    TYPED("typed");

    companion object {
        fun fromId(id: String?): AnswerEntryMode = entries.firstOrNull { it.id == id } ?: CHOICES
    }
}

/** One rung of the answer-entry ladder. */
enum class AnswerEntryTier(val id: String, val choiceCount: Int?) {
    FOUR_CHOICES("fourChoices", 4),
    SIX_CHOICES("sixChoices", 6),
    TYPED("typed", null);

    /** The rung above this one; null at the top. */
    val next: AnswerEntryTier?
        get() = when (this) {
            FOUR_CHOICES -> SIX_CHOICES
            SIX_CHOICES -> TYPED
            TYPED -> null
        }

    companion object {
        fun fromId(id: String?): AnswerEntryTier = entries.firstOrNull { it.id == id } ?: FOUR_CHOICES
    }
}

/**
 * Per-level progression of how an answer is given (#232):
 * four choices → six choices → typed.
 *
 * A rung is cleared by [REQUIRED_CORRECT] of the last [WINDOW] answers given on
 * it; the window is emptied on each promotion, so every rung is earned on its
 * own answers. A new level — a character added to the Koch ladder, or a new
 * Characters stage — starts again at four choices: multiple choice is the
 * scaffold for something new. There is no demotion; a learner who wants the
 * choices back switches the Answer entry setting. Pinned for both ports by
 * `fixtures/answer-entry.json`; twin of the Swift `AnswerEntryLadder`.
 */
class AnswerEntryLadder(
    tier: AnswerEntryTier = AnswerEntryTier.FOUR_CHOICES,
    recent: List<Boolean> = emptyList()
) {
    var tier: AnswerEntryTier = tier
        private set

    private val window: ArrayDeque<Boolean> = ArrayDeque(recent.takeLast(WINDOW))

    /** Results on the current rung, oldest first, at most [WINDOW] long. */
    val recent: List<Boolean> get() = window.toList()

    /**
     * Record one answer given on the current rung. Returns the rung this
     * answer promoted to, or null when it did not promote.
     */
    fun record(correct: Boolean): AnswerEntryTier? {
        val next = tier.next ?: return null   // typed is the top
        window.addLast(correct)
        while (window.size > WINDOW) window.removeFirst()
        if (window.size < WINDOW || window.count { it } < REQUIRED_CORRECT) return null
        tier = next
        window.clear()
        return next
    }

    /** A new level began: back to the first rung with an empty window. */
    fun restartLevel() {
        tier = AnswerEntryTier.FOUR_CHOICES
        window.clear()
    }

    override fun equals(other: Any?): Boolean =
        other is AnswerEntryLadder && other.tier == tier && other.recent == recent

    override fun hashCode(): Int = tier.hashCode() * 31 + recent.hashCode()

    companion object {
        const val WINDOW = 20
        const val REQUIRED_CORRECT = 18
    }
}

/** Grading of a typed (or keyed) answer (#232). Twin of the Swift `TypedAnswer`. */
object TypedAnswer {

    private val whitespace = Regex("\\s+")

    /**
     * What was typed, in the form answers are compared in: surrounding
     * whitespace trimmed, runs of inner whitespace collapsed to one space,
     * upper case, and a slashed zero (Ø) read as the digit it stands for.
     */
    fun normalize(text: String): String =
        text.uppercase().replace('Ø', '0').trim().split(whitespace).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * How an answer to a single-character drill is scored. [confusedWith] is
     * the character given in place of the target — the confusion to record —
     * or null: for a correct answer, and for one that is not a single Morse
     * character (a blank "don't know", two characters, a symbol Morse has no
     * code for). Those are misses with no partner, so a typing slip never lands
     * in the Confusion Matrix as a sound-alike.
     */
    data class CharacterGrade(val correct: Boolean, val confusedWith: Char?)

    fun gradeCharacter(answer: String, target: Char): CharacterGrade {
        val given = normalize(answer)
        if (given == target.toString()) return CharacterGrade(true, null)
        if (given.length != 1 || MorseCode.pattern(given[0]) == null) return CharacterGrade(false, null)
        return CharacterGrade(false, given[0])
    }
}
