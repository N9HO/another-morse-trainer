package app.anothermorsetrainer.morsekit

import kotlin.random.Random

/**
 * What Rapid Fire streams, back to back. Each item is sent as plain text and the
 * learner copies it (typing, keying, or just reviewing the list at the end).
 *
 * Translated from MorseKit/RapidFire.swift.
 */
enum class RapidFireContent(val label: String) {
    CALLSIGNS("Call signs"),
    WORDS("Words"),
    NUMBERS("Number groups"),
    STATES("State abbreviations"),
    MIXED("Mixed")
}

/** How the learner copies a Rapid Fire stream. */
enum class RapidFireResponse(val label: String, val blurb: String) {
    TYPE("Type as you hear it", "Type into the box as each item is sent — the field stays live while it plays."),
    HEAD_COPY("Head copy, then type", "Hold each item in your head, then type it once the code finishes. Builds true head copy."),
    REVIEW("Just listen", "Copy on paper or in your head — then review the full list of what was sent when you finish.")
}

/** How quickly Rapid Fire moves on to the next item. */
enum class RapidFirePace(val seconds: Double, val label: String) {
    RELAXED(2.0, "Relaxed (2.0 s)"),
    STEADY(1.2, "Steady (1.2 s)"),
    BRISK(0.6, "Brisk (0.6 s)"),
    BLAZING(0.25, "Blazing (0.25 s)")
}

/**
 * A streaming free-recall quiz: it hands out one generated item at a time (a
 * call sign, word, number group, or state) and grades a typed copy of it. Pure
 * logic — seedable for tests, no audio or UI. Drives the same quiz loop as the
 * other modes via [QuizSource].
 */
class RapidFireQuiz(
    val config: Config,
    private val rng: Random = Random.Default
) : QuizSource {

    data class Config(
        val content: RapidFireContent = RapidFireContent.CALLSIGNS,
        /** Call-sign shapes to draw from (1×2, 2×1, …). Empty falls back to the common defaults. */
        val callsignFormats: List<CallsignFormat> = CallsignFormat.commonDefaults,
        val callsignUSOnly: Boolean = true,
        /** Inclusive word-length bounds for [RapidFireContent.WORDS]. */
        val wordMinLength: Int = 3,
        val wordMaxLength: Int = 6,
        /** How many digits in each [RapidFireContent.NUMBERS] group. */
        val numberCount: Int = 5
    )

    private val wordPool: List<String>
    private var lastAnswer = ""

    init {
        val lo = maxOf(1, minOf(config.wordMinLength, config.wordMaxLength))
        val hi = maxOf(lo, config.wordMaxLength)
        val filtered = MorseData.rankedWords.filter { it.length in lo..hi }
        wordPool = filtered.ifEmpty { MorseData.rankedWords }
    }

    // ---- QuizSource ----

    override val summary: String
        get() = when (config.content) {
            RapidFireContent.CALLSIGNS -> "Call signs"
            RapidFireContent.WORDS -> {
                val lo = maxOf(1, minOf(config.wordMinLength, config.wordMaxLength))
                val hi = maxOf(lo, config.wordMaxLength)
                if (lo == hi) "$lo-letter words" else "Words $lo–$hi letters"
            }
            RapidFireContent.NUMBERS -> "${maxOf(1, config.numberCount)}-digit numbers"
            RapidFireContent.STATES -> "State abbreviations"
            RapidFireContent.MIXED -> "Mixed copy"
        }

    override fun nextDrill(): Drill {
        val text = generate()
        lastAnswer = text
        // Free recall: a single "option" (the answer) keeps the Drill valid for
        // the shared loop; the Rapid Fire UI never shows a choice grid.
        return Drill(
            playable = MorseItem.Playable.Text(text),
            options = listOf(text),
            correct = text,
            revealPrimary = text,
            revealSecondary = ""
        )
    }

    override fun record(choice: String, ttr: Double): DrillOutcome =
        DrillOutcome(correct = normalize(choice) == normalize(lastAnswer), unlocked = null)

    // ---- Generation ----

    private fun generate(): String = when (config.content) {
        RapidFireContent.CALLSIGNS -> makeCallsign()
        RapidFireContent.WORDS -> wordPool.randomOrNull(rng) ?: "THE"
        RapidFireContent.NUMBERS -> makeNumberGroup()
        RapidFireContent.STATES -> MorseData.usStates.randomOrNull(rng) ?: "OH"
        RapidFireContent.MIXED -> makeMixed()
    }

    private fun makeCallsign(): String {
        val formats = config.callsignFormats.ifEmpty { CallsignFormat.commonDefaults }
        return CallsignGenerator.generate(formats = formats, usOnly = config.callsignUSOnly, rng = rng)
    }

    private fun makeNumberGroup(): String {
        val n = maxOf(1, config.numberCount)
        return (0 until n).map { ('0' + rng.nextInt(10)) }.joinToString("")
    }

    private fun makeMixed(): String = when (rng.nextInt(4)) {
        0 -> makeCallsign()
        1 -> wordPool.randomOrNull(rng) ?: "THE"
        2 -> makeNumberGroup()
        else -> MorseData.usStates.randomOrNull(rng) ?: "OH"
    }

    companion object {
        /** Case- and space-insensitive comparison, so "K1 ABC" copies as "K1ABC". */
        fun normalize(s: String): String = s.uppercase().filter { !it.isWhitespace() }
    }
}
