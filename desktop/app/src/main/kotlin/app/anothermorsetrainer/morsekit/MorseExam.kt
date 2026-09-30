package app.anothermorsetrainer.morsekit

import kotlin.random.Random

// An ARRL/FCC-style Morse code proficiency exam mode.
//
// Background: the historical FCC/VEC code exams (eliminated 2007-02-23) sent
// ~5 minutes of plain-language text styled as an on-air QSO — callsigns, name,
// QTH, rig, antenna, weather, RST, age, "73". To pass you needed EITHER one
// minute of solid copy (25 consecutive correct characters) OR to correctly
// answer ~10 fill-in questions about the content. License-tied speeds were
// 5 WPM (Novice), 13 WPM (General/Advanced) and 20 WPM (Amateur Extra); the
// 5 WPM test used Farnsworth (full-speed characters, stretched spacing).
//
// The genuine secured exam transcripts were never published, so this mode
// reproduces the *format* with procedurally generated (and a few bundled)
// QSO-style passages, plus both grading modes. Pure logic, no audio/UI, so it
// can be unit-tested and reuses the same `QuizSource` loop as the other modes.
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

    val license: String
        get() = when (this) {
            NOVICE5 -> "Novice / Technician"
            GENERAL13 -> "General / Advanced"
            EXTRA20 -> "Amateur Extra"
        }

    val label: String get() = "$wpmLabel — $license"

    companion object {
        /** Mirrors Swift's `CaseIterable.allCases`. */
        val allCases: List<ExamSpeed> get() = entries.toList()
    }
}

// MARK: - Grading mode

/**
 * The two historical ways to pass the code exam.
 *
 * Swift `enum ExamGrading: String, CaseIterable, Identifiable` → Kotlin
 * `enum class` carrying the raw value as [code].
 */
enum class ExamGrading(val code: String) {
    SOLID_COPY("solidCopy"), // one minute of solid copy: 25 consecutive correct chars
    QUESTIONS("questions");  // ~10 fill-in questions about the content of the message

    val id: String get() = code

    val label: String
        get() = when (this) {
            SOLID_COPY -> "Solid copy (25 in a row)"
            QUESTIONS -> "Answer questions"
        }

    companion object {
        /** Mirrors Swift's `CaseIterable.allCases`. */
        val allCases: List<ExamGrading> get() = entries.toList()
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
     * The keyed transmission, using "=" (BT) section separators and a final
     * "K". Every character is sendable. This is what gets played in Morse.
     */
    val sentText: String = render(
        toCall, deCall, name, qth, rst, rig, power,
        antenna, weather, temp, age, sep = "=", closer = "K"
    )

    /**
     * A prosign-annotated, human-readable version for the reveal screen
     * (separators shown as <BT>, sign-off as <KN>).
     */
    val displayText: String = render(
        toCall, deCall, name, qth, rst, rig, power,
        antenna, weather, temp, age, sep = "<BT>", closer = "<KN>"
    )

    /**
     * The gradable plain-text copy a candidate would write: [sentText] with the
     * "=" separators removed and whitespace collapsed.
     */
    val copyText: String = normalize(sentText)

    companion object {
        /**
         * One ragchew template, parameterized by the section separator and
         * sign-off so the keyed ("=" / "K") and pretty ("<BT>" / "<KN>") forms
         * stay in sync.
         */
        private fun render(
            toCall: String, deCall: String, name: String,
            qth: String, rst: String, rig: String, power: String,
            antenna: String, weather: String, temp: String,
            age: String, sep: String, closer: String
        ): String =
            "$toCall DE $deCall $sep GE OM ES TNX FER CALL $sep " +
                "UR RST $rst $rst $sep NAME HR IS $name $name $sep " +
                "QTH $qth $qth $sep RIG HR IS $rig ES PWR $power $sep " +
                "ANT IS $antenna $sep WX $weather ES TEMP $temp $sep " +
                "AGE $age $sep HW? $toCall DE $deCall $closer"

        /**
         * Reduce a string to a comparable copy stream: upper-cased, "="
         * separators dropped (candidates aren't expected to transcribe BT), and
         * runs of whitespace collapsed to a single space. Used for both the
         * reference text and the learner's typed copy so grading is
         * apples-to-apples.
         */
        fun normalize(s: String): String {
            val out = StringBuilder()
            var pendingSpace = false
            for (ch in s.uppercase()) {
                if (ch == '=') continue
                if (ch == ' ' || ch == '\n' || ch == '\t') {
                    if (out.isNotEmpty()) pendingSpace = true
                } else {
                    if (pendingSpace) {
                        out.append(' ')
                        pendingSpace = false
                    }
                    out.append(ch)
                }
            }
            return out.toString()
        }
    }
}

/**
 * The outcome of grading a solid-copy attempt.
 *
 * Swift `struct ExamCopyResult: Equatable` → Kotlin `data class`.
 */
data class ExamCopyResult(
    /** Length of the longest run of consecutive characters the copy got right. */
    val longestRun: Int,
    /** The bar to clear (the historical FCC rule: 25 in a row). */
    val required: Int
) {
    val passed: Boolean get() = longestRun >= required
}

// MARK: - Question

/**
 * One fill-in question about the passage's content.
 *
 * Swift `struct ExamQuestion: Equatable` → Kotlin `data class`.
 */
data class ExamQuestion(
    val prompt: String,
    val options: List<String>,   // distinct, includes `answer`
    val answer: String
)

// MARK: - Session

/**
 * Drives one exam: holds the passage, the questions, and the grading. Plugs
 * into the shared [QuizSource] loop, and also exposes a richer API the app's
 * bespoke exam screen uses (play the whole passage, then copy or answer).
 *
 * Swift's two initializers (random passage / explicit passage) become a primary
 * constructor taking an explicit [passage] plus a [forRandom] companion factory.
 */
class ExamSession(
    val speed: ExamSpeed,
    val grading: ExamGrading,
    passage: ExamPassage,
    questionCount: Int = 10,
    private val rng: Random = Random.Default
) : QuizSource {

    var passage: ExamPassage = passage
        private set

    var questions: List<ExamQuestion> = makeQuestions(passage, questionCount, rng)
        private set

    var questionIndex: Int = 0
        private set
    var correctCount: Int = 0
        private set
    var lastCopyResult: ExamCopyResult? = null
        private set

    // MARK: QuizSource

    override val summary: String
        get() = when (grading) {
            ExamGrading.SOLID_COPY ->
                "Code exam · ${speed.wpmLabel}"
            ExamGrading.QUESTIONS ->
                "Q ${minOf(questionIndex + 1, questions.size)} of ${questions.size}"
        }

    override fun nextDrill(): Drill = when (grading) {
        ExamGrading.SOLID_COPY ->
            Drill(
                playable = MorseItem.Playable.Text(passage.sentText),
                options = emptyList(),
                correct = passage.copyText,
                revealPrimary = passage.copyText,
                revealSecondary = "",
                question = "Copy the transmission, then type what you got. " +
                    "Pass = $requiredRun correct characters in a row."
            )
        ExamGrading.QUESTIONS -> {
            val q = questions[minOf(questionIndex, maxOf(0, questions.size - 1))]
            // The first question carries the passage so the loop plays it once;
            // later questions are silent (the passage isn't replayed).
            Drill(
                playable = MorseItem.Playable.Text(if (questionIndex == 0) passage.sentText else ""),
                options = q.options,
                correct = q.answer,
                revealPrimary = q.answer,
                revealSecondary = "",
                question = q.prompt
            )
        }
    }

    override fun record(choice: String, ttr: Double): DrillOutcome = when (grading) {
        ExamGrading.SOLID_COPY -> {
            val result = gradeSolidCopy(choice)
            lastCopyResult = result
            DrillOutcome(correct = result.passed, unlocked = null)
        }
        ExamGrading.QUESTIONS -> {
            if (questionIndex >= questions.size) {
                DrillOutcome(correct = false, unlocked = null)
            } else {
                val correct = choice == questions[questionIndex].answer
                if (correct) correctCount += 1
                questionIndex += 1
                val done = questionIndex >= questions.size
                DrillOutcome(correct = correct, unlocked = if (done) "exam complete" else null)
            }
        }
    }

    /** Whether every question has been answered (question mode). */
    val isComplete: Boolean get() = questionIndex >= questions.size

    // MARK: Solid-copy grading

    /**
     * Grade a typed copy against the passage: find the longest run of
     * consecutive characters that exactly matches the sent text.
     */
    fun gradeSolidCopy(typed: String): ExamCopyResult {
        val a = ExamPassage.normalize(typed).toList()
        val b = passage.copyText.toList()
        return ExamCopyResult(
            longestRun = longestCommonRun(a, b),
            required = requiredRun
        )
    }

    companion object {
        /** The historical "one minute of solid copy" bar: 25 consecutive characters. */
        const val requiredRun = 25

        /** Generate a random passage at the given speed. */
        fun forRandom(
            speed: ExamSpeed,
            grading: ExamGrading,
            questionCount: Int = 10,
            rng: Random = Random.Default
        ): ExamSession {
            val passage = randomPassage(rng)
            return ExamSession(
                speed = speed,
                grading = grading,
                passage = passage,
                questionCount = questionCount,
                rng = rng
            )
        }

        /**
         * Length of the longest substring common to both character lists
         * (classic O(n·m) longest-common-substring DP, rolling row).
         */
        fun longestCommonRun(a: List<Char>, b: List<Char>): Int {
            if (a.isEmpty() || b.isEmpty()) return 0
            var prev = IntArray(b.size + 1)
            var best = 0
            for (i in 1..a.size) {
                val cur = IntArray(b.size + 1)
                for (j in 1..b.size) {
                    if (a[i - 1] != b[j - 1]) continue
                    cur[j] = prev[j - 1] + 1
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

        fun makeQuestions(p: ExamPassage, count: Int, rng: Random): List<ExamQuestion> {
            // (prompt, correct answer, pool to draw distractors from)
            val fields: List<Triple<String, String, List<String>>> = listOf(
                Triple("What was the operator's name?", p.name, MorseData.opNames),
                Triple("What state (QTH) were they in?", p.qth, MorseData.qthList),
                Triple("What RST signal report did they send?", p.rst, MorseData.rstValues),
                Triple("What rig (radio) were they using?", p.rig, ExamData.rigs),
                Triple("How much power were they running?", p.power, ExamData.powers),
                Triple("What antenna were they using?", p.antenna, ExamData.antennas),
                Triple("What was the weather (WX) like?", p.weather, ExamData.weathers),
                Triple("What was the temperature?", p.temp, ExamData.temps),
                Triple("How old is the operator?", p.age, ExamData.ages),
                Triple("What was the sending station's callsign?", p.deCall, MorseData.callSigns),
            )
            var built = fields.map { field ->
                ExamQuestion(
                    prompt = field.first,
                    options = makeOptions(answer = field.second, pool = field.third, rng = rng),
                    answer = field.second
                )
            }
            built = built.shuffled(rng)
            if (count < built.size) built = built.take(count)
            return built
        }

        /**
         * Four distinct options: the correct answer plus three random distractors
         * from the same pool, shuffled.
         */
        fun makeOptions(answer: String, pool: List<String>, rng: Random): List<String> {
            val distractors = pool.filter { it != answer }.shuffled(rng)
            val options = mutableListOf(answer)
            for (d in distractors) {
                if (options.size >= 4) break
                if (!options.contains(d)) options.add(d)
            }
            return options.shuffled(rng)
        }
    }
}
