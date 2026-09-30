package app.anothermorsetrainer.morsekit

import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The Sending Analyzer (#241, #234, #235): the operator is shown a text, sends
// it on whatever key they have, and gets back what was copied, how it lines up
// against the text, and how their elements and spacing compare with the
// 1:3 / 1:3:7 standard.
//
// Everything here is pure and works on one thing: a list of key-down/key-up
// times. Where those come from is the app's business — the on-screen key, a
// Vail adapter or other MIDI key, the microphone ([ToneKeyingDetector]), and
// later the on-screen paddles all reduce to the same stream, so none of them
// needs anything from this file but [KeyingRecorder].
//
// The spec is written out in `fixtures/sending-analysis.json` (`derivation`),
// which pins both ports to the same numbers. Hand-translated from
// MorseKit/SendingAnalysis.swift.

/**
 * The kind of key the operator is sending on. It changes what the analysis may
 * fairly judge: a keyer times every element itself, and a bug times its dits,
 * so feedback about those parts would be about the machine.
 */
enum class SendingKeyType(val id: String, val title: String) {
    /** Straight key — every element and gap is hand-timed. */
    STRAIGHT("straight", "Straight key"),
    /** Semi-automatic "bug": dits machine-timed, dahs formed by hand. */
    BUG("bug", "Bug"),
    /** Cootie / sideswiper — hand-timed like a straight key, sideways. */
    COOTIE("cootie", "Cootie"),
    /** Paddles through an electronic (iambic) keyer — elements machine-timed. */
    KEYER("keyer", "Paddles (keyer)");

    /** Whether the operator, not a machine, forms the dahs and intra-character gaps. */
    val handTimesElements: Boolean get() = this != KEYER
    /** Whether the dits are hand-formed (and so their evenness is the operator's). */
    val handTimesDits: Boolean get() = this == STRAIGHT || this == COOTIE

    companion object {
        fun fromId(id: String?): SendingKeyType = entries.firstOrNull { it.id == id } ?: STRAIGHT
    }
}

/** One key-down interval, in ms on any clock consistent within one attempt. */
data class KeyMark(val downMs: Double, val upMs: Double) {
    val durationMs: Double get() = upMs - downMs
}

/**
 * Collects key edges from any input into [KeyMark]s. Any source that can say
 * "key went down at t" and "key went up at t" plugs in here — which is the
 * whole interface the analyzer asks of a keyer, paddles included.
 */
class KeyingRecorder {
    private val _marks = mutableListOf<KeyMark>()
    val marks: List<KeyMark> get() = _marks.toList()
    private var downAt: Double? = null

    val isDown: Boolean get() = downAt != null
    /** Time of the most recent edge, or null before the first. */
    var lastEdgeMs: Double? = null
        private set

    fun keyDown(atMs: Double) {
        if (downAt != null) return
        downAt = atMs
        lastEdgeMs = atMs
    }

    fun keyUp(atMs: Double) {
        val down = downAt ?: return
        downAt = null
        lastEdgeMs = atMs
        if (atMs > down) _marks.add(KeyMark(down, atMs))
    }

    fun reset() {
        _marks.clear()
        downAt = null
        lastEdgeMs = null
    }
}

/** Feedback the analysis can give, in the order it is shown. [code]s are what the fixture pins. */
enum class SendingFeedback(val code: String) {
    SLOWER_THAN_TARGET("slowerThanTarget"), FASTER_THAN_TARGET("fasterThanTarget"),
    DAHS_SHORT("dahsShort"), DAHS_LONG("dahsLong"), DITS_UNEVEN("ditsUneven"), DAHS_UNEVEN("dahsUneven"),
    ELEMENT_GAPS_SHORT("elementGapsShort"), ELEMENT_GAPS_LONG("elementGapsLong"),
    CHAR_GAPS_SHORT("charGapsShort"), CHAR_GAPS_LONG("charGapsLong"), CHAR_GAPS_UNEVEN("charGapsUneven"),
    WORD_GAPS_SHORT("wordGapsShort"), WORD_GAPS_LONG("wordGapsLong"),
    WORDS_RUN_TOGETHER("wordsRunTogether"), WORDS_SPLIT("wordsSplit"),
    PERFECT_COPY("perfectCopy"), TIMING_ON_TARGET("timingOnTarget");

    /** The codes about timing (everything [TIMING_ON_TARGET] stands for the absence of). */
    val isTiming: Boolean
        get() = this != WORDS_RUN_TOGETHER && this != WORDS_SPLIT &&
            this != PERFECT_COPY && this != TIMING_ON_TARGET

    /** True for the two "well done" lines. */
    val isPraise: Boolean get() = this == PERFECT_COPY || this == TIMING_ON_TARGET
}

class SendingAnalysis(
    rawMarks: List<KeyMark>,
    rawTarget: String,
    val keyType: SendingKeyType,
    characterWpm: Double,
    effectiveWpm: Double? = null
) {
    /** Mean and population standard deviation of one kind of interval. */
    class Stat(values: List<Double>) {
        val count: Int = values.size
        val mean: Double = if (values.isEmpty()) 0.0 else values.sum() / values.size
        val sd: Double = if (values.isEmpty()) 0.0
        else sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        /** Coefficient of variation (sd / mean), 0 when there is nothing. */
        val cv: Double get() = if (mean > 0) sd / mean else 0.0
    }

    /** One character as keyed: its pattern ("." / "-") and what it reads as. */
    data class SentCharacter(val pattern: String, val character: Char, val startMs: Double, val endMs: Double)

    /** One step of the alignment between the target and what was sent. */
    data class AlignmentOp(val kind: Kind, val expected: Char?, val sent: Char?, val sentPattern: String?) {
        enum class Kind { MATCH, SUBSTITUTE, MISSING, EXTRA }

        /** The fixture's compact form: "=H", "~87", "- " (missing space), "+9". */
        val code: String
            get() = when (kind) {
                Kind.MATCH -> "=${expected ?: '?'}"
                Kind.SUBSTITUTE -> "~${expected ?: '?'}${sent ?: '?'}"
                Kind.MISSING -> "-${expected ?: '?'}"
                Kind.EXTRA -> "+${sent ?: '?'}"
            }
    }

    val target: String = normalizedTarget(rawTarget)
    val targetCharacterWpm: Double = maxOf(5.0, characterWpm)
    val targetEffectiveWpm: Double = minOf(targetCharacterWpm, maxOf(1.0, effectiveWpm ?: targetCharacterWpm))
    /** The marks after contact-bounce clean-up. */
    val marks: List<KeyMark> = cleaned(rawMarks)

    val farnsworthFactor: Double
    val dits: Stat
    val dahs: Stat
    val unitMs: Double
    val sentCharacters: List<SentCharacter>
    val decodedText: String
    val elementGaps: Stat
    val characterGaps: Stat
    val wordGaps: Stat
    val characterGapValues: List<Double>
    val wordGapValues: List<Double>
    val characterGapHistogram: List<Int>
    val wordGapHistogram: List<Int>
    val characterWpm: Double
    val effectiveWpm: Double
    val alignment: List<AlignmentOp>
    val targetCharacterCount: Int
    val correctCharacterCount: Int
    val missingWordBreaks: Int
    val extraWordBreaks: Int
    val feedback: List<SendingFeedback>

    init {
        val timing = MorseTiming.farnsworth(targetCharacterWpm, targetEffectiveWpm)
        val k = timing.spacingUnit / timing.unit
        farnsworthFactor = k
        val targetUnit = 1200.0 / targetCharacterWpm

        // 1. Dit or dah.
        val durations = marks.map { it.durationMs }
        val isDah = classify(durations, targetUnit)
        dits = Stat(durations.filterIndexed { i, _ -> !isDah[i] })
        dahs = Stat(durations.filterIndexed { i, _ -> isDah[i] })
        val unit = when {
            dits.count > 0 -> dits.mean
            dahs.count > 0 -> dahs.mean / 3
            else -> targetUnit
        }
        unitMs = unit

        // 2. Gaps: inside a character, between characters, between words.
        val chars = mutableListOf<SentCharacter>()
        val words = mutableListOf<String>()
        val word = StringBuilder()
        val pattern = StringBuilder()
        var charStart = marks.firstOrNull()?.downMs ?: 0.0
        val intra = mutableListOf<Double>()
        val charGaps = mutableListOf<Double>()
        val wordGapsV = mutableListOf<Double>()
        var standardUnits = 0.0
        fun closeCharacter(endMs: Double) {
            if (pattern.isEmpty()) return
            val p = pattern.toString()
            val ch = MorseCode.characterForPattern(p) ?: MorseDecoder.unknownMarker
            chars.add(SentCharacter(p, ch, charStart, endMs))
            word.append(ch)
            pattern.setLength(0)
        }
        for ((i, m) in marks.withIndex()) {
            if (i > 0) {
                val gap = m.downMs - marks[i - 1].upMs
                val units = gap / unit
                if (units < INTRA_GAP_LIMIT) {
                    intra.add(units)
                    standardUnits += 1
                } else {
                    closeCharacter(marks[i - 1].upMs)
                    charStart = m.downMs
                    val spacing = gap / (unit * k)
                    if (spacing < WORD_GAP_LIMIT) {
                        charGaps.add(spacing)
                        standardUnits += 3
                    } else {
                        wordGapsV.add(spacing)
                        standardUnits += 7
                        words.add(word.toString())
                        word.setLength(0)
                    }
                }
            }
            pattern.append(if (isDah[i]) '-' else '.')
            standardUnits += if (isDah[i]) 3 else 1
        }
        marks.lastOrNull()?.let { closeCharacter(it.upMs) }
        if (word.isNotEmpty()) words.add(word.toString())
        sentCharacters = chars
        decodedText = words.joinToString(" ")
        elementGaps = Stat(intra)
        characterGaps = Stat(charGaps)
        wordGaps = Stat(wordGapsV)
        characterGapValues = charGaps
        wordGapValues = wordGapsV
        characterGapHistogram = histogram(charGaps, CHAR_GAP_BIN_EDGES)
        wordGapHistogram = histogram(wordGapsV, WORD_GAP_BIN_EDGES)
        this.characterWpm = if (marks.isEmpty()) 0.0 else 1200 / unit
        val elapsed = (marks.lastOrNull()?.upMs ?: 0.0) - (marks.firstOrNull()?.downMs ?: 0.0)
        this.effectiveWpm = if (elapsed > 0) 1200 * standardUnits / elapsed else 0.0

        // 3. Line the copy up against the target.
        val ops = align(target.toList(), decodedText.toList(), chars)
        alignment = ops
        targetCharacterCount = target.count { it != ' ' }
        correctCharacterCount = ops.count { it.kind == AlignmentOp.Kind.MATCH && it.expected != ' ' }
        missingWordBreaks = ops.count { it.kind == AlignmentOp.Kind.MISSING && it.expected == ' ' }
        extraWordBreaks = ops.count { it.kind == AlignmentOp.Kind.EXTRA && it.sent == ' ' }

        // 4. Plain-English feedback, in [SendingFeedback] order.
        val fb = mutableListOf<SendingFeedback>()
        val enough = marks.size >= 5
        val cw = targetCharacterWpm
        // A keyer sets the character speed itself, so speed is only judged
        // when the operator forms the elements.
        val speedJudged = enough && keyType.handTimesElements
        if (speedJudged && this.characterWpm < 0.85 * cw) fb.add(SendingFeedback.SLOWER_THAN_TARGET)
        if (speedJudged && this.characterWpm > 1.15 * cw) fb.add(SendingFeedback.FASTER_THAN_TARGET)
        if (keyType.handTimesElements && dits.count >= 3 && dahs.count >= 3) {
            val ratio = dahs.mean / dits.mean
            if (ratio < 2.5) fb.add(SendingFeedback.DAHS_SHORT)
            if (ratio > 3.5) fb.add(SendingFeedback.DAHS_LONG)
        }
        if (keyType.handTimesDits && dits.count >= 5 && dits.cv > 0.25) fb.add(SendingFeedback.DITS_UNEVEN)
        if (keyType.handTimesElements && dahs.count >= 5 && dahs.cv > 0.25) fb.add(SendingFeedback.DAHS_UNEVEN)
        if (keyType.handTimesElements && elementGaps.count >= 3) {
            if (elementGaps.mean < 0.7) fb.add(SendingFeedback.ELEMENT_GAPS_SHORT)
            if (elementGaps.mean > 1.5) fb.add(SendingFeedback.ELEMENT_GAPS_LONG)
        }
        if (characterGaps.count >= 3) {
            if (characterGaps.mean < 2.5) fb.add(SendingFeedback.CHAR_GAPS_SHORT)
            if (characterGaps.mean > 4.0) fb.add(SendingFeedback.CHAR_GAPS_LONG)
        }
        if (characterGaps.count >= 5 && characterGaps.cv > 0.3) fb.add(SendingFeedback.CHAR_GAPS_UNEVEN)
        if (wordGaps.count >= 2) {
            if (wordGaps.mean < 6) fb.add(SendingFeedback.WORD_GAPS_SHORT)
            if (wordGaps.mean > 9) fb.add(SendingFeedback.WORD_GAPS_LONG)
        }
        if (missingWordBreaks > 0) fb.add(SendingFeedback.WORDS_RUN_TOGETHER)
        if (extraWordBreaks > 0) fb.add(SendingFeedback.WORDS_SPLIT)
        if (targetCharacterCount > 0 && correctCharacterCount == targetCharacterCount &&
            ops.all { it.kind == AlignmentOp.Kind.MATCH }
        ) fb.add(SendingFeedback.PERFECT_COPY)
        if (enough && fb.none { it.isTiming }) fb.add(SendingFeedback.TIMING_ON_TARGET)
        feedback = fb
    }

    val accuracy: Double
        get() = if (targetCharacterCount > 0) correctCharacterCount.toDouble() / targetCharacterCount else 0.0

    /** Dah length over dit length, when both were sent. */
    val dahDitRatio: Double?
        get() = if (dits.count > 0 && dahs.count > 0 && dits.mean > 0) dahs.mean / dits.mean else null

    val isEmpty: Boolean get() = marks.isEmpty()

    /** One line of plain English for a feedback code, with this attempt's numbers in it. */
    fun message(code: SendingFeedback): String {
        fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
        fun pct(v: Double) = "${(v * 100).roundToInt()}%"
        val spacingNote = if (farnsworthFactor > 1.001) " (Farnsworth spacing units)" else ""
        val sent = characterWpm.roundToInt()
        val want = targetCharacterWpm.roundToInt()
        return when (code) {
            SendingFeedback.SLOWER_THAN_TARGET -> "You sent at about $sent WPM, slower than the $want WPM you set."
            SendingFeedback.FASTER_THAN_TARGET -> "You sent at about $sent WPM, faster than the $want WPM you set."
            SendingFeedback.DAHS_SHORT -> "Dahs are short: ${f1(dahDitRatio ?: 0.0)} dits long on average. Aim for 3."
            SendingFeedback.DAHS_LONG -> "Dahs are long: ${f1(dahDitRatio ?: 0.0)} dits long on average. Aim for 3."
            SendingFeedback.DITS_UNEVEN -> "Dit lengths vary a lot (±${pct(dits.cv)}). Aim for an even rhythm."
            SendingFeedback.DAHS_UNEVEN -> "Dah lengths vary a lot (±${pct(dahs.cv)}). Aim for an even rhythm."
            SendingFeedback.ELEMENT_GAPS_SHORT -> "Gaps inside characters are clipped: ${f1(elementGaps.mean)} units, aim for 1."
            SendingFeedback.ELEMENT_GAPS_LONG -> "Gaps inside characters are long: ${f1(elementGaps.mean)} units, aim for 1. Letters can break apart."
            SendingFeedback.CHAR_GAPS_SHORT -> "Characters run together: spacing averages ${f1(characterGaps.mean)} units$spacingNote, aim for 3."
            SendingFeedback.CHAR_GAPS_LONG -> "Character spacing is long: ${f1(characterGaps.mean)} units$spacingNote on average, aim for 3."
            SendingFeedback.CHAR_GAPS_UNEVEN -> "Character spacing is uneven (±${pct(characterGaps.cv)})."
            SendingFeedback.WORD_GAPS_SHORT -> "Word spacing is short: ${f1(wordGaps.mean)} units$spacingNote on average, aim for 7."
            SendingFeedback.WORD_GAPS_LONG -> "Word spacing is long: ${f1(wordGaps.mean)} units$spacingNote on average, aim for 7."
            SendingFeedback.WORDS_RUN_TOGETHER -> if (missingWordBreaks == 1)
                "One word break was too short, so two words ran together."
            else "$missingWordBreaks word breaks were too short, so words ran together."
            SendingFeedback.WORDS_SPLIT -> if (extraWordBreaks == 1)
                "One gap inside a word was long enough to read as a word break."
            else "$extraWordBreaks gaps inside words were long enough to read as word breaks."
            SendingFeedback.PERFECT_COPY -> "Every character copied correctly."
            SendingFeedback.TIMING_ON_TARGET -> "Element and spacing timing are on target."
        }
    }

    companion object {
        /** Key-down or key-up intervals shorter than this are contact bounce. */
        const val GLITCH_MS = 5.0
        /** Below this max/min duration ratio the marks are one kind of element. */
        const val SEPARATION_RATIO = 1.8
        /** A gap shorter than this many units is inside a character. */
        const val INTRA_GAP_LIMIT = 2.0
        /** A gap of at least this many spacing units is a word break. */
        const val WORD_GAP_LIMIT = 5.0
        /** Lower bin edges of the spacing histograms, in spacing units. */
        val CHAR_GAP_BIN_EDGES = listOf(1.75, 2.25, 2.75, 3.25, 3.75, 4.25)
        val CHAR_GAP_BIN_LABELS = listOf("<2", "2", "2.5", "3", "3.5", "4", "4.5+")
        val WORD_GAP_BIN_EDGES = listOf(5.5, 6.5, 7.5, 8.5, 9.5, 10.5)
        val WORD_GAP_BIN_LABELS = listOf("5", "6", "7", "8", "9", "10", "11+")
        /** Index of the ideal bin (3 and 7 units) in each histogram. */
        const val IDEAL_CHAR_GAP_BIN = 3
        const val IDEAL_WORD_GAP_BIN = 2

        /**
         * The target as the analysis compares against it: uppercase, whitespace
         * runs collapsed to one space, characters with no Morse pattern dropped.
         */
        fun normalizedTarget(text: String): String {
            val out = StringBuilder()
            var pendingSpace = false
            for (ch in text.uppercase(Locale.US)) {
                if (ch.isWhitespace()) {
                    pendingSpace = out.isNotEmpty()
                } else if (MorseCode.pattern(ch) != null) {
                    if (pendingSpace) { out.append(' '); pendingSpace = false }
                    out.append(ch)
                }
            }
            return out.toString()
        }

        /** Merge marks split by contact bounce and drop the bounce itself. */
        fun cleaned(raw: List<KeyMark>): List<KeyMark> {
            val sorted = raw.filter { it.upMs > it.downMs }.sortedBy { it.downMs }
            val merged = mutableListOf<KeyMark>()
            for (m in sorted) {
                val last = merged.lastOrNull()
                if (last != null && m.downMs - last.upMs < GLITCH_MS) {
                    merged[merged.size - 1] = last.copy(upMs = maxOf(last.upMs, m.upMs))
                } else {
                    merged.add(m)
                }
            }
            return merged.filter { it.durationMs >= GLITCH_MS }
        }

        /**
         * Dit (false) or dah (true) for each duration. Two-means on the log
         * durations, seeded at the extremes; when the extremes are closer than
         * [SEPARATION_RATIO] (or the clusters end up that close) everything is
         * one kind, split at two target units.
         */
        internal fun classify(durations: List<Double>, targetUnitMs: Double): List<Boolean> {
            val lo = durations.minOrNull() ?: return emptyList()
            val hi = durations.max()
            if (lo <= 0) return durations.map { false }
            val single = durations.map { it >= 2 * targetUnitMs }
            if (hi / lo < SEPARATION_RATIO) return single
            val logs = durations.map { ln(it) }
            var cDit = ln(lo)
            var cDah = ln(hi)
            var isDah = logs.map { it >= (cDit + cDah) / 2 }
            for (round in 0 until 20) {
                val d = logs.filterIndexed { i, _ -> !isDah[i] }
                val h = logs.filterIndexed { i, _ -> isDah[i] }
                if (d.isEmpty() || h.isEmpty()) break
                cDit = d.sum() / d.size
                cDah = h.sum() / h.size
                val next = logs.map { it >= (cDit + cDah) / 2 }
                if (next == isDah) break
                isDah = next
            }
            return if (exp(cDah - cDit) < SEPARATION_RATIO) single else isDah
        }

        internal fun histogram(values: List<Double>, edges: List<Double>): List<Int> {
            val bins = MutableList(edges.size + 1) { 0 }
            for (v in values) bins[edges.count { v >= it }] += 1
            return bins
        }

        /**
         * Edit-distance alignment. Substituting one character for another costs
         * 1; a space never substitutes for a character (it is a word break, not a
         * letter). The backtrace prefers the diagonal, then a missing target
         * token, then an extra sent one, so ties resolve the same on both ports.
         */
        internal fun align(target: List<Char>, sent: List<Char>, sentCharacters: List<SentCharacter>): List<AlignmentOp> {
            val n = target.size
            val m = sent.size
            fun cost(a: Char, b: Char): Int? = when {
                a == b -> 0
                (a == ' ') != (b == ' ') -> null
                else -> 1
            }
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in 0..n) dp[i][0] = i
            for (j in 0..m) dp[0][j] = j
            for (i in 1..n) {
                for (j in 1..m) {
                    var best = minOf(dp[i - 1][j], dp[i][j - 1]) + 1
                    cost(target[i - 1], sent[j - 1])?.let { best = minOf(best, dp[i - 1][j - 1] + it) }
                    dp[i][j] = best
                }
            }
            // Sent position → keyed pattern (spaces have none).
            val patternAt = arrayOfNulls<String>(m)
            var k = 0
            for ((j, ch) in sent.withIndex()) {
                if (ch == ' ') continue
                if (k < sentCharacters.size) patternAt[j] = sentCharacters[k].pattern
                k++
            }
            val ops = mutableListOf<AlignmentOp>()
            var i = n
            var j = m
            while (i > 0 || j > 0) {
                val c = if (i > 0 && j > 0) cost(target[i - 1], sent[j - 1]) else null
                if (c != null && dp[i][j] == dp[i - 1][j - 1] + c) {
                    val kind = if (c == 0) AlignmentOp.Kind.MATCH else AlignmentOp.Kind.SUBSTITUTE
                    ops.add(AlignmentOp(kind, target[i - 1], sent[j - 1], patternAt[j - 1]))
                    i--; j--
                } else if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) {
                    ops.add(AlignmentOp(AlignmentOp.Kind.MISSING, target[i - 1], null, null))
                    i--
                } else {
                    ops.add(AlignmentOp(AlignmentOp.Kind.EXTRA, null, sent[j - 1], patternAt[j - 1]))
                    j--
                }
            }
            return ops.reversed()
        }
    }
}

/** Texts to send, and the pangram list (#241's own example among them). */
object SendingTargets {
    val pangrams: List<String> = listOf(
        "JACKDAWS LOVE MY BIG SPHINX OF QUARTZ",
        "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG",
        "PACK MY BOX WITH FIVE DOZEN LIQUOR JUGS",
        "SPHINX OF BLACK QUARTZ JUDGE MY VOW",
        "HOW VEXINGLY QUICK DAFT ZEBRAS JUMP",
        "THE FIVE BOXING WIZARDS JUMP QUICKLY"
    )
}

/**
 * What the Sending Analyzer remembers between attempts: per-character and
 * per-pair error rates, the mix-ups (target → what was sent), and a short
 * history of attempts. Kept apart from the copy-side [ConfusionMatrix] that
 * drives the receive drills: a sending slip is a different skill. The app
 * layer (SendingStore) persists it as JSON.
 */
class SendingRecord {
    data class Tally(val attempts: Int = 0, val misses: Int = 0) {
        val missRate: Double get() = if (attempts > 0) misses.toDouble() / attempts else 0.0
    }

    data class Attempt(
        val epochMs: Long,
        val accuracy: Double,
        val characterWpm: Double,
        val effectiveWpm: Double,
        val characters: Int,
        val characterGapMean: Double?,
        val wordGapMean: Double?
    )

    /** Keyed by the character (as a one-character string). */
    val characters: MutableMap<String, Tally> = mutableMapOf()
    /** Keyed by two adjacent characters of a target word ("LL"). */
    val pairs: MutableMap<String, Tally> = mutableMapOf()
    val mixups = ConfusionMatrix()
    /** Newest last, capped at [ATTEMPT_LIMIT]. */
    val attempts: MutableList<Attempt> = mutableListOf()

    val isEmpty: Boolean get() = characters.isEmpty() && attempts.isEmpty()

    /**
     * Fold one attempt in. Every target character is an attempt at that
     * character; one sent as anything else, or not sent, is a miss. Each
     * adjacent pair inside a target word is an attempt at the pair, missed when
     * either half is. A substitution by a real character is a mix-up.
     */
    fun record(analysis: SendingAnalysis, epochMs: Long = System.currentTimeMillis()) {
        if (analysis.targetCharacterCount <= 0 || analysis.isEmpty) return
        val word = mutableListOf<Pair<Char, Boolean>>()
        fun closeWord() {
            for (p in 0 until word.size - 1) {
                val key = "${word[p].first}${word[p + 1].first}"
                val t = pairs[key] ?: Tally()
                val miss = !(word[p].second && word[p + 1].second)
                pairs[key] = Tally(t.attempts + 1, t.misses + if (miss) 1 else 0)
            }
            word.clear()
        }
        for (op in analysis.alignment) {
            val expected = op.expected ?: continue
            if (expected == ' ') { closeWord(); continue }
            val ok = op.kind == SendingAnalysis.AlignmentOp.Kind.MATCH
            val key = expected.toString()
            val t = characters[key] ?: Tally()
            characters[key] = Tally(t.attempts + 1, t.misses + if (ok) 0 else 1)
            word.add(expected to ok)
            val sent = op.sent
            if (op.kind == SendingAnalysis.AlignmentOp.Kind.SUBSTITUTE && sent != null &&
                sent != MorseDecoder.unknownMarker
            ) mixups.record(expected, sent)
        }
        closeWord()
        attempts.add(
            Attempt(
                epochMs, analysis.accuracy, analysis.characterWpm, analysis.effectiveWpm,
                analysis.targetCharacterCount,
                if (analysis.characterGaps.count > 0) analysis.characterGaps.mean else null,
                if (analysis.wordGaps.count > 0) analysis.wordGaps.mean else null
            )
        )
        while (attempts.size > ATTEMPT_LIMIT) attempts.removeAt(0)
    }

    /**
     * Characters with at least one miss, worst miss rate first (then most
     * misses, then key order), among those tried [minAttempts] times.
     */
    fun problemCharacters(minAttempts: Int = 3, limit: Int = 6): List<Pair<String, Tally>> =
        worst(characters, minAttempts, limit)

    /** Same, for adjacent pairs. */
    fun problemPairs(minAttempts: Int = 3, limit: Int = 6): List<Pair<String, Tally>> =
        worst(pairs, minAttempts, limit)

    companion object {
        const val ATTEMPT_LIMIT = 30

        private fun worst(map: Map<String, Tally>, minAttempts: Int, limit: Int): List<Pair<String, Tally>> =
            map.entries
                .filter { it.value.attempts >= minAttempts && it.value.misses > 0 }
                .sortedWith(
                    compareByDescending<Map.Entry<String, Tally>> { it.value.missRate }
                        .thenByDescending { it.value.misses }
                        .thenBy { it.key }
                )
                .take(limit)
                .map { it.key to it.value }
    }
}
