package app.anothermorsetrainer.morsekit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// The hunt simulator (#313), the pure logic: a seeded random source, the copy
// model (per-character decode odds from level over noise, time overlap and
// pitch offset), the hunters, the activator's state machine and the grader.
// docs/operating-procedure-design.md ("The hunt simulator") is the spec; every
// number below is pinned by fixtures/hunt-model.json and fixtures/hunt-sessions.json,
// which the Swift MorseKitCheck and the Android tree's own copy of this file
// read too. Twin of ios/Sources/MorseKit/HuntSim.swift and of
// android/…/morsekit/HuntSim.kt. Not ranked and no leaderboard entry
// (maintainer, 2026-10-03): the grading is about procedure, not speed.
//
// Operations are kept to + - * / min max on doubles, in a fixed order, so the
// three trees agree to the last bit, not just to a tolerance.

/** SplitMix64: the same sequence in all three trees for one seed. */
class HuntRandom(seed: Long) {
    private var state: Long = seed

    fun next64(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** In [0, 1): the top 53 bits over 2^53. */
    fun nextDouble(): Double = (next64() ushr 11).toDouble() / 9007199254740992.0

    /** Inclusive on both ends. */
    fun nextInt(lo: Int, hi: Int): Int = lo + (nextDouble() * (hi - lo + 1).toDouble()).toInt()

    fun range(a: Double, b: Double): Double = a + (b - a) * nextDouble()
}

enum class HuntType(val raw: String, val weight: Int) {
    GOOD("good", 40),
    REPEATER("repeater", 12),
    TAIL_ENDER("tailEnder", 10),
    ZERO_BEATER("zeroBeater", 10),
    QUESTION_SENDER("questionSender", 6),
    WRONG_CALL("wrongCall", 8),
    TUNE_UP("tuneUp", 4),
    QRP("qrp", 10);

    companion object {
        fun fromRaw(raw: String): HuntType? = entries.firstOrNull { it.raw == raw }
    }
}

/** What kind of window the activator has just opened. */
enum class HuntKind(val raw: String) {
    /** After CQ, QRZ?, SRI QRZ? or a finished contact: everyone may call. */
    CALLING("calling"),
    /** After a partial or a doubtful call with "?": those it fits answer. */
    QUERY("query"),
    /** The picked station answers with its exchange (or the activator asked AGN?). */
    EXCHANGE("exchange"),
    /** The activator's TU 73 E E to you: your sign-off. */
    CLOSE("close"),
    /** The activator sent <AS>: wait. */
    AS("as");

    companion object {
        fun fromRaw(raw: String): HuntKind? = entries.firstOrNull { it.raw == raw }
    }
}

/** The difficulty settings. */
data class HuntConfig(
    val pileupSize: Int = 4,
    val wpmMin: Double = 18.0,
    val wpmMax: Double = 24.0,
    val activatorWpm: Double = 20.0,
    val qsb: Boolean = false,
    /** Band noise, 0 (none) to about 0.3. */
    val qrn: Double = 0.0,
    /** Percent of hunters you cannot hear at all. */
    val inaudiblePercent: Double = 20.0,
    /** Chance a character the activator fails to copy comes out as a wrong one, not a gap. */
    val bustRate: Double = 0.1,
    val toneSpread: Double = 250.0,
    /** How strong you are at the activator. */
    val youLevel: Double = 0.6
) {
    companion object {
        val EASY = HuntConfig(pileupSize = 2, wpmMin = 14.0, wpmMax = 18.0, activatorWpm = 16.0, inaudiblePercent = 0.0, bustRate = 0.0)
        val MEDIUM = HuntConfig()
        val HARD = HuntConfig(
            pileupSize = 8, wpmMin = 22.0, wpmMax = 30.0, activatorWpm = 26.0,
            qsb = true, qrn = 0.15, inaudiblePercent = 40.0, bustRate = 0.25
        )
        const val MIN_PILEUP = 1
        const val MAX_PILEUP = 10
    }
}

data class HuntHunter(
    val id: Int,
    val call: String,
    val type: HuntType,
    val state: String,
    val wpm: Double,
    /** Hertz from the activator's frequency. */
    val offset: Double,
    /** Strength at the activator, 0–1. */
    val levelAct: Double,
    /** Strength at you, 0–1; 0 = you cannot hear them. */
    val levelYou: Double,
    val qsb: Boolean,
    val repeats: Int
)

/** One hunter transmission in a window. */
data class HuntVoice(
    val id: Int,
    val call: String,
    val type: HuntType,
    val text: String,
    val wpm: Double,
    val offsetHz: Double,
    val levelAct: Double,
    val levelYou: Double,
    val qsb: Boolean,
    /** Seconds after the activator stops. */
    val delay: Double
)

/** What the activator just sent, and who answers. */
data class HuntTurn(
    val round: Int,
    val kind: HuntKind,
    val text: String,
    val query: String,
    val voices: List<HuntVoice>,
    val wpm: Double
)

/** What you keyed in a window: text, your speed, seconds after the activator stopped, and your XIT offset. */
data class HuntYouSend(val text: String, val wpm: Double, val delay: Double, val offsetHz: Double)

/** One graded point, tied to the lesson that teaches it. */
data class HuntPoint(val code: String, val good: Boolean, val lesson: OpLesson)

/** Who sent what and how the model copied it. [who] is "you" or a hunter's id. */
data class HuntHeard(val who: String, val sent: String, val decoded: String)

/** One finished window, for the debrief. */
data class HuntRecord(
    val round: Int,
    val turn: HuntTurn,
    val you: HuntYouSend?,
    val late: Boolean,
    val activatorHeard: List<HuntHeard>,
    val youHeard: List<HuntHeard>,
    val grade: List<HuntPoint>
)

/** What the grader needs to know about a window. */
data class HuntGradeContext(
    val kind: HuntKind,
    val query: String,
    val queryForYou: Boolean,
    val pickedIsYou: Boolean,
    val pileup: Boolean,
    val myCall: String,
    val myState: String,
    val actCall: String,
    val pickedCall: String?,
    val activatorText: String
)

/** The grader's view of what you keyed. */
data class HuntGradeSend(val text: String, val delay: Double, val offsetHz: Double, val late: Boolean)

/** A transmission as the copy model sees it. */
class HuntTx(
    val who: Int,
    val text: String,
    val wpm: Double,
    val start: Double,
    val level: Double,
    val pitch: Double
) {
    val end: Double = HuntModel.txEnd(text, wpm, start)
}

data class HuntDecoded(val text: String, val minOdds: Double)

/** The copy model, the query rules and the grader. */
object HuntModel {
    const val YOU = -1

    const val NOISE_FLOOR = 0.05
    const val MAX_ODDS = 0.98
    const val MASK_WEIGHT = 0.9
    const val ZERO_BEAT_HZ = 20.0
    const val SEP_FAR_START = 0.6
    const val SEP_SLOPE = 0.0025
    const val SEP_MIN = 0.15
    const val QSB_FACTOR = 0.6
    const val DOUBT_ODDS = 0.6
    const val LISTEN_SECONDS = 3.5
    const val AS_PROB = 0.1
    const val SRI_AFTER = 2
    const val MAX_EXCHANGE_TRIES = 3
    const val MAX_QUERIES = 2
    const val MAX_ROUNDS = 60
    const val CALL_PROB = 0.8
    const val PICK_JITTER = 0.5
    const val PILEUP_MIN = 3
    const val OFFSET_MIN_HZ = 20.0
    const val OFFSET_MAX_HZ = 100.0

    val prefixes = listOf("K", "W", "N", "AA", "AB", "KC", "KD", "KE", "KF", "WA", "WB", "WD")
    val states = listOf("AL", "FL", "GA", "TN", "NC", "OH", "WI", "TX", "CO", "NY")

    // Text

    fun norm(t: String): String = t.uppercase().split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.joinToString(" ")

    private fun stripQ(q: String): String = OperatingProcedure.normalizeCall(q).trimEnd('?')

    private fun lev1(a0: String, b0: String): Boolean {
        var a = a0
        var b = b0
        if (a == b) return false
        if (abs(a.length - b.length) > 1) return false
        if (a.length == b.length) return a.indices.count { a[it] != b[it] } == 1
        if (a.length > b.length) { val t = a; a = b; b = t }
        for (i in b.indices) if (b.removeRange(i, i + 1) == a) return true
        return false
    }

    /** A full-looking query ("K1ABD?") one edit from your call. */
    fun nearMissQuery(q: String, call: String): Boolean {
        val s = stripQ(q)
        return s.length >= 4 && lev1(s, call)
    }

    /** Whether a hunter with [call] should answer the activator's [query]: the partial rule, or a near miss with "?". */
    fun answers(query: String, call: String): Boolean =
        OperatingProcedure.partialMatches(query, call) || nearMissQuery(query, call)

    /** The query's characters occur in the call in order, with anything between. */
    fun looseMatch(query: String, call: String): Boolean {
        val s = stripQ(query)
        var i = 0
        for (ch in call) if (i < s.length && s[i] == ch) i++
        return i == s.length
    }

    // Timing

    private fun units(c: Char): Int {
        val p = MorseCode.table[c] ?: MorseCode.optionalPunctuation[c] ?: return 0
        return p.count { it == '.' } + 3 * p.count { it != '.' } + p.length - 1
    }

    /** Each non-space character with its start and end second. */
    data class CharSpan(val ch: Char, val start: Double, val end: Double)

    fun charSpans(text: String, wpm: Double, start: Double): List<CharSpan> {
        val unit = 1.2 / wpm
        var cum = 0
        var prev = false
        val out = mutableListOf<CharSpan>()
        for (c in text) {
            if (c == ' ') { cum += 7; prev = false; continue }
            if (prev) cum += 3
            val s = cum
            cum += units(c)
            out += CharSpan(c, start + s.toDouble() * unit, start + cum.toDouble() * unit)
            prev = true
        }
        return out
    }

    fun txEnd(text: String, wpm: Double, start: Double): Double =
        charSpans(text, wpm, start).lastOrNull()?.end ?: start

    // Copy model

    /** How much a caller [diffHz] away blurs into this one: 1 within zero beat, less the further off. */
    fun sep(diffHz: Double): Double =
        if (diffHz <= ZERO_BEAT_HZ) 1.0 else max(SEP_MIN, SEP_FAR_START - (diffHz - ZERO_BEAT_HZ) * SEP_SLOPE)

    data class Other(val overlap: Double, val level: Double, val diffHz: Double)

    fun oddsFor(level: Double, qrn: Double, others: List<Other>): Double {
        var o = min(MAX_ODDS, level / (level + NOISE_FLOOR + qrn))
        for (x in others) {
            val mask = min(1.0, 2.0 * x.level / (x.level + level))
            o = o * (1.0 - MASK_WEIGHT * x.overlap * sep(x.diffHz) * mask)
        }
        return o
    }

    fun effLevel(level: Double, qsb: Boolean): Double = level * (if (qsb) QSB_FACTOR else 1.0)

    /** How the listener copies transmission [i] of [txs]: a char each, or a "?" gap, or (at the bust rate) a wrong one. */
    fun decode(i: Int, txs: List<HuntTx>, qrn: Double, bust: Double, rng: HuntRandom): HuntDecoded {
        val me = txs[i]
        val spans = charSpans(me.text, me.wpm, me.start)
        var k = 0
        var mn = 1.0
        val out = StringBuilder()
        for (c in me.text) {
            if (c == ' ') { out.append(' '); continue }
            val cs = spans[k].start
            val ce = spans[k].end
            k++
            val others = ArrayList<Other>()
            for (j in txs.indices) {
                if (j == i) continue
                val o = txs[j]
                val ov = max(0.0, min(ce, o.end) - max(cs, o.start)) / (ce - cs)
                others += Other(ov, o.level, abs(me.pitch - o.pitch))
            }
            val od = oddsFor(me.level, qrn, others)
            mn = min(mn, od)
            val u = rng.nextDouble()
            if (u < od) out.append(c)
            else {
                val v = rng.nextDouble()
                out.append(if (v < bust) OperatingProcedure.shift(c, 1) else '?')
            }
        }
        return HuntDecoded(out.toString(), mn)
    }

    /** Repeated copies (a repeater's CALL CALL) fill each other's gaps, position by position. */
    fun mergeCopies(copies: List<String>): String {
        val n = copies[0].length
        if (copies.any { it.length != n }) return copies[0]
        val out = StringBuilder()
        for (k in 0 until n) {
            var ch = '?'
            for (c in copies) if (c[k] != '?') { ch = c[k]; break }
            out.append(ch)
        }
        return out.toString()
    }

    fun candidateOf(decoded: String): String {
        val words = decoded.split(' ')
        return if (words.size > 1) mergeCopies(words) else words[0]
    }

    /** Worth acting on: three characters or more, with a decoded digit and a decoded letter. */
    fun isCandidate(s: String): Boolean =
        s.length >= 3 && s.any { it in '0'..'9' } && s.any { it in 'A'..'Z' }

    /** The longest unbroken run of decoded characters, if it has two or more; the first on ties. */
    fun longestRun(s: String): String {
        var best = ""
        var cur = ""
        for (c in "$s?") {
            if (c == '?') {
                if (cur.length > best.length) best = cur
                cur = ""
            } else cur += c
        }
        return if (best.length >= 2) best else ""
    }

    // Exchange and sign-off

    enum class ExchangeForm(val raw: String) { PRIMARY("primary"), BK("bk"), NONE("none") }

    /** `5NN ST 73` or `5NN ST BK`, after any leading calls and DE. */
    fun exchangeForm(text: String, state: String, myCall: String, actCall: String): ExchangeForm {
        val n = norm(text)
        var toks = if (n.isEmpty()) emptyList() else n.split(' ')
        while (toks.isNotEmpty() && (toks[0] == myCall || toks[0] == actCall || toks[0] == "DE")) toks = toks.drop(1)
        if (toks.size == 3 && (toks[0] == "5NN" || toks[0] == "599") && toks[1] == state) {
            if (toks[2] == "73") return ExchangeForm.PRIMARY
            if (toks[2] == "BK") return ExchangeForm.BK
        }
        return ExchangeForm.NONE
    }

    /** The activator logs it: a copy with no gap or bust that holds a report and your state. */
    fun exchangeAccepts(decoded: String, state: String): Boolean {
        val toks = norm(decoded).split(' ')
        return '?' !in decoded && toks.any { it == "5NN" || it == "599" } && state in toks
    }

    fun signoffForm(text: String, myCall: String): Boolean {
        val n = norm(text)
        var toks = if (n.isEmpty()) emptyList() else n.split(' ')
        while (toks.isNotEmpty() && toks[0] == myCall) toks = toks.drop(1)
        return toks == listOf("E", "E") || toks == listOf("73", "E", "E")
    }

    // Hunters

    fun makeHunter(cfg: HuntConfig, rng: HuntRandom, id: Int, taken: List<String>): HuntHunter {
        var call: String
        while (true) {
            val pre = prefixes[rng.nextInt(0, prefixes.size - 1)]
            val dig = rng.nextInt(0, 9)
            val n = rng.nextInt(2, 3)
            val suf = StringBuilder()
            repeat(n) { suf.append('A' + rng.nextInt(0, 25)) }
            call = "$pre$dig$suf"
            if (call !in taken) break
        }
        val roll = rng.nextInt(0, 99)
        var acc = 0
        var type = HuntType.GOOD
        for (t in HuntType.entries) {
            acc += t.weight
            if (roll < acc) { type = t; break }
        }
        val state = states[rng.nextInt(0, states.size - 1)]
        val wpm = rng.range(cfg.wpmMin, cfg.wpmMax)
        val spread = rng.range(-cfg.toneSpread, cfg.toneSpread)
        val offset = if (type == HuntType.ZERO_BEATER) 0.0 else spread
        val a = rng.nextDouble()
        val la = if (type == HuntType.QRP) 0.04 + 0.06 * a else 0.25 + 0.75 * a
        val yf = rng.range(0.6, 1.2)
        var ly = min(1.0, la * yf)
        val h = rng.nextDouble()
        if (h < cfg.inaudiblePercent / 100.0) ly = 0.0
        val q = rng.nextDouble()
        val qsb = cfg.qsb && q < 0.5
        val rp = rng.nextInt(2, 3)
        val repeats = if (type == HuntType.REPEATER) rp else 1
        return HuntHunter(id, call, type, state, wpm, offset, la, ly, qsb, repeats)
    }

    fun callText(h: HuntHunter): String = List(h.repeats) { h.call }.joinToString(" ")

    // The grader

    /** Grade one window. [you] is what you keyed there, or null for silence. */
    fun grade(ctx: HuntGradeContext, you: HuntGradeSend?): List<HuntPoint> {
        val out = ArrayList<HuntPoint>()
        fun add(code: String, good: Boolean, lesson: OpLesson) { out += HuntPoint(code, good, lesson) }
        val me = ctx.myCall
        val sent = you != null && norm(you.text).isNotEmpty()
        if (!sent) {
            if (ctx.kind == HuntKind.QUERY && ctx.queryForYou && answers(ctx.query, me)) add("partial.silent", false, OpLesson.PARTIAL)
            if (ctx.kind == HuntKind.EXCHANGE && ctx.pickedIsYou) add("exchange.silent", false, OpLesson.EXCHANGE)
            if (ctx.kind == HuntKind.CLOSE) add("signoff.missing", false, OpLesson.EXCHANGE)
            return out
        }
        you!!
        val text = norm(you.text)
        val toks = text.split(' ')
        if (you.late) { add("timing.late", false, OpLesson.WHEN); return out }
        fun offsetCheck() {
            val off = abs(you.offsetHz)
            if (ctx.pileup) {
                if (off < OFFSET_MIN_HZ) add("offset.zeroBeat", false, OpLesson.OFFSET)
                else if (off > OFFSET_MAX_HZ) add("offset.far", false, OpLesson.OFFSET)
                else add("offset.ok", true, OpLesson.OFFSET)
            } else {
                if (off > OFFSET_MAX_HZ) add("offset.far", false, OpLesson.OFFSET)
                else add("offset.ok", true, OpLesson.OFFSET)
            }
        }
        fun callCheck(goodCode: String) {
            val n = toks.count { it == me }
            if (n == 0) add("call.missing", false, OpLesson.ONCE)
            else if (n > 1) add("call.repeated", false, OpLesson.ONCE)
            else if (toks.size > 1) add("call.extra", false, OpLesson.ONCE)
            else add(goodCode, true, OpLesson.ONCE)
        }
        when (ctx.kind) {
            HuntKind.AS -> add("as.ignored", false, OpLesson.SIGNALS)
            HuntKind.CALLING -> { add("timing.ok", true, OpLesson.WHEN); callCheck("call.once"); offsetCheck() }
            HuntKind.QUERY -> {
                if (answers(ctx.query, me)) {
                    callCheck(
                        if (!nearMissQuery(ctx.query, me) || OperatingProcedure.partialMatches(ctx.query, me)) "partial.answered"
                        else "near.answered"
                    )
                    offsetCheck()
                } else add("partial.notMine", false, OpLesson.PARTIAL)
            }
            HuntKind.EXCHANGE -> {
                if (ctx.pickedIsYou) {
                    val f = exchangeForm(text, ctx.myState, me, ctx.actCall)
                    if (f != ExchangeForm.NONE) add("exchange.ok", true, OpLesson.EXCHANGE)
                    else add("exchange.form", false, OpLesson.EXCHANGE)
                } else {
                    val pc = ctx.pickedCall
                    if (!pc.isNullOrEmpty() && pc != me && lev1(pc, me) && '?' !in ctx.activatorText) add("near.noQuestion", false, OpLesson.ME)
                    else add("timing.over", false, OpLesson.WHEN)
                }
            }
            HuntKind.CLOSE -> {
                val ok = signoffForm(text, me)
                if (ok) add("signoff.ok", true, OpLesson.EXCHANGE) else add("signoff.form", false, OpLesson.EXCHANGE)
            }
        }
        return out
    }
}

enum class HuntOutcome(val raw: String) { LOGGED("logged"), TIMEOUT("timeout"), QUIT("quit") }

/**
 * One run of the simulator: you against a pileup, for one contact. [respond]
 * takes what you keyed in the current window (or null) and opens the next one.
 */
class HuntSession(val config: HuntConfig, seed: Long, val myCall: String, val myState: String) {
    private val rng = HuntRandom(seed)
    private val actor = OperatingProcedure.activator(myCall)
    var pool: List<HuntHunter> = emptyList(); private set
    private var nextId = 0
    private var failed = 0
    private var queries = 0
    private var exTries = 0
    private var queryFor: Int? = null
    private var lastAS = false
    private var pending = ""

    /** The call the activator is working, if any. */
    var pickedCall: String? = null; private set
    var round = 0; private set
    var ended = false; private set
    var outcome: HuntOutcome? = null; private set
    val records = ArrayList<HuntRecord>()
    var turn: HuntTurn; private set

    val activatorCall: String get() = actor.call
    val activatorState: String get() = actor.state

    init {
        repeat(config.pileupSize) { addHunter() }
        turn = makeTurn(HuntKind.CALLING, "CQ POTA DE ${actor.call} K")
    }

    private fun addHunter() {
        val taken = pool.map { it.call } + myCall
        pool = pool + HuntModel.makeHunter(config, rng, nextId, taken)
        nextId++
    }

    private fun makeTurn(kind: HuntKind, text: String, query: String = ""): HuntTurn {
        val v = ArrayList<Triple<HuntHunter, String, Double>>()
        when (kind) {
            HuntKind.CALLING -> for (h in pool) {
                val u = rng.nextDouble()
                if (u >= HuntModel.CALL_PROB) continue
                val t = when (h.type) {
                    HuntType.REPEATER -> HuntModel.callText(h)
                    HuntType.QUESTION_SENDER -> "?"
                    HuntType.TUNE_UP -> "TTTT"
                    else -> h.call
                }
                v += Triple(h, t, rng.range(0.2, 1.4))
            }
            HuntKind.QUERY -> for (h in pool) {
                if (HuntModel.answers(query, h.call) || (h.type == HuntType.WRONG_CALL && HuntModel.looseMatch(query, h.call))) {
                    v += Triple(h, HuntModel.callText(h), rng.range(0.2, 1.4))
                }
            }
            HuntKind.EXCHANGE -> {
                val ph = pool.firstOrNull { it.call == pickedCall }
                if (ph != null) v += Triple(ph, "5NN ${ph.state} 73", rng.range(0.2, 0.5))
                for (h in pool) {
                    if (h.type == HuntType.TAIL_ENDER && h.id != ph?.id) v += Triple(h, h.call, rng.range(0.0, 0.4))
                }
            }
            else -> {}
        }
        val voices = v.map { (h, t, d) ->
            HuntVoice(h.id, h.call, h.type, t, h.wpm, h.offset, h.levelAct, h.levelYou, h.qsb, d)
        }
        return HuntTurn(round, kind, text, query, voices, config.activatorWpm)
    }

    private fun toCalling(text: String) {
        if (!lastAS && rng.nextDouble() < HuntModel.AS_PROB) {
            lastAS = true
            pending = text
            turn = makeTurn(HuntKind.AS, "<AS>")
        } else {
            lastAS = false
            turn = makeTurn(HuntKind.CALLING, text)
        }
    }

    private fun fail() {
        failed++
        queries = 0
        pickedCall = null
        val text: String
        if (failed >= HuntModel.SRI_AFTER) { failed = 0; text = "SRI QRZ?" } else text = "QRZ?"
        toCalling(text)
    }

    private fun startExchange(call: String) {
        pickedCall = call
        exTries = 0
        queries = 0
        turn = makeTurn(HuntKind.EXCHANGE, "$call 5NN ${actor.state} ${actor.state} BK")
    }

    /** Stop early: the run ends as [HuntOutcome.QUIT]. */
    fun quit() {
        if (!ended) { ended = true; outcome = HuntOutcome.QUIT }
    }

    /** Whether the pileup is big enough that the offset rule is 20–100 Hz. */
    val isPileup: Boolean get() = pool.size >= HuntModel.PILEUP_MIN

    fun respond(you: HuntYouSend?) {
        if (ended) return
        val cfg = config
        val t = turn
        round++
        val txs = ArrayList<HuntTx>()
        for (v in t.voices) txs += HuntTx(v.id, v.text, v.wpm, v.delay, HuntModel.effLevel(v.levelAct, v.qsb), v.offsetHz)
        var yt: HuntTx? = null
        var late = false
        val sent = you != null && HuntModel.norm(you.text).isNotEmpty()
        if (sent) {
            you!!
            if (you.delay > HuntModel.LISTEN_SECONDS) late = true
            else {
                yt = HuntTx(HuntModel.YOU, HuntModel.norm(you.text), you.wpm, you.delay, cfg.youLevel, you.offsetHz)
                txs += yt
            }
        }
        val dec = txs.indices.map { HuntModel.decode(it, txs, cfg.qrn, cfg.bustRate, rng) }
        val hv = t.voices.filter { it.levelYou > 0.0 }
        val htx = hv.map { HuntTx(it.id, it.text, it.wpm, it.delay, HuntModel.effLevel(it.levelYou, it.qsb), it.offsetHz) }
        val ydec = htx.indices.map { HuntModel.decode(it, htx, cfg.qrn, cfg.bustRate, rng) }
        val pickedIsYou = pickedCall == myCall
        val ctx = HuntGradeContext(
            kind = t.kind, query = t.query, queryForYou = queryFor == HuntModel.YOU, pickedIsYou = pickedIsYou,
            pileup = isPileup, myCall = myCall, myState = myState, actCall = actor.call,
            pickedCall = pickedCall, activatorText = t.text
        )
        val g = HuntModel.grade(ctx, you?.let { HuntGradeSend(it.text, it.delay, it.offsetHz, late) })
        fun who(id: Int) = if (id == HuntModel.YOU) "you" else id.toString()
        records += HuntRecord(
            round = round, turn = t, you = you, late = late,
            activatorHeard = txs.indices.map { HuntHeard(who(txs[it].who), txs[it].text, dec[it].text) },
            youHeard = htx.indices.map { HuntHeard(who(htx[it].who), htx[it].text, ydec[it].text) },
            grade = g
        )
        when (t.kind) {
            HuntKind.CLOSE -> { ended = true; outcome = HuntOutcome.LOGGED; return }
            HuntKind.AS -> {
                lastAS = false
                turn = makeTurn(HuntKind.CALLING, pending)
            }
            HuntKind.CALLING, HuntKind.QUERY -> {
                class Cand(val tx: HuntTx, val s: String, val minOdds: Double)
                val cands = ArrayList<Cand>()
                for (i in txs.indices) {
                    val sc = HuntModel.candidateOf(dec[i].text)
                    if (HuntModel.isCandidate(sc)) cands += Cand(txs[i], sc, dec[i].minOdds)
                }
                if (cands.isEmpty()) fail()
                else {
                    var best: Cand? = null
                    var bs = 0.0
                    for (c in cands) {
                        val score = c.tx.level + rng.nextDouble() * HuntModel.PICK_JITTER
                        if (best == null || score > bs) { bs = score; best = c }
                    }
                    best!!
                    val sc = best.s
                    queryFor = best.tx.who
                    if ('?' !in sc) {
                        if (!OperatingProcedure.isValidCall(sc)) fail()
                        else if (best.minOdds < HuntModel.DOUBT_ODDS && queries < HuntModel.MAX_QUERIES) {
                            queries++
                            turn = makeTurn(HuntKind.QUERY, "$sc?", "$sc?")
                        } else startExchange(sc)
                    } else {
                        val run = HuntModel.longestRun(sc)
                        if (run.isEmpty() || queries >= HuntModel.MAX_QUERIES) fail()
                        else {
                            queries++
                            turn = makeTurn(HuntKind.QUERY, "$run?", "$run?")
                        }
                    }
                }
            }
            HuntKind.EXCHANGE -> {
                var ptxIndex = -1
                if (pickedCall == myCall) {
                    if (yt != null) ptxIndex = txs.indexOf(yt)
                } else {
                    val ph = pool.firstOrNull { it.call == pickedCall }
                    if (ph != null) for (i in txs.indices) if (txs[i].who == ph.id) ptxIndex = i
                }
                if (ptxIndex < 0) fail()
                else {
                    val tx = txs[ptxIndex]
                    val d = dec[ptxIndex]
                    val ok = if (pickedCall == myCall) HuntModel.exchangeAccepts(d.text, myState) else d.text == tx.text
                    if (ok) {
                        if (pickedCall == myCall) {
                            turn = makeTurn(HuntKind.CLOSE, "$myCall TU 73 E E")
                        } else {
                            val done = pickedCall!!
                            pool = pool.filter { it.call != done }
                            addHunter()
                            failed = 0
                            pickedCall = null
                            queryFor = null
                            toCalling("$done TU 73 E E")
                        }
                    } else {
                        exTries++
                        if (exTries >= HuntModel.MAX_EXCHANGE_TRIES) fail()
                        else turn = makeTurn(HuntKind.EXCHANGE, "AGN?")
                    }
                }
            }
        }
        if (!ended && round >= HuntModel.MAX_ROUNDS) { ended = true; outcome = HuntOutcome.TIMEOUT }
    }
}
