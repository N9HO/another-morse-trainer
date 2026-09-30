package app.anothermorsetrainer.morsekit.cw

/**
 * Morse element-pattern → text resolver for the CW decoder core.
 *
 * Line-faithful Kotlin port of the vendored Carrier Wave decoder core's
 * `cw_morse.c` / `cw_morse.h` (see PROVENANCE.md in this package; MIT, © 2026
 * Jay Vana). Kept separate from [app.anothermorsetrainer.morsekit.MorseCode]
 * on purpose: this is the decoder core's own table (with prosigns and the
 * character-over-prosign preference), mirrored so the two decoders — the
 * firmware's and this port — resolve identically.
 */
object CwMorse {

    /* Standard ITU Morse. For patterns shared by a character and a prosign
     * (e.g. ".-.-." is both '+' and <AR>), the everyday character is used.
     * A few unambiguous prosigns are included as <..> text. */
    private val table: Map<String, String> = mapOf(
        // letters
        ".-" to "A", "-..." to "B", "-.-." to "C", "-.." to "D",
        "." to "E", "..-." to "F", "--." to "G", "...." to "H",
        ".." to "I", ".---" to "J", "-.-" to "K", ".-.." to "L",
        "--" to "M", "-." to "N", "---" to "O", ".--." to "P",
        "--.-" to "Q", ".-." to "R", "..." to "S", "-" to "T",
        "..-" to "U", "...-" to "V", ".--" to "W", "-..-" to "X",
        "-.--" to "Y", "--.." to "Z",
        // digits
        "-----" to "0", ".----" to "1", "..---" to "2", "...--" to "3",
        "....-" to "4", "....." to "5", "-...." to "6", "--..." to "7",
        "---.." to "8", "----." to "9",
        // punctuation
        ".-.-.-" to ".", "--..--" to ",", "..--.." to "?", ".----." to "'",
        "-.-.--" to "!", "-..-." to "/", "-.--." to "(", "-.--.-" to ")",
        ".-..." to "&", "---..." to ":", "-.-.-." to ";", "-...-" to "=",
        ".-.-." to "+", "-....-" to "-", "..--.-" to "_", ".-..-." to "\"",
        "...-..-" to "$", ".--.-." to "@",
        // unambiguous prosigns
        "...-.-" to "<SK>",    // end of contact
        "-...-.-" to "<BK>",   // break
        "...-." to "<SN>",     // understood / VE
        ".-.-" to "<AA>"       // new line
    )

    /** The decoded text for a dot/dash pattern, or null if unknown. */
    fun lookup(pattern: String): String? =
        if (pattern.isEmpty()) null else table[pattern]
}
