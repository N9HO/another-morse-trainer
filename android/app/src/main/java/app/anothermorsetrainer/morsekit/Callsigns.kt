package app.anothermorsetrainer.morsekit

import kotlin.random.Random

/**
 * Amateur callsign shapes, expressed as "(prefix letters) × (suffix letters)".
 * US calls are `prefix + single region digit + suffix`, e.g. `K1ABC` is 1×3.
 *
 * Translated from MorseKit/Callsigns.swift. Swift's raw-valued enum becomes a
 * Kotlin `enum class` carrying its [code] (the Swift rawValue, for persistence),
 * [label], and the prefix/suffix lengths as constructor params.
 */
enum class CallsignFormat(
    val code: String,
    val label: String,
    val prefixLen: Int,
    val suffixLen: Int
) {
    OneByOne("oneByOne", "1×1", 1, 1),      // K1A      (special event)
    OneByTwo("oneByTwo", "1×2", 1, 2),      // K1AB
    TwoByOne("twoByOne", "2×1", 2, 1),      // AB1C
    OneByThree("oneByThree", "1×3", 1, 3),  // K1ABC
    TwoByTwo("twoByTwo", "2×2", 2, 2),      // AB1CD
    TwoByThree("twoByThree", "2×3", 2, 3);  // AB1CDE

    val id: String get() = code

    companion object {
        /** A sensible default set: the everyday formats on, the rare ones off. */
        val commonDefaults: List<CallsignFormat> = listOf(OneByTwo, TwoByOne, OneByThree, TwoByTwo)
    }
}

/**
 * Procedurally generates realistic callsigns so pileups vary widely (and
 * substring matching is meaningful). Pure logic — seedable for tests.
 *
 * Swift's generic `<R: RandomNumberGenerator>(using rng: inout R)` becomes a
 * plain `rng: Random` parameter.
 */
object CallsignGenerator {
    private val letters = ('A'..'Z').toList()
    private val digits = ('0'..'9').toList()
    // US single-letter prefixes actually issued.
    private val usSingle = listOf('K', 'N', 'W')
    // US two-letter prefix first letters.
    private val usFirst = listOf('A', 'K', 'N', 'W')
    // A few common DX prefixes, for the optional worldwide pool.
    private val dxPrefixes = listOf(
        "DL", "G", "GM", "F", "ON", "PA", "EA", "I", "SM", "LA", "OE", "HB", "JA",
        "VK", "ZL", "VE", "SP", "OK", "LZ", "YO", "UR", "RA", "UA", "9A", "OZ", "EI"
    )

    /**
     * One random callsign in one of the allowed [formats]. [usOnly] restricts
     * to US-style calls; otherwise DX prefixes are mixed in.
     */
    fun generate(formats: List<CallsignFormat>, usOnly: Boolean, rng: Random): String {
        val fmt = formats.randomOrNull(rng) ?: CallsignFormat.OneByTwo
        return if (usOnly || rng.nextBoolean()) usCall(fmt, rng) else dxCall(fmt, rng)
    }

    private fun usCall(fmt: CallsignFormat, rng: Random): String {
        val s = StringBuilder()
        if (fmt.prefixLen == 1) {
            s.append(usSingle.random(rng))
        } else {
            val first = usFirst.random(rng)
            s.append(first)
            // 'A' prefixes only run AA–AL; the rest take any second letter.
            val second = if (first == 'A') ('A'..'L').toList().random(rng) else letters.random(rng)
            s.append(second)
        }
        s.append(digits.random(rng))
        repeat(fmt.suffixLen) { s.append(letters.random(rng)) }
        return s.toString()
    }

    private fun dxCall(fmt: CallsignFormat, rng: Random): String {
        val s = StringBuilder(dxPrefixes.random(rng))
        s.append(digits.random(rng))
        // DX suffixes run 1–3 letters; reuse the format's suffix length.
        repeat(maxOf(2, fmt.suffixLen)) { s.append(letters.random(rng)) }
        return s.toString()
    }
}

/**
 * Contest "cut numbers": numerals shortened to letters (T=0, N=9, …) to save
 * time. We always grade against the real digits, accepting either the digit or
 * its cut letter typed back.
 */
object CutNumbers {
    /**
     * Standard digit → cut-letter map. Only the digits in the active set are
     * actually substituted when sending.
     */
    val map: Map<Char, Char> = mapOf(
        '0' to 'T', '1' to 'A', '2' to 'U', '3' to 'V', '5' to 'E', '7' to 'G', '8' to 'D', '9' to 'N'
    )

    /** Reverse map for normalizing typed input back to digits. */
    val reverse: Map<Char, Char> = mapOf(
        'T' to '0', 'A' to '1', 'U' to '2', 'V' to '3', 'E' to '5', 'G' to '7', 'D' to '8', 'N' to '9'
    )

    /** The digits that can be cut (for the settings UI). */
    val cuttableDigits: List<Char> = listOf('0', '1', '2', '3', '5', '7', '8', '9')

    /** A reasonable default: the two everyone actually uses on the air. */
    val commonDefaults: Set<Char> = setOf('0', '9')

    /**
     * Replace each digit in [text] with its cut letter when that digit is in
     * [enabled]. Non-digits pass through untouched.
     */
    fun encode(text: String, enabled: Set<Char>): String =
        text.map { ch -> (if (ch in enabled) map[ch] else null) ?: ch }.joinToString("")

    /** Normalize typed input for numeric comparison: cut letters become digits. */
    fun decodeDigits(text: String): String =
        text.uppercase().mapNotNull { ch ->
            when {
                ch.isDigit() -> ch
                reverse[ch] != null -> reverse[ch]
                else -> null
            }
        }.joinToString("")
}
