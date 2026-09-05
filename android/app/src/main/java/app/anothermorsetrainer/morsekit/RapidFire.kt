package app.anothermorsetrainer.morsekit

import kotlin.random.Random

/**
 * What Rapid Fire streams, back to back. Each item is sent as plain text and the
 * learner copies it (typing, keying, or just reviewing the list at the end).
 *
 * Translated from MorseKit/RapidFire.swift.
 */
enum class RapidFireContent(val label: String, val blurb: String) {
    // Labels and blurbs are the iOS wording (RapidFireContent.label / .blurb), word for word.
    CALLSIGNS("Call signs", "Random call signs in the shapes you pick."),
    WORDS("Words", "Common words, within the length bounds you set."),
    NUMBERS("Number groups", "Random digit groups, N digits each."),
    STATES("State abbreviations", "Two-letter US state abbreviations — add the ARRL/RAC Field Day sections for three-letter ones like EPA and STX."),
    SERIALS("Serial numbers", "Contest serial numbers, 001–999, as a sprint or CWT station sends them."),
    NAMES("Names", "Short operator names heard in CW QSOs and contest exchanges."),
    POWER("Power", "Transmit power as sent on the air — 5W, 100W, 1KW."),
    MIXED("Mixed", "Call signs, words, number groups and states, shuffled.")
}

/** How the learner copies a Rapid Fire stream. */
enum class RapidFireResponse(val label: String, val blurb: String) {
    // Blurbs are the iOS wording (AppSettings.swift, `RapidFireResponse.blurb`), word for word.
    TYPE("Type as you hear it", "Type into the box as each item is sent — the field stays live while it plays, like the QSO simulator."),
    HEAD_COPY("Head copy, then type", "Hold each item in your head, then type it once the code finishes — the box stays hidden until then. Builds true head copy."),
    KEY("Key each one", "Send each item back on a hardware or on-screen key; it’s decoded and checked."),
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
        val numberCount: Int = 5,
        /**
         * Widen the [RapidFireContent.STATES] pool with the ARRL/RAC section
         * abbreviations ([ContestData.arrlSections]), so EPA, STX and SDG turn up beside OH.
         */
        val statesIncludeSections: Boolean = false,
        /**
         * Send [RapidFireContent.SERIALS] with cut numbers (T for 0, N for 9 …), the way
         * the pileup does. Either form is accepted as the copy regardless.
         */
        val serialCutNumbers: Boolean = false
    )

    private val wordPool: List<String>
    /** States, plus the ARRL/RAC sections when enabled (each abbreviation once). */
    private val statePool: List<String>
    private var lastAnswer = ""
    /**
     * The last item was a serial number, so its copy is graded numerically (cut
     * letters decoded, leading zeros ignored) rather than by text.
     */
    private var lastIsSerial = false

    init {
        val lo = maxOf(1, minOf(config.wordMinLength, config.wordMaxLength))
        val hi = maxOf(lo, config.wordMaxLength)
        val filtered = MorseData.rankedWords.filter { it.length in lo..hi }
        wordPool = filtered.ifEmpty { MorseData.rankedWords }
        statePool = statePool(includeSections = config.statesIncludeSections)
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
            RapidFireContent.STATES -> if (config.statesIncludeSections) "States & sections" else "State abbreviations"
            RapidFireContent.SERIALS -> if (config.serialCutNumbers) "Serial numbers (cut)" else "Serial numbers"
            RapidFireContent.NAMES -> "Names"
            RapidFireContent.POWER -> "Power levels"
            RapidFireContent.MIXED -> "Mixed copy"
        }

    override fun nextDrill(): Drill {
        lastIsSerial = config.content == RapidFireContent.SERIALS
        val text = generate()
        lastAnswer = text
        // A cut serial is *sent* as letters but *means* the digits: the answer
        // and the reveal are the true value (as the pileup logs it), and the
        // sent form rides along as the secondary line.
        val sent = if (lastIsSerial && config.serialCutNumbers) {
            CutNumbers.encode(text, CutNumbers.cuttableDigits.toSet())
        } else {
            text
        }
        // Free recall: a single "option" (the answer) keeps the Drill valid for
        // the shared loop; the Rapid Fire UI never shows a choice grid.
        return Drill(
            playable = MorseItem.Playable.Text(sent),
            options = listOf(text),
            correct = text,
            revealPrimary = text,
            revealSecondary = if (sent == text) "" else "sent as $sent"
        )
    }

    override fun record(choice: String, ttr: Double): DrillOutcome =
        DrillOutcome(correct = matches(choice, lastAnswer, lastIsSerial), unlocked = null)

    // ---- Generation ----

    private fun generate(): String = when (config.content) {
        RapidFireContent.CALLSIGNS -> makeCallsign()
        RapidFireContent.WORDS -> wordPool.randomOrNull(rng) ?: "THE"
        RapidFireContent.NUMBERS -> makeNumberGroup()
        RapidFireContent.STATES -> statePool.randomOrNull(rng) ?: "OH"
        RapidFireContent.SERIALS -> makeSerial()
        RapidFireContent.NAMES -> MorseData.opNames.randomOrNull(rng) ?: "JIM"
        RapidFireContent.POWER -> ExamData.powers.randomOrNull(rng) ?: "100W"
        RapidFireContent.MIXED -> makeMixed()
    }

    /**
     * A contest serial, 001–999, zero-padded to three digits the way the pileup's
     * basic contest sends it. Cut numbers are applied when sending, not here:
     * this is the true value.
     */
    private fun makeSerial(): String = rng.nextInt(1, 1000).toString().padStart(3, '0')

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
        else -> statePool.randomOrNull(rng) ?: "OH"
    }

    companion object {
        /** Case- and space-insensitive comparison, so "K1 ABC" copies as "K1ABC". */
        fun normalize(s: String): String = s.uppercase().filter { !it.isWhitespace() }

        /**
         * Grade one copy. A serial is compared as a number — "TTA", "001" and "1"
         * all copy 001, as the pileup accepts them — everything else as text.
         */
        fun matches(choice: String, answer: String, serial: Boolean): Boolean {
            if (serial) {
                val a = CutNumbers.decodeDigits(choice).toIntOrNull()
                val b = answer.toIntOrNull()
                if (a != null && b != null) return a == b
            }
            return normalize(choice) == normalize(answer)
        }

        /**
         * The [RapidFireContent.STATES] pool: the 51 state abbreviations, widened
         * with the ARRL/RAC sections when asked. Sections that are also states
         * (CT, OH …) appear once.
         */
        fun statePool(includeSections: Boolean): List<String> {
            if (!includeSections) return MorseData.usStates
            val states = MorseData.usStates.toSet()
            return MorseData.usStates + ContestData.arrlSections.filter { it !in states }
        }
    }
}
