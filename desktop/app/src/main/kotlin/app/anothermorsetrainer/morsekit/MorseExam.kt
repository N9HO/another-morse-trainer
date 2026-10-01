package app.anothermorsetrainer.morsekit

import kotlin.random.Random

// An ARRL/FCC-style Morse code proficiency exam mode, graded the way the ARRL
// VEC graded it.
//
// Background: the historical FCC/VEC code exams (eliminated 2007-02-23) sent
// ~5 minutes of plain-language text styled as an on-air QSO — callsigns, name,
// QTH, rig, antenna, weather, RST, age, "73". License-tied speeds were 5 WPM
// (Novice), 13 WPM (General/Advanced) and 20 WPM (Amateur Extra); the 5 WPM
// test used Farnsworth (full-speed characters, stretched spacing). The text
// had to use every letter and numeral, period, comma, question mark, slant
// mark and the prosigns AR, BT and SK (47 CFR 97.503(a), 1998 edition).
//
// Grading, ARRL VEC practice (the FCC left the method to the VEs):
// - The candidate copies the whole message, then answers ten fill-in-the-blank
//   questions about it. Either path passes: one minute of solid copy, or seven
//   of the ten blanks right. (FCC 99-412 ¶37: the ARRL asked that VEs be held
//   to "a ten-question fill-in-the-blank examination or one minute of solid
//   copy".)
// - One minute of solid copy is 25 / 65 / 100 characters at 5 / 13 / 20 WPM,
//   with letters counting one and "numbers, punctuation and procedural
//   signals" counting two each (ARRL VE Manual, 8th edition, 2000). So the
//   prosigns are graded copy, not ignored.
// `fixtures/code-exam.json` pins every rule above, with its sources.
//
// The genuine secured exam transcripts were never published, so this mode
// reproduces the *format* with procedurally generated (and a few bundled)
// QSO-style passages. Pure logic, no audio/UI, so it can be unit-tested.
//
// Translated from MorseKit/MorseExam.swift. Swift's injectable
// `RandomNumberGenerator` becomes Kotlin's `kotlin.random.Random`. Exam
// vocabulary that Swift referenced as `MorseData.rigs` (etc.) now lives in
// [ExamData] (see MorseDataExam.kt).

// MARK: - Speed

/**
 * A license-tied exam speed. Mirrors the three historical Morse requirements.
 *
 * Swift `enum ExamSpeed: String, CaseIterable, Identifiable` → Kotlin
 * `enum class` carrying the raw value as [code]; computed properties become
 * getters.
 */
enum class ExamSpeed(val code: String) {
    NOVICE5("novice5"),     // 5 WPM  — Novice / Technician (the final single requirement)
    GENERAL13("general13"), // 13 WPM — General / Advanced
    EXTRA20("extra20");     // 20 WPM — Amateur Extra

    val id: String get() = code

    /** Overall (effective) words-per-minute the passage is sent at. */
    val effectiveWpm: Double
        get() = when (this) {
            NOVICE5 -> 5.0
            GENERAL13 -> 13.0
            EXTRA20 -> 20.0
        }

    /**
     * Character (element) speed. The 5 WPM test used Farnsworth: characters sent
     * at ~13 WPM with the spacing stretched so the *effective* rate is 5 WPM.
     */
    val characterWpm: Double
        get() = when (this) {
            NOVICE5 -> 13.0
            GENERAL13 -> 13.0
            EXTRA20 -> 20.0
        }

    /** Correct timing for the speed (Farnsworth for the 5 WPM test). */
    val timing: MorseTiming
        get() = if (characterWpm > effectiveWpm) {
            MorseTiming.farnsworth(characterWpm = characterWpm, effectiveWpm = effectiveWpm)
        } else {
            MorseTiming(effectiveWpm)
        }

    val wpmLabel: String get() = "${effectiveWpm.toInt()} WPM"

    /**
     * The solid-copy pass bar: one minute of copy at this exam's speed (#262),
     * so 25 / 65 / 100 counted characters at 5 / 13 / 20 WPM. Counted the
     * ARRL way: a numeral, punctuation mark or prosign is worth two letters
     * ([ExamPassage.weight]).
     *
     * Two further choices, both pinned by `fixtures/code-exam.json`:
     * - The speed is the *effective* (stated) WPM, not the character speed. The
     *   5 WPM exam sent 13 WPM characters, but a minute of it still held five
     *   words, and the historical bar there was 25 characters.
     * - Word spaces are not characters. PARIS is five letters; the space
     *   between words is timing. [ExamSession.longestCommonRun] counts the same
     *   way — a space inside a run has to match but adds nothing — so the bar
     *   and the count measure the same thing.
     */
    val requiredRun: Int get() = effectiveWpm.toInt() * CHARACTERS_PER_WORD

    val license: String
        get() = when (this) {
            NOVICE5 -> "Novice / Technician"
            GENERAL13 -> "General / Advanced"
            EXTRA20 -> "Amateur Extra"
        }

    val label: String get() = "$wpmLabel — $license"

    /**
     * The two ways to pass, as the setup, header and settings summary show
     * them: "Solid copy (65) or 7 of 10 questions" at 13 WPM.
     */
    val passLabel: String
        get() = "Solid copy ($requiredRun) or ${ExamSession.QUESTIONS_TO_PASS} of " +
            "${ExamSession.QUESTION_COUNT} questions"

    companion object {
        /** Characters in a word, by the PARIS standard every WPM figure uses. */
        const val CHARACTERS_PER_WORD = 5

        /** Mirrors Swift's `CaseIterable.allCases`. */
        val allCases: List<ExamSpeed> get() = entries.toList()
    }
}

// MARK: - Passage

/**
 * A generated (or bundled) QSO-style exam passage plus the structured facts
 * behind it, so questions can be asked about what was sent.
 *
 * Swift `struct ExamPassage: Equatable` → Kotlin `data class`. The three
 * derived fields ([sentText], [displayText], [copyText]) are computed once in
 * `init` to mirror the stored Swift properties.
 */
data class ExamPassage(
    val toCall: String,    // the station being called (the examinee)
    val deCall: String,    // the sending station (the examiner)
    /** The call area the sending station signs portable from ("4" → K9LA/4), so every passage keys a slash (#263). */
    val portable: String,
    val name: String,
    val qth: String,       // US-state QTH
    val rst: String,
    val rig: String,
    val power: String,
    val antenna: String,
    val weather: String,
    val temp: String,
    val age: String
) {
    /**
     * The keyed transmission. Prosigns are bracketed tokens (<BT> between
     * sections, <AR> closing the message, <SK> on the final over), spelled as
     * [MorseData.prosigns] spells them, which [MorseSynth] keys run-together;
     * everything else is a sendable character. This is what gets played.
     */
    val sentText: String = render(
        toCall, deCall, portable, name, qth, rst, rig, power,
        antenna, weather, temp, age
    )

    /**
     * The version shown on the reveal screen. Now that the keyed text spells
     * its prosigns the way the app displays them, it is the same string.
     */
    val displayText: String get() = sentText

    /**
     * The gradable copy a candidate would write: [sentText] through
     * [normalize]. Prosigns stay in it, as bracketed tokens, because the ARRL
     * graded them.
     */
    val copyText: String = normalize(sentText)

    companion object {
        /**
         * One ragchew template. Its fixed wording carries everything the FCC/VEC
         * exams had to include, whatever fields were drawn (#263): every letter
         * (B, J, V, Y and Z come from "JUST GOT BACK … VY GLAD" and "1830Z"),
         * every numeral ("1830Z ON 14.052 OR 7.069"), . , ? and the portable
         * slash, and the prosigns — <BT> between sections, <AR> closing the
         * message and <SK> on the final over. `fixtures/code-exam.json` lists
         * the requirement.
         */
        private fun render(
            toCall: String, deCall: String, portable: String, name: String,
            qth: String, rst: String, rig: String, power: String,
            antenna: String, weather: String, temp: String, age: String
        ): String {
            val de = "$deCall/$portable"
            return "$toCall DE $de <BT> GE OM ES TNX FER CALL. " +
                "UR RST $rst $rst, NAME HR IS $name $name, QTH $qth $qth. <BT> " +
                "RIG HR IS $rig ES PWR $power, ANT IS $antenna. <BT> " +
                "WX $weather ES TEMP $temp. AGE $age, JUST GOT BACK ON THE AIR " +
                "ES VY GLAD TO WORK U. <BT> CAN WE SKED TMW AT 1830Z ON 14.052 OR 7.069? " +
                "HW? 73 <AR> $toCall DE $de <SK>"
        }

        /**
         * Reduce a string to a comparable copy stream, used for both the
         * reference text and the learner's typed copy so grading is
         * apples-to-apples.
         *
         * Grading rule (pinned by `fixtures/code-exam.json`, identical in the
         * Swift port). The ARRL VEC graded prosigns as copy, so they stay in
         * the stream, each as its canonical bracketed token standing as its
         * own word:
         * - a bracketed token is a prosign: `<AR>`, `<sk>`, `< BT >` all read
         *   as the token with its spaces dropped, upper-cased;
         * - `+` is AR and `=` is BT, the written forms copy sheets used;
         * - the letters `AR` without brackets are the letters A and R
         *   (Arkansas in a QTH), never the prosign. The bracket or the symbol
         *   decides.
         * Letters, digits and `. , ? /` are kept as written (`/` is DN).
         * Upper-cased; whitespace runs collapse to one space, none leading or
         * trailing.
         */
        fun normalize(s: String): String {
            val words = mutableListOf<String>()
            val word = StringBuilder()
            fun endWord() {
                if (word.isNotEmpty()) {
                    words.add(word.toString())
                    word.setLength(0)
                }
            }
            val chars = s.uppercase()
            var i = 0
            while (i < chars.length) {
                val ch = chars[i]
                if (ch == '<') {
                    val close = chars.indexOf('>', startIndex = i + 1)
                    if (close >= 0) {
                        val inner = chars.substring(i + 1, close).filter { !it.isWhitespace() }
                        endWord()
                        if (inner.isNotEmpty()) words.add("<$inner>")
                        i = close + 1
                        continue
                    }
                }
                when {
                    ch == '+' || ch == '=' -> {
                        endWord()
                        words.add(if (ch == '+') "<AR>" else "<BT>")
                    }
                    ch.isWhitespace() -> endWord()
                    else -> word.append(ch)
                }
                i += 1
            }
            endWord()
            return words.joinToString(" ")
        }

        /**
         * Split a normalized copy stream into the symbols the grader compares:
         * a bracketed prosign is one symbol, a word space is one symbol, and
         * every other character is one symbol.
         */
        fun symbols(normalized: String): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < normalized.length) {
                val close = if (normalized[i] == '<') normalized.indexOf('>', startIndex = i + 1) else -1
                if (close >= 0) {
                    out.add(normalized.substring(i, close + 1))
                    i = close + 1
                } else {
                    out.add(normalized[i].toString())
                    i += 1
                }
            }
            return out
        }

        /**
         * What one copied symbol counts toward the one-minute bar, by the ARRL
         * VEC rule: a letter counts one; a numeral, punctuation mark or prosign
         * counts two; a word space counts nothing (it has to be in the right
         * place, but it is timing, not a character).
         */
        fun weight(symbol: String): Int {
            if (symbol == " ") return 0
            if (symbol.length == 1 && symbol[0] in 'A'..'Z') return 1
            return 2
        }
    }
}

/**
 * The outcome of grading a typed copy for one minute of solid copy.
 *
 * Swift `struct ExamCopyResult: Equatable` → Kotlin `data class`.
 */
data class ExamCopyResult(
    /**
     * The longest run of consecutive correct copy, counted the ARRL way
     * (letters 1, numerals / punctuation / prosigns 2, word spaces 0).
     */
    val longestRun: Int,
    /** The bar to clear: one minute of copy at the exam's speed. */
    val required: Int
) {
    val passed: Boolean get() = longestRun >= required
}

// MARK: - Question

/**
 * One fill-in-the-blank question about the message, answered from the copy.
 *
 * Swift `struct ExamQuestion: Equatable` → Kotlin `data class`.
 */
data class ExamQuestion(
    /** The blank to fill, e.g. "The operator's name is ____." */
    val prompt: String,
    /** The answer as it was sent. */
    val answer: String,
    /** Every answer marked right (e.g. both K9LA/4 and K9LA). */
    val accepted: List<String> = listOf(answer)
) {
    /**
     * Whether a typed answer fills the blank: compared the way copy is
     * compared, with case and spacing ignored ("100 w" fills "100W").
     */
    fun accepts(typed: String): Boolean {
        val t = compact(typed)
        return t.isNotEmpty() && accepted.any { compact(it) == t }
    }

    private fun compact(s: String) = ExamPassage.normalize(s).filter { it != ' ' }
}

// MARK: - Result

/** Which way a sitting passed. The ARRL VEC passed a candidate on either. */
enum class ExamPassPath { SOLID_COPY, QUESTIONS, BOTH, NONE }

/** Both grades for one sitting. */
data class ExamResult(
    val copy: ExamCopyResult,
    val questionsCorrect: Int,
    val questionsAsked: Int,
    val questionsRequired: Int
) {
    val passedByCopy: Boolean get() = copy.passed
    val passedByQuestions: Boolean
        get() = questionsAsked > 0 && questionsCorrect >= questionsRequired
    val passed: Boolean get() = passedByCopy || passedByQuestions
    val path: ExamPassPath
        get() = when {
            passedByCopy && passedByQuestions -> ExamPassPath.BOTH
            passedByCopy -> ExamPassPath.SOLID_COPY
            passedByQuestions -> ExamPassPath.QUESTIONS
            else -> ExamPassPath.NONE
        }

    /** One line naming the way the sitting passed, for the results screen. */
    val pathText: String
        get() = when (path) {
            ExamPassPath.BOTH -> "Passed on both: solid copy and the questions"
            ExamPassPath.SOLID_COPY -> "Passed on one minute of solid copy"
            ExamPassPath.QUESTIONS -> "Passed on the questions"
            ExamPassPath.NONE -> "Neither solid copy nor the questions reached the bar"
        }
}

// MARK: - Session

/**
 * Drives one sitting, graded the way the ARRL VEC graded it: copy the whole
 * message, then fill in ten blanks about it from that copy. One minute of
 * solid copy passes, and so do seven right answers out of ten.
 *
 * Swift's two initializers (random passage / explicit passage) become a primary
 * constructor taking an explicit [passage] plus a [forRandom] companion factory.
 */
class ExamSession(
    val speed: ExamSpeed,
    val passage: ExamPassage
) {
    val questions: List<ExamQuestion> = makeQuestions(passage)

    var questionIndex: Int = 0
        private set
    var correctCount: Int = 0
        private set
    var copyResult: ExamCopyResult = ExamCopyResult(longestRun = 0, required = speed.requiredRun)
        private set

    /** The historical "one minute of solid copy" bar at this exam's speed. */
    val requiredRun: Int get() = speed.requiredRun

    val summary: String get() = "Code exam · ${speed.wpmLabel}"

    // MARK: Solid copy

    /**
     * Grade a typed copy against the passage: the longest run of consecutive
     * symbols matching the sent text, weighted by the ARRL counting rule.
     */
    fun gradeSolidCopy(typed: String): ExamCopyResult {
        val a = ExamPassage.symbols(ExamPassage.normalize(typed))
        val b = ExamPassage.symbols(passage.copyText)
        return ExamCopyResult(longestRun = longestCommonRun(a, b), required = requiredRun)
    }

    /** Hand in the copy: graded now and kept for the result. */
    fun submitCopy(typed: String): ExamCopyResult {
        copyResult = gradeSolidCopy(typed)
        return copyResult
    }

    // MARK: Questions

    /** The blank being filled in, or null once all are done. */
    val currentQuestion: ExamQuestion? get() = questions.getOrNull(questionIndex)

    /** Fill in the current blank and move on. Returns whether it was right. */
    fun answer(typed: String): Boolean {
        val q = currentQuestion ?: return false
        val right = q.accepts(typed)
        if (right) correctCount += 1
        questionIndex += 1
        return right
    }

    /** Whether every question has been answered. */
    val isComplete: Boolean get() = questionIndex >= questions.size

    /** Both grades so far. */
    val result: ExamResult
        get() = ExamResult(
            copy = copyResult,
            questionsCorrect = correctCount,
            questionsAsked = questions.size,
            questionsRequired = QUESTIONS_TO_PASS
        )

    companion object {
        /** Questions asked about the message, and how many must be right. */
        const val QUESTION_COUNT = 10
        const val QUESTIONS_TO_PASS = 7

        /** Generate a random passage at the given speed. */
        fun forRandom(speed: ExamSpeed, rng: Random = Random.Default): ExamSession =
            ExamSession(speed = speed, passage = randomPassage(rng))

        /**
         * The longest run of symbols common to both, in order and unbroken,
         * each cell holding the run's ARRL weight rather than its length:
         * letters one, numerals / punctuation / prosigns two, word spaces
         * nothing (a space inside the run still has to match).
         * Longest-common-substring DP with a rolling row.
         */
        fun longestCommonRun(a: List<String>, b: List<String>): Int {
            if (a.isEmpty() || b.isEmpty()) return 0
            var prev = IntArray(b.size + 1)
            var best = 0
            for (i in 1..a.size) {
                val cur = IntArray(b.size + 1)
                for (j in 1..b.size) {
                    if (a[i - 1] != b[j - 1]) continue
                    cur[j] = prev[j - 1] + ExamPassage.weight(a[i - 1])
                    if (cur[j] > best) best = cur[j]
                }
                prev = cur
            }
            return best
        }

        // MARK: Passage generation

        fun randomPassage(rng: Random): ExamPassage {
            val calls = MorseData.callSigns
            val toCall = calls.randomOrNull(rng) ?: "W1AW"
            var deCall = calls.randomOrNull(rng) ?: "K3LR"
            // Two different stations make a sensible exchange.
            var guard0 = 0
            while (deCall == toCall && guard0 < 8) {
                deCall = calls.randomOrNull(rng) ?: "K3LR"
                guard0 += 1
            }
            return ExamPassage(
                toCall = toCall,
                deCall = deCall,
                portable = rng.nextInt(0, 10).toString(),
                name = MorseData.opNames.randomOrNull(rng) ?: "BOB",
                qth = MorseData.qthList.randomOrNull(rng) ?: "OH",
                rst = MorseData.rstValues.randomOrNull(rng) ?: "599",
                rig = ExamData.rigs.randomOrNull(rng) ?: "K3",
                power = ExamData.powers.randomOrNull(rng) ?: "100W",
                antenna = ExamData.antennas.randomOrNull(rng) ?: "DIPOLE",
                weather = ExamData.weathers.randomOrNull(rng) ?: "SUNNY",
                temp = ExamData.temps.randomOrNull(rng) ?: "72F",
                age = ExamData.ages.randomOrNull(rng) ?: "45"
            )
        }

        // MARK: Question generation

        /**
         * Ten fill-in blanks drawn from the passage's fields, in the order the
         * message sends them, so they can be answered reading down the copy.
         */
        fun makeQuestions(p: ExamPassage): List<ExamQuestion> {
            val de = "${p.deCall}/${p.portable}"
            return listOf(
                ExamQuestion("The sending station's call sign is ____.", de, listOf(de, p.deCall)),
                ExamQuestion("The signal report (RST) is ____.", p.rst),
                ExamQuestion("The operator's name is ____.", p.name),
                ExamQuestion("The QTH (state) is ____.", p.qth),
                ExamQuestion("The rig is ____.", p.rig),
                ExamQuestion("The power is ____.", p.power),
                ExamQuestion("The antenna is ____.", p.antenna),
                ExamQuestion("The weather (WX) is ____.", p.weather),
                ExamQuestion("The temperature is ____.", p.temp),
                ExamQuestion("The operator's age is ____.", p.age),
            )
        }
    }
}

