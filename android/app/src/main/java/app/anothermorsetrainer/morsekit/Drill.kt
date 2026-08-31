package app.anothermorsetrainer.morsekit

/**
 * One multiple-choice round, in a form the UI can render regardless of which
 * quiz mode produced it (characters, words, abbreviations, prosigns).
 *
 * Translated from MorseKit/Drill.swift.
 */
data class Drill(
    /** What to sound out in Morse. */
    val playable: MorseItem.Playable,
    /** The answer choices shown on the buttons (includes the correct one). */
    val options: List<String>,
    /** The correct choice. */
    val correct: String,
    /** Big text shown when revealing (e.g. "X", "ES", "<AR>"). */
    val revealPrimary: String,
    /** Smaller supporting text on reveal (e.g. the dot-dash pattern or meaning). */
    val revealSecondary: String,
    /**
     * Optional question/context shown above the choices (e.g. the QSO
     * simulator's "What's their name?"). Empty for simple recognition drills.
     */
    val question: String = ""
)

/** Result of answering a drill. */
data class DrillOutcome(
    val correct: Boolean,
    /** A newly unlocked item (character/word), if answering triggered progression. */
    val unlocked: String?
)

/**
 * Anything that can drive the quiz loop: hand out drills and record answers.
 * (Swift `protocol QuizSource: AnyObject` → Kotlin `interface`.)
 */
interface QuizSource {
    fun nextDrill(): Drill
    fun record(choice: String, ttr: Double): DrillOutcome
    /** Short status for the toolbar (e.g. "12 letters", "70 abbreviations"). */
    val summary: String
}
