package app.anothermorsetrainer.morsekit

/**
 * The Morse code alphabet and helpers for turning characters into the
 * sequence of dits (·) and dahs (−) that get played as sound.
 *
 * Hand-translated from MorseKit/MorseCode.swift — the Swift `enum` used as a
 * namespace becomes a Kotlin `object`; everything else maps near 1:1.
 */
object MorseCode {

    /** A single timed element of a Morse character. */
    enum class Element {
        DIT,   // short tone  (1 unit)
        DAH    // long tone   (3 units)
    }

    /**
     * Character → element string, using "." for a dit and "-" for a dah.
     * Letters, digits, and "?" — the base set every learner works through.
     * ("?" is common enough on the air to belong in the core curriculum.)
     */
    val table: Map<Char, String> = mapOf(
        'A' to ".-",    'B' to "-...",  'C' to "-.-.",  'D' to "-..",
        'E' to ".",     'F' to "..-.",  'G' to "--.",   'H' to "....",
        'I' to "..",    'J' to ".---",  'K' to "-.-",   'L' to ".-..",
        'M' to "--",    'N' to "-.",    'O' to "---",   'P' to ".--.",
        'Q' to "--.-",  'R' to ".-.",   'S' to "...",   'T' to "-",
        'U' to "..-",   'V' to "...-",  'W' to ".--",   'X' to "-..-",
        'Y' to "-.--",  'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--",
        '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
        '8' to "---..", '9' to "----.",
        '?' to "..--.."
    )

    /**
     * Punctuation a learner can opt into beyond the base set. Kept separate so
     * it only appears when the user explicitly chooses to study it.
     */
    val optionalPunctuation: Map<Char, String> = mapOf(
        ',' to "--..--",   // comma
        '/' to "-..-.",    // slash
        '.' to ".-.-.-",   // period
        '=' to "-...-"     // the BT prosign / "double dash" used as a CW section
                           // separator (kept out of the UI punctuation picker, but
                           // sendable so exam passages can key authentic BT breaks)
    )

    /**
     * The punctuation offered by the settings opt-in picker, in teaching order.
     * ('=' stays out — it's sendable for exam passages but not studied.)
     */
    val pickablePunctuation: List<Char> = listOf('.', ',', '/')

    /**
     * The ladder's introduction order: the Koch core, then whichever punctuation
     * the learner has opted into, appended at the end so it is introduced only
     * after the core set is done.
     *
     * Opting in adds a mark to the *ladder*, not to the active set — it still
     * has to be reached and mastered like any other character. Moved here from
     * `Settings.studyOrder()` so both ports build it the same way and
     * `fixtures/ladder.json` can pin it; the behaviour is unchanged.
     */
    fun studyOrder(punctuation: Set<Char>): List<Char> =
        kochOrder + pickablePunctuation.filter { it in punctuation }

    /**
     * Pattern lookup across both the base table and optional punctuation.
     * (`optionalPunctuation + table` so a base entry wins on any key clash,
     * matching the Swift `merging { base, _ in base }`.)
     */
    private val allPatterns: Map<Char, String> = optionalPunctuation + table

    /**
     * The classic Koch-method teaching order. New characters are introduced
     * from the front of this list, one at a time, as the learner speeds up.
     */
    val kochOrder: List<Char> =
        "KMRSUAPTLOWINJEF0YVG5Q9ZH38B?427C1D6X".toList()
            .filter { table.containsKey(it) }

    /** All trainable characters (sorted for stable, testable ordering). */
    val alphabet: List<Char> = table.keys.sorted()

    /** The pattern string ("-..-") for a character, or null if unknown. */
    fun pattern(for_: Char): String? = allPatterns[for_.uppercaseChar()]

    /** The timed elements (dit/dah) for a character. */
    fun elements(for_: Char): List<Element> {
        val pattern = pattern(for_) ?: return emptyList()
        return pattern.map { if (it == '.') Element.DIT else Element.DAH }
    }

    /**
     * Reverse lookup: an element-string pattern ("-..-") → its character. Built
     * from [allPatterns], so a base-table entry wins on any clash (the base
     * entries come last in `optionalPunctuation + table`, overwriting in the
     * association). Used by the decoder to turn keyed timing back into text.
     */
    private val charForPattern: Map<String, Char> =
        allPatterns.entries.associate { (ch, pat) -> pat to ch }

    /**
     * The character for a dit/dah pattern string ("-..-" → 'X'), or null if the
     * pattern isn't a known character. The inverse of [pattern]; mirrors the
     * Swift `character(forPattern:)`.
     */
    fun characterForPattern(pattern: String): Char? = charForPattern[pattern]

    /** Decode a sequence of dit/dah elements back to a character, or null. */
    fun character(for_: List<Element>): Char? {
        if (for_.isEmpty()) return null
        val pattern = for_.joinToString("") { if (it == Element.DIT) "." else "-" }
        return charForPattern[pattern]
    }
}
