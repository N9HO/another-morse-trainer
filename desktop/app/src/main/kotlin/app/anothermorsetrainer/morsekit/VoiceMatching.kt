package app.anothermorsetrainer.morsekit

/**
 * Lookup tables for turning spoken English back into Morse answer tokens, used
 * by the "voice response" training option. Kept as plain data so they can be
 * reused both for matching and for biasing the speech recognizer.
 *
 * Translated from MorseKit/VoiceMatching.swift. Swift `enum`-namespace →
 * `object`.
 */
object VoicePhonetics {

    /**
     * NATO/ICAO phonetic alphabet — the recommended way to say a single letter
     * (far more reliable for recognition than the bare letter name).
     */
    val natoWords: Map<Char, String> = mapOf(
        'A' to "alpha", 'B' to "bravo", 'C' to "charlie", 'D' to "delta", 'E' to "echo",
        'F' to "foxtrot", 'G' to "golf", 'H' to "hotel", 'I' to "india", 'J' to "juliet",
        'K' to "kilo", 'L' to "lima", 'M' to "mike", 'N' to "november", 'O' to "oscar",
        'P' to "papa", 'Q' to "quebec", 'R' to "romeo", 'S' to "sierra", 'T' to "tango",
        'U' to "uniform", 'V' to "victor", 'W' to "whiskey", 'X' to "xray", 'Y' to "yankee",
        'Z' to "zulu"
    )

    /**
     * How the recognizer commonly transcribes a spoken *letter name* (and a few
     * near-homophones), mapped back to the letter. Keys must be unique.
     */
    val letterNameWords: Map<String, Char> = mapOf(
        "ay" to 'A', "eh" to 'A', "aye" to 'A',
        "bee" to 'B', "be" to 'B', "bea" to 'B',
        "see" to 'C', "sea" to 'C', "cee" to 'C',
        "dee" to 'D',
        "ee" to 'E',
        "ef" to 'F', "eff" to 'F',
        "gee" to 'G', "jee" to 'G',
        "aitch" to 'H', "haitch" to 'H',
        "eye" to 'I',
        "jay" to 'J',
        "kay" to 'K', "kaye" to 'K',
        "el" to 'L', "ell" to 'L',
        "em" to 'M',
        "en" to 'N',
        "owe" to 'O',
        "pee" to 'P', "pea" to 'P',
        "cue" to 'Q', "queue" to 'Q', "kew" to 'Q',
        "ar" to 'R', "are" to 'R', "arr" to 'R',
        "es" to 'S', "ess" to 'S',
        "tee" to 'T', "tea" to 'T',
        "you" to 'U', "yoo" to 'U', "ewe" to 'U',
        "vee" to 'V',
        "double you" to 'W', "double u" to 'W', "dub" to 'W',
        "ex" to 'X', "eks" to 'X',
        "why" to 'Y', "wye" to 'Y',
        "zee" to 'Z', "zed" to 'Z'
    )

    /** Digits as words, including the standard ham/aviation pronunciations. */
    val digitWords: Map<String, Char> = mapOf(
        "zero" to '0', "nought" to '0',
        "one" to '1', "won" to '1',
        "two" to '2', "to" to '2', "too" to '2',
        "three" to '3', "tree" to '3',
        "four" to '4', "for" to '4', "fower" to '4',
        "five" to '5', "fife" to '5',
        "six" to '6',
        "seven" to '7',
        "eight" to '8', "ate" to '8',
        "nine" to '9', "niner" to '9'
    )

    /** Punctuation/symbol tokens spoken as words. */
    val symbolWords: Map<String, String> = mapOf(
        "comma" to ",", "period" to ".", "full stop" to ".", "stop" to ".",
        "slash" to "/", "stroke" to "/",
        "question mark" to "?", "question" to "?",
        "equals" to "=", "equal" to "=", "plus" to "+"
    )

    /** One canonical letter-name pronunciation per letter (for spelling words). */
    val primaryLetterName: Map<Char, String> = mapOf(
        'A' to "ay", 'B' to "bee", 'C' to "see", 'D' to "dee", 'E' to "ee", 'F' to "ef",
        'G' to "gee", 'H' to "aitch", 'I' to "eye", 'J' to "jay", 'K' to "kay", 'L' to "el",
        'M' to "em", 'N' to "en", 'O' to "oh", 'P' to "pee", 'Q' to "cue", 'R' to "ar",
        'S' to "es", 'T' to "tee", 'U' to "you", 'V' to "vee", 'W' to "double you",
        'X' to "ex", 'Y' to "why", 'Z' to "zee"
    )

    /** One canonical word per digit. */
    val primaryDigitWord: Map<Char, String> = mapOf(
        '0' to "zero", '1' to "one", '2' to "two", '3' to "three", '4' to "four",
        '5' to "five", '6' to "six", '7' to "seven", '8' to "eight", '9' to "nine"
    )
}

/**
 * The result of trying to understand what the learner said.
 *
 * Translated from MorseKit/VoiceMatching.swift. The Swift `init` clamped
 * [isConfident] to false whenever [token] was nil; the secondary constructor
 * preserves that.
 */
data class VoiceInterpretation(
    /** The best-guess answer token (one of the supplied candidates), or null. */
    val token: String?,
    /** 0…1, where 1 means an exact match. */
    val confidence: Double,
    /** True only when we're confident enough to grade without confirming. */
    val isConfident: Boolean
) {
    constructor(token: String?, confidence: Double) :
        this(token, confidence, false)

    companion object {
        /** Mirrors Swift's `init(token:confidence:isConfident:)` clamp. */
        fun make(token: String?, confidence: Double, isConfident: Boolean = false): VoiceInterpretation =
            VoiceInterpretation(token, confidence, isConfident && token != null)
    }
}

/**
 * Turns speech-recognizer transcripts into Morse answer tokens. Pure and
 * deterministic so it can be unit-tested without the Speech framework: the iOS
 * layer passes in the recognizer's best guess plus its alternatives, and this
 * decides what the learner most likely meant. An optional [VoiceProfile]
 * personalizes the result from the user's past confirmations.
 *
 * Translated from MorseKit/VoiceMatching.swift.
 */
class VoiceMatcher(
    var profile: VoiceProfile = VoiceProfile()
) {

    // MARK: - Public API

    /**
     * Best interpretation of [transcripts] restricted to the drill's
     * [candidates]. Used to decide whether to grade outright or to ask
     * "Did you say X?".
     */
    fun interpret(
        transcripts: List<String>,
        candidates: List<String>,
        confidenceThreshold: Double = 0.6
    ): VoiceInterpretation {
        val cands = orderedUnique(candidates)
        if (cands.isEmpty()) return VoiceInterpretation(token = null, confidence = 0.0)
        val norms = transcripts.map { normalize(it) }.filter { it.isNotEmpty() }
        if (norms.isEmpty()) return VoiceInterpretation(token = null, confidence = 0.0)

        // 1) Personalized override: if this user reliably means a candidate by
        //    this exact phrasing, trust it.
        val learned = profile.suggestion(norms[0])
        if (learned != null && cands.contains(learned)) {
            return VoiceInterpretation.make(token = learned, confidence = 0.97, isConfident = true)
        }

        // 2) Score every candidate by closeness, best (lowest distance) first.
        val scored = cands
            .map { token -> Scored(token, bestNormalizedDistance(token, norms)) }
            .sortedWith(compareBy({ it.score }, { it.token }))
        val best = scored.firstOrNull()
            ?: return VoiceInterpretation(token = null, confidence = 0.0)

        var confidence = maxOf(0.0, 1 - best.score)
        // A near-tie with the runner-up means we're not sure which they said.
        if (scored.size > 1 && scored[1].score - best.score < 0.15) {
            confidence *= 0.6
        }
        return VoiceInterpretation.make(
            token = best.token,
            confidence = confidence,
            isConfident = confidence >= confidenceThreshold
        )
    }

    /**
     * The [limit] answers from [pool] that sound closest to what was heard,
     * best first. Used to populate the "pick the closest" buttons after the
     * learner rejects the "Did you say X?" guess.
     */
    fun rankedCandidates(
        transcripts: List<String>,
        pool: List<String>,
        limit: Int = 4
    ): List<String> {
        val candidates = orderedUnique(pool)
        val norms = transcripts.map { normalize(it) }.filter { it.isNotEmpty() }
        if (norms.isEmpty()) return candidates.take(limit)
        val scored = candidates
            .map { token -> Scored(token, bestNormalizedDistance(token, norms)) }
            .sortedWith(compareBy({ it.score }, { it.token }))
        return scored.take(limit).map { it.token }
    }

    /**
     * Phrases to bias the recognizer toward (every spoken form of every
     * candidate). Used for `contextualStrings` and the custom language model.
     */
    fun contextualStrings(candidates: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (c in candidates) out.addAll(spokenForms(c))
        return orderedUnique(out)
    }

    // MARK: - Scoring

    private fun bestNormalizedDistance(token: String, norms: List<String>): Double {
        val forms = spokenForms(token)
        if (forms.isEmpty()) return 1.0
        var best = Double.MAX_VALUE
        for (n in norms) {
            for (f in forms) best = minOf(best, normalizedDistance(n, f))
        }
        return best
    }

    /** Small holder for a (token, score) pair while sorting. */
    private data class Scored(val token: String, val score: Double)

    companion object {

        // MARK: - Spoken forms

        /**
         * Every plausible way to say [token]: NATO words, letter names, digit
         * words, the word itself, and (for multi-character tokens) spelled-out
         * variants.
         */
        fun spokenForms(token: String): List<String> {
            val upper = token.uppercase()
            val chars = upper.toCharArray()
            if (chars.size == 1) return singleCharForms(chars[0])

            val forms = mutableListOf<String>()
            forms.add(normalize(upper))                                        // said as a word
            forms.add(chars.joinToString(" ") { spelledNato(it) })            // spelled (NATO)
            forms.add(chars.joinToString(" ") { spelledName(it) })            // spelled (names)
            return orderedUnique(forms).filter { it.isNotEmpty() }
        }

        private fun singleCharForms(ch: Char): List<String> {
            val c = ch.uppercaseChar()
            val forms = mutableListOf<String>()
            VoicePhonetics.natoWords[c]?.let { forms.add(it) }
            for ((word, letter) in VoicePhonetics.letterNameWords) {
                if (letter == c) forms.add(word)
            }
            for ((word, digit) in VoicePhonetics.digitWords) {
                if (digit == c) forms.add(word)
            }
            for ((word, sym) in VoicePhonetics.symbolWords) {
                if (sym == c.toString()) forms.add(word)
            }
            if (c.isLetter() || c.isDigit()) forms.add(c.toString().lowercase())
            return orderedUnique(forms).filter { it.isNotEmpty() }
        }

        private fun spelledNato(ch: Char): String {
            val c = ch.uppercaseChar()
            VoicePhonetics.natoWords[c]?.let { return it }
            VoicePhonetics.primaryDigitWord[c]?.let { return it }
            return c.toString().lowercase()
        }

        private fun spelledName(ch: Char): String {
            val c = ch.uppercaseChar()
            VoicePhonetics.primaryLetterName[c]?.let { return it }
            VoicePhonetics.primaryDigitWord[c]?.let { return it }
            return c.toString().lowercase()
        }

        // MARK: - Helpers

        /** Lowercase, strip punctuation to spaces, and collapse whitespace. */
        fun normalize(s: String): String {
            val out = StringBuilder()
            for (ch in s.lowercase()) {
                out.append(if (ch.isLetter() || ch.isDigit()) ch else ' ')
            }
            // Swift `.split(separator: " ")` drops empty subsequences, so this
            // both trims and collapses runs of whitespace.
            return out.toString().split(" ").filter { it.isNotEmpty() }.joinToString(" ")
        }

        /** Levenshtein distance normalized to 0…1 by the longer string's length. */
        fun normalizedDistance(a: String, b: String): Double {
            if (a.isEmpty() && b.isEmpty()) return 0.0
            val d = MorseDistance.distance(a, b, substitutionCost = 1.0, indelCost = 1.0)
            val m = maxOf(a.length, b.length).toDouble()
            return if (m == 0.0) 0.0 else d / m
        }

        fun orderedUnique(items: List<String>): List<String> {
            val seen = HashSet<String>()
            val out = mutableListOf<String>()
            for (i in items) {
                if (seen.add(i)) out.add(i)
            }
            return out
        }
    }
}
