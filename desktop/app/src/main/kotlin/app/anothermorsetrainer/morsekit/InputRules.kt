package app.anothermorsetrainer.morsekit

/**
 * Small pure rules behind the input settings that every app must agree on,
 * pinned by `fixtures/gap-input-settings-rules.json`. The iOS twin is
 * `MorseKit/InputRules.swift`; nothing is shared but that fixture.
 */
object InputRules {

    // ---- Side tone and tone spread (parity row 18) ----------------------

    const val SIDETONE_MIN = 300.0
    const val SIDETONE_MAX = 1000.0
    const val SIDETONE_STEP = 10.0

    const val TONE_SPREAD_MIN = 0.0
    const val TONE_SPREAD_MAX = 500.0
    const val TONE_SPREAD_STEP = 10.0

    /**
     * Compose counts a slider's `steps` as the stops *between* its two ends,
     * so a range walked in [step] increments has one fewer than it spans.
     */
    fun composeSteps(min: Double, max: Double, step: Double): Int =
        maxOf(0, Math.round((max - min) / step).toInt() - 1)

    val SIDETONE_COMPOSE_STEPS: Int get() = composeSteps(SIDETONE_MIN, SIDETONE_MAX, SIDETONE_STEP)
    val TONE_SPREAD_COMPOSE_STEPS: Int get() = composeSteps(TONE_SPREAD_MIN, TONE_SPREAD_MAX, TONE_SPREAD_STEP)

    /** A stored side tone pulled into the slider's range. */
    fun clampSidetone(hz: Double): Double = hz.coerceIn(SIDETONE_MIN, SIDETONE_MAX)

    fun clampToneSpread(hz: Double): Double = hz.coerceIn(TONE_SPREAD_MIN, TONE_SPREAD_MAX)

    /** The slider reads "Zero-beat" below one step. */
    fun isZeroBeat(spread: Double): Boolean = spread < TONE_SPREAD_STEP

    // ---- Pileup speed and wait (parity row 19) ---------------------------

    const val CALLER_WPM_MIN = 12.0
    const val CALLER_WPM_MAX = 60.0
    const val MIN_WAIT_MAX = 3.0
    const val MAX_WAIT_MAX = 4.0

    fun clampCallerWpm(wpm: Double): Double = wpm.coerceIn(CALLER_WPM_MIN, CALLER_WPM_MAX)
    fun clampMinWait(s: Double): Double = s.coerceIn(0.0, MIN_WAIT_MAX)
    fun clampMaxWait(s: Double): Double = s.coerceIn(0.0, MAX_WAIT_MAX)

    /** The speed band callers are drawn from: the pair in sorted order. */
    fun effectiveSpeedRange(min: Double, max: Double): Pair<Double, Double> =
        minOf(min, max) to maxOf(min, max)

    /** The reply-wait window: min wait to the larger of the two. */
    fun effectiveWaitRange(min: Double, max: Double): Pair<Double, Double> =
        min to maxOf(min, max)

    // ---- Your callsign (parity row 20) -----------------------------------

    const val CALLSIGN_PLACEHOLDER = "W1AW"
    const val CALLSIGN_MAX_LENGTH = 12

    /**
     * What the callsign field keeps of what was typed: upper-cased, only
     * A-Z, 0-9 and "/", at most 12 characters. May be empty.
     */
    fun normalizeCallsign(input: String): String =
        input.uppercase()
            .filter { it in 'A'..'Z' || it in '0'..'9' || it == '/' }
            .take(CALLSIGN_MAX_LENGTH)

    /** The value stored for what was typed: [normalizeCallsign], with a blank result becoming W1AW. */
    fun storedCallsign(input: String): String =
        normalizeCallsign(input).ifEmpty { CALLSIGN_PLACEHOLDER }
}
