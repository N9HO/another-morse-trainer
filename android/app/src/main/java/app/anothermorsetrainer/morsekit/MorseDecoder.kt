package app.anothermorsetrainer.morsekit

/**
 * Turns keyed timing back into text — the inverse of `MorsePlayer`.
 *
 * A real key (or the on-screen key) gives us *tone* durations (key down → key
 * up) and *gap* durations (key up → next key down). This classifies each tone
 * as a dit or dah and each gap as an intra-character, letter, or word boundary,
 * then looks the assembled elements up in [MorseCode].
 *
 * The unit (dit) length is **adaptive**: seeded from a nominal WPM and nudged
 * toward what the operator is actually sending, so a human who speeds up or
 * slows down mid-session still decodes. Classic CW proportions are assumed:
 * dit = 1 unit, dah = 3, element gap = 1, letter gap = 3, word gap = 7.
 *
 * Translated from MorseKit/MorseDecoder.swift.
 */
class MorseDecoder(wpm: Double = 20.0) {

    /**
     * Text decoded so far (finalized characters only — the in-progress
     * character isn't shown until its letter gap completes or [submit] runs).
     */
    var text: String = ""
        private set

    /** Called whenever [text] changes. */
    var onUpdate: ((String) -> Unit)? = null

    private val elements = mutableListOf<MorseCode.Element>()
    private var unitMs: Double = 1200.0 / wpm.coerceIn(5.0, 60.0)

    // Boundaries, in units. A tone shorter than ditDahSplit is a dit; a gap of
    // at least letterGapUnits ends the character, wordGapUnits adds a space.
    private val ditDahSplit = 2.0
    private val letterGapUnits = 2.0
    private val wordGapUnits = 5.0

    /** The current adaptive dit length in milliseconds. */
    val ditMs: Double get() = unitMs
    val letterGapMs: Double get() = unitMs * 3
    val wordGapMs: Double get() = unitMs * 7

    /** True when a character is mid-entry (elements buffered, not finalized). */
    val hasPendingElements: Boolean get() = elements.isNotEmpty()

    fun reset() {
        text = ""
        elements.clear()
        onUpdate?.invoke(text)
    }

    // MARK: - Streaming input

    /** Record one keyed tone (key-down duration). Classifies dit vs dah. */
    fun ingestTone(durationMs: Double) {
        if (durationMs <= 0) return
        val units = durationMs / unitMs
        val element = if (units < ditDahSplit) MorseCode.Element.DIT else MorseCode.Element.DAH
        elements.add(element)
        adaptUnit(toneMs = durationMs, element = element)
    }

    /**
     * Record a gap (silence between tones). Ends a character on a letter gap and
     * appends a space on a word gap; intra-character gaps are ignored.
     */
    fun ingestGap(durationMs: Double) {
        if (!hasPendingElements && text.isEmpty()) return
        val units = durationMs / unitMs
        if (units >= wordGapUnits) {
            finishCharacter()
            if (text.isNotEmpty() && text.last() != ' ') {
                text += " "
                onUpdate?.invoke(text)
            }
        } else if (units >= letterGapUnits) {
            finishCharacter()
        }
    }

    /**
     * Decode the buffered elements into one character. Called when a letter gap
     * elapses or the operator submits. No-op when nothing is buffered.
     */
    fun finishCharacter(): Char? {
        if (!hasPendingElements) return null
        val decoded = MorseCode.character(for_ = elements.toList())
        elements.clear()
        text += decoded ?: unknownMarker
        onUpdate?.invoke(text)
        return decoded
    }

    /** Flush any in-progress character and return the full decoded text. */
    fun submit(): String {
        finishCharacter()
        return text
    }

    private fun adaptUnit(toneMs: Double, element: MorseCode.Element) {
        // Nudge the dit estimate toward the observed tone (a dit ≈ 1 unit, a dah
        // ≈ 3). Light EMA so one sloppy element doesn't swing the estimate, and
        // clamp to a 5–60 WPM sanity range.
        val implied = if (element == MorseCode.Element.DIT) toneMs else toneMs / 3.0
        val clamped = implied.coerceIn(20.0, 240.0)
        val alpha = 0.3
        unitMs = (1 - alpha) * unitMs + alpha * clamped
    }

    /**
     * Decode a batch of (tone, trailing-gap) pairs in one shot. Convenience for
     * tests and offline decoding; the final character is flushed automatically.
     */
    fun decodeTimings(pairs: List<Pair<Double, Double>>): String {
        for ((tone, gap) in pairs) {
            ingestTone(tone)
            ingestGap(gap)
        }
        finishCharacter()
        return text
    }

    companion object {
        /** Appended when a keyed character matches no known symbol. */
        const val unknownMarker: Char = '#'
    }
}
