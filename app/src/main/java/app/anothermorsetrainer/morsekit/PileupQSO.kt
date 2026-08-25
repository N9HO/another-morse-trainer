package app.anothermorsetrainer.morsekit

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Translated from MorseKit/PileupQSO.swift.
 *
 * Pure-logic pileup/contest QSO simulator. No audio, no UI — it decides who
 * transmits what in response to your sends, so it can be unit-tested.
 */

// MARK: - Modes

/**
 * The QSO/contest flavours the simulator can run. Each has its own exchange.
 *
 * Swift's raw-valued `enum: String, CaseIterable` becomes a Kotlin `enum class`
 * carrying its [code] (the Swift rawValue, for persistence).
 */
enum class QSOContestMode(val code: String) {
    SingleCaller("singleCaller"),   // one station, ragchew-lite (call + name)
    Pota("pota"),                   // RST + state
    BasicContest("basicContest"),   // RST + serial number
    Cwt("cwt"),                     // CWops: name + number (members) / name + state
    Sst("sst"),                     // K1USN SST: name + state
    Mst("mst"),                     // ICWC MST: name + serial number
    Sprint("sprint"),               // NCCC/NA Sprint: serial + name + state
    FieldDay("fieldDay");           // ARRL Field Day: class + section

    val id: String get() = code

    val label: String
        get() = when (this) {
            SingleCaller -> "Single Caller"
            Pota -> "POTA Activator"
            BasicContest -> "Basic Contest"
            Cwt -> "CWT"
            Sst -> "K1USN SST"
            Mst -> "ICWC MST"
            Sprint -> "NS Sprint"
            FieldDay -> "Field Day"
        }

    val blurb: String
        get() = when (this) {
            SingleCaller -> "One station answers — copy their call and name. The gentle warmup."
            Pota -> "Work a park pileup — copy each hunter's call and their state."
            BasicContest -> "A generic CW sprint — copy callsign and serial number."
            Cwt -> "CWops mini-test — copy name and member number (or state)."
            Sst -> "K1USN Slow Speed Test — copy name and state, taken easy."
            Mst -> "ICWC Medium Speed Test — copy name and serial number."
            Sprint -> "NCCC/NA Sprint — copy serial number, name, and state."
            FieldDay -> "ARRL Field Day — copy class and ARRL section (e.g. 2A OH)."
        }

    /** Whether the exchange conventionally carries a signal report. */
    val includesRST: Boolean
        get() = when (this) {
            Pota, BasicContest, SingleCaller -> true
            Cwt, Sst, Mst, Sprint, FieldDay -> false
        }

    /** A single caller never piles up. */
    val isPileup: Boolean get() = this != SingleCaller

    companion object {
        val allCases: List<QSOContestMode> = entries.toList()
    }
}

// MARK: - Exchange tokens

enum class TokenKind { ALPHA, NUMERIC, RAW }

data class ExchToken(
    val value: String,      // canonical value (real digits, upper-case)
    val kind: TokenKind
)

/**
 * Builds one station's exchange: what it transmits, what you must copy, and a
 * human-readable form for the log.
 */
data class ExchangeSpec(
    val sentText: String,            // Morse text the station sends (cut numbers applied)
    val requiredTokens: List<ExchToken>,
    val display: String              // true values, for the log
) {
    companion object {
        fun build(
            mode: QSOContestMode,
            cutEnabled: Boolean,
            cutDigits: Set<Char>,
            rstRequired: Boolean,
            rng: Random
        ): ExchangeSpec {
            fun num(s: String): String = if (cutEnabled) CutNumbers.encode(s, cutDigits) else s
            val states = MorseData.qthList

            var info: List<ExchToken> = emptyList()     // informational tokens (graded)
            var sentInfo = ""
            var dispInfo = ""

            when (mode) {
                QSOContestMode.SingleCaller -> {
                    val name = ContestData.names.randomOrNull(rng) ?: "BOB"
                    info = listOf(ExchToken(name, TokenKind.ALPHA))
                    sentInfo = "OP $name $name"
                    dispInfo = name
                }

                QSOContestMode.Pota -> {
                    val st = states.randomOrNull(rng) ?: "OH"
                    info = listOf(ExchToken(st, TokenKind.ALPHA))
                    sentInfo = "$st $st"
                    dispInfo = st
                }

                QSOContestMode.BasicContest -> {
                    val serial = rng.nextInt(1, 1000).toString().padStart(3, '0')
                    info = listOf(ExchToken(serial, TokenKind.NUMERIC))
                    sentInfo = num(serial)
                    dispInfo = serial
                }

                QSOContestMode.Cwt -> {
                    val name = ContestData.names.randomOrNull(rng) ?: "BOB"
                    if (rng.nextDouble(1.0) < 0.7) {
                        val n = rng.nextInt(1, 3301).toString()
                        info = listOf(ExchToken(name, TokenKind.ALPHA), ExchToken(n, TokenKind.NUMERIC))
                        sentInfo = "$name ${num(n)}"
                        dispInfo = "$name $n"
                    } else {
                        val st = states.randomOrNull(rng) ?: "OH"
                        info = listOf(ExchToken(name, TokenKind.ALPHA), ExchToken(st, TokenKind.ALPHA))
                        sentInfo = "$name $st"
                        dispInfo = "$name $st"
                    }
                }

                QSOContestMode.Sst -> {
                    val name = ContestData.names.randomOrNull(rng) ?: "BOB"
                    val st = states.randomOrNull(rng) ?: "OH"
                    info = listOf(ExchToken(name, TokenKind.ALPHA), ExchToken(st, TokenKind.ALPHA))
                    sentInfo = "$name $st"
                    dispInfo = "$name $st"
                }

                QSOContestMode.Mst -> {
                    // ICWC MST: name + a running serial number (no RST). Each station
                    // sends its own QSO count, so a plausible serial varies per caller.
                    val name = ContestData.names.randomOrNull(rng) ?: "BOB"
                    val n = rng.nextInt(1, 1000).toString()
                    info = listOf(ExchToken(name, TokenKind.ALPHA), ExchToken(n, TokenKind.NUMERIC))
                    sentInfo = "$name ${num(n)}"
                    dispInfo = "$name $n"
                }

                QSOContestMode.Sprint -> {
                    // NCCC/NA Sprint: serial number + operator name + state (no RST).
                    val serial = rng.nextInt(1, 1000).toString()
                    val name = ContestData.names.randomOrNull(rng) ?: "BOB"
                    val st = states.randomOrNull(rng) ?: "OH"
                    info = listOf(
                        ExchToken(serial, TokenKind.NUMERIC),
                        ExchToken(name, TokenKind.ALPHA),
                        ExchToken(st, TokenKind.ALPHA)
                    )
                    sentInfo = "${num(serial)} $name $st"
                    dispInfo = "$serial $name $st"
                }

                QSOContestMode.FieldDay -> {
                    val cls = "${rng.nextInt(1, 13)}${ContestData.fieldDayCategories.randomOrNull(rng) ?: 'A'}"
                    val sec = ContestData.arrlSections.randomOrNull(rng) ?: "OH"
                    info = listOf(ExchToken(cls, TokenKind.RAW), ExchToken(sec, TokenKind.ALPHA))
                    sentInfo = "$cls $sec"
                    dispInfo = "$cls $sec"
                }
            }

            // RST is always sent as "5NN" where the exchange carries one; it's only
            // *graded* when the user opted into copying it.
            val sent = if (mode.includesRST) "5NN $sentInfo" else sentInfo
            val disp = if (mode.includesRST) "599 $dispInfo" else dispInfo
            val required = info.toMutableList()
            if (mode.includesRST && rstRequired) {
                required.add(0, ExchToken("599", TokenKind.NUMERIC))
            }
            return ExchangeSpec(sentText = sent, requiredTokens = required, display = disp)
        }
    }
}

// MARK: - Config

enum class BustBehavior(val code: String) {
    Forgiving("forgiving"),   // matches repeat; total bust -> whole pileup re-calls
    Silence("silence"),       // matches repeat; total bust -> silence
    Nearest("nearest");       // total bust -> the closest call nudges once

    val id: String get() = code

    val label: String
        get() = when (this) {
            Forgiving -> "Forgiving (pileup re-calls)"
            Silence -> "Strict (silence on a bust)"
            Nearest -> "Nudge (closest re-calls)"
        }

    companion object {
        val allCases: List<BustBehavior> = entries.toList()
    }
}

/**
 * Everything the engine needs to run a session. AppModel derives this from
 * AppSettings + the operator's tone.
 */
data class PileupConfig(
    var mode: QSOContestMode = QSOContestMode.Pota,
    var maxStations: Int = 4,
    var minWPM: Double = 18.0,
    var maxWPM: Double = 28.0,
    var toneSpread: Double = 250.0,        // Hz of zero-beat<->offset spread
    var minVolume: Float = 0.5f,
    var maxVolume: Float = 1.0f,
    var minDelay: Double = 0.1,            // seconds
    var maxDelay: Double = 1.2,            // seconds
    var qsbEnabled: Boolean = false,
    var qrnLevel: Float = 0f,              // 0 = off
    var cutNumbersEnabled: Boolean = false,
    var cutDigits: Set<Char> = CutNumbers.commonDefaults,
    var rstRequired: Boolean = false,
    var bustBehavior: BustBehavior = BustBehavior.Forgiving,
    var giveUpEnabled: Boolean = false,
    var giveUpMin: Int = 3,
    var giveUpMax: Int = 6,
    var formats: List<CallsignFormat> = CallsignFormat.commonDefaults,
    var usOnly: Boolean = true
)

// MARK: - Engine

/**
 * Pure-logic pileup QSO engine. No audio, no UI — it decides who transmits
 * what in response to your sends, so it can be unit-tested. AppModel turns its
 * [Voice] lists into mixed audio.
 */
class PileupEngine(
    config: PileupConfig = PileupConfig(),
    rng: Random = Random.Default
) {

    data class Station(
        val id: Int,
        val call: String,
        var wpm: Double,                // mutable so QRS/QRQ can change it
        val toneOffset: Double,
        val volume: Float,
        val qsb: Boolean,
        val exchange: ExchangeSpec,
        val patience: Int,
        var attempts: Int = 0
    )

    /** One transmission to mix into the pileup audio. */
    data class Voice(
        val text: String,
        val wpm: Double,
        val toneOffset: Double,
        val volume: Float,
        val qsb: Boolean,
        val delay: Double               // seconds
    )

    sealed class Phase {
        object Idle : Phase()
        object Pileup : Phase()
        data class Working(val id: Int) : Phase()
        data class ReadyToLog(val id: Int) : Phase()
    }

    sealed class Action {
        data class Play(val voices: List<Voice>) : Action()
        object Silence : Action()
        data class Logged(val call: String) : Action()
    }

    data class LoggedQSO(
        val id: Int,
        val call: String,
        val exchange: String,
        val wpm: Int
    )

    // State
    var phase: Phase = Phase.Idle
        private set
    var stations: List<Station> = emptyList()
        private set
    var log: List<LoggedQSO> = emptyList()
        private set
    var qsoCount: Int = 0
        private set
    var bustCount: Int = 0
        private set

    private var config: PileupConfig = config
    private val rng: Random = rng
    private var nextID = 1

    fun update(config: PileupConfig) { this.config = config }

    /** Clear all state and start a fresh session with [config]. */
    fun reset(config: PileupConfig) {
        this.config = config
        stations = emptyList()
        log = emptyList()
        qsoCount = 0
        bustCount = 0
        nextID = 1
        phase = Phase.Idle
    }

    val summary: String
        get() = if (qsoCount == 0) config.mode.label else "$qsoCount in the log"

    val activeCount: Int get() = stations.size

    val workingStation: Station?
        get() = when (val p = phase) {
            is Phase.Working -> stations.firstOrNull { it.id == p.id }
            is Phase.ReadyToLog -> stations.firstOrNull { it.id == p.id }
            else -> null
        }

    /**
     * The canonical answer for the station being worked (for a reveal/hint and
     * for tests): required tokens joined with spaces, in true digits.
     */
    val expectedCopy: String?
        get() = workingStation?.let { st ->
            st.exchange.requiredTokens.joinToString(" ") { it.value }
        }

    /** Clean-copy accuracy: completed QSOs vs. completed + busts. */
    val accuracy: Double
        get() {
            val total = qsoCount + bustCount
            return if (total == 0) 1.0 else qsoCount.toDouble() / total.toDouble()
        }

    // MARK: Calling CQ

    /** Call CQ: top the pileup up with fresh callers and have them all answer. */
    fun callCQ(): Action {
        if (config.mode.isPileup) {
            val target = closedInt(rng, maxOf(1, config.maxStations / 2), maxOf(1, config.maxStations))
            val current = stations.toMutableList()
            while (current.size < target) { current.add(makeStation(current)) }
            stations = current
        } else if (stations.isEmpty()) {
            stations = listOf(makeStation(emptyList()))
        }
        phase = Phase.Pileup
        if (stations.isEmpty()) return Action.Silence
        return Action.Play(stations.map { callVoice(it) })
    }

    // MARK: Sending

    fun send(raw: String): Action {
        val text = raw.trim().uppercase()
        // Operating commands act in any phase and don't count as misses.
        if (isQRS(text)) return adjustSpeed(-6.0)
        if (isQRQ(text)) return adjustSpeed(6.0)
        return when (val p = phase) {
            is Phase.Idle -> callCQ()
            is Phase.Pileup -> handlePileupSend(text)
            is Phase.Working -> handleExchangeSend(text, p.id)
            is Phase.ReadyToLog -> {
                if (text.isEmpty() || isSignOff(text)) doLog(p.id)
                else handlePileupSend(text)
            }
        }
    }

    /** The "?" / "AGN" button: ask for a repeat appropriate to the phase. */
    fun repeatRequest(): Action {
        when (val p = phase) {
            is Phase.Idle -> return callCQ()
            is Phase.Pileup -> {
                if (stations.isEmpty()) return Action.Silence
                return Action.Play(stations.map { callVoice(it) })
            }
            is Phase.Working -> return repeatForWorking(p.id)
            is Phase.ReadyToLog -> return repeatForWorking(p.id)
        }
    }

    private fun repeatForWorking(id: Int): Action {
        val i = index(id) ?: return Action.Silence
        bump(i)
        if (quit(i)) return stationQuits(i)
        return Action.Play(listOf(exchangeVoice(stations[i])))
    }

    /** Log the station currently ready to be logged (the TU button). */
    fun logCurrent(): Action {
        val p = phase
        if (p is Phase.ReadyToLog) return doLog(p.id)
        if (p is Phase.Working) {
            val i = index(p.id)
            if (i != null) {
                // Allow an early TU only once the exchange was copied; otherwise no-op.
                @Suppress("UNUSED_EXPRESSION") i
            }
        }
        return Action.Silence
    }

    // MARK: Pileup handling

    private fun handlePileupSend(text: String): Action {
        phase = Phase.Pileup
        val frag = fragment(text)
        if (frag.isEmpty()) {
            // A bare "?" / AGN / empty send asks the whole pileup to call again.
            if (stations.isEmpty()) return Action.Silence
            return Action.Play(stations.map { callVoice(it) })
        }
        // Exact full-call match -> straight to the exchange.
        val exact = stations.indexOfFirst { it.call == frag }
        if (exact >= 0) {
            return beginExchange(exact)
        }
        // Only stations whose call STARTS WITH the fragment answer — sending
        // "W1" brings back the W1s, not everyone. The impatient may quit first.
        var matched = stations.indices.filter { stations[it].call.startsWith(frag) }
        if (config.giveUpEnabled && matched.isNotEmpty()) {
            for (idx in matched) bump(idx)
            val quitters = matched.filter { quit(it) }
            if (quitters.isNotEmpty()) {
                removeStations(quitters.map { stations[it].id })
                matched = stations.indices.filter { stations[it].call.startsWith(frag) }
            }
        }
        if (matched.isNotEmpty()) {
            return Action.Play(matched.map { callVoice(stations[it]) })
        }
        // No one matches the call you sent — you miscopied the call. Count it
        // against clean-copy accuracy (issue #30: earlier missed attempts were
        // being ignored, so a QSO logged after retries showed 100%), then
        // respond per the busted-call setting. A non-empty fragment that DID
        // prefix-match a station above is a legitimate partial call, not a bust.
        if (stations.isNotEmpty()) bustCount += 1
        return when (config.bustBehavior) {
            BustBehavior.Forgiving -> {
                if (stations.isEmpty()) Action.Silence
                else Action.Play(stations.map { callVoice(it) })
            }
            BustBehavior.Silence -> Action.Silence
            BustBehavior.Nearest -> {
                val n = nearestStation(frag) ?: return Action.Silence
                Action.Play(listOf(callVoice(stations[n])))
            }
        }
    }

    private fun beginExchange(i: Int): Action {
        phase = Phase.Working(stations[i].id)
        return Action.Play(listOf(exchangeVoice(stations[i])))
    }

    // MARK: Exchange handling

    private fun handleExchangeSend(text: String, id: Int): Action {
        val i = index(id) ?: run { phase = Phase.Pileup; return Action.Silence }
        if (text.isEmpty() || isRepeat(text)) {
            bump(i)
            if (quit(i)) return stationQuits(i)
            return Action.Play(listOf(exchangeVoice(stations[i])))
        }
        // Bailing to another station you can hear better.
        val frag = fragment(text)
        if (frag != stations[i].call) {
            val j = stations.indexOfFirst { it.call == frag }
            if (j >= 0) return beginExchange(j)
        }
        if (grade(text, stations[i].exchange.requiredTokens)) {
            phase = Phase.ReadyToLog(id)
            return Action.Silence
        }
        bustCount += 1
        bump(i)
        if (quit(i)) return stationQuits(i)
        return Action.Play(listOf(exchangeVoice(stations[i])))
    }

    private fun doLog(id: Int): Action {
        val i = index(id) ?: run {
            phase = if (stations.isEmpty()) Phase.Idle else Phase.Pileup
            return Action.Silence
        }
        val s = stations[i]
        log = log + LoggedQSO(id = s.id, call = s.call, exchange = s.exchange.display, wpm = s.wpm.roundToInt())
        qsoCount += 1
        stations = stations.toMutableList().apply { removeAt(i) }
        phase = if (stations.isEmpty()) Phase.Idle else Phase.Pileup
        return Action.Logged(s.call)
    }

    /**
     * QRS (slow down) / QRQ (speed up): change the speed of whoever you're
     * working — or the whole pileup — and have them send again at the new rate.
     */
    private fun adjustSpeed(delta: Double): Action {
        fun clamp(w: Double): Double = minOf(45.0, maxOf(10.0, w))
        return when (val p = phase) {
            is Phase.Working -> adjustWorkingSpeed(p.id, delta, ::clamp)
            is Phase.ReadyToLog -> adjustWorkingSpeed(p.id, delta, ::clamp)
            is Phase.Pileup -> {
                if (stations.isEmpty()) return Action.Silence
                for (i in stations.indices) { stations[i].wpm = clamp(stations[i].wpm + delta) }
                Action.Play(stations.map { callVoice(it) })
            }
            is Phase.Idle -> Action.Silence
        }
    }

    private fun adjustWorkingSpeed(id: Int, delta: Double, clamp: (Double) -> Double): Action {
        val i = index(id) ?: return Action.Silence
        stations[i].wpm = clamp(stations[i].wpm + delta)
        phase = Phase.Working(id)
        return Action.Play(listOf(exchangeVoice(stations[i])))
    }

    private fun stationQuits(i: Int): Action {
        stations = stations.toMutableList().apply { removeAt(i) }
        phase = if (stations.isEmpty()) Phase.Idle else Phase.Pileup
        if (stations.isEmpty()) return Action.Silence
        return Action.Play(stations.map { callVoice(it) })
    }

    // MARK: Grading

    private fun grade(input: String, tokens: List<ExchToken>): Boolean {
        var user = input.uppercase()
            .split(*fieldSeparators)
            .filter { it.isNotEmpty() }
            .toMutableList()
        // Drop a leading signal report the operator typed but wasn't asked to
        // copy ("599 OH" -> "OH"). Only for exchanges that actually send an RST,
        // and only when there's a surplus token to drop — otherwise a serial
        // that merely looks like a report (the NS Sprint's serial, or a basic
        // contest serial in the 500s) would be mistaken for one and stripped.
        if (config.mode.includesRST && !config.rstRequired && user.size > tokens.size) {
            val first = user.firstOrNull()
            if (first != null && isRSTLike(first)) {
                user.removeAt(0)
            }
        }
        // Stations send each exchange element twice for copyability ("OH OH")
        // and prefix a name with the filler "OP" — so a faithful copy of what
        // was *heard* carries more tokens than the exchange requires. Drop the
        // filler and collapse immediately-repeated tokens before counting. No
        // real exchange has two genuinely-identical adjacent tokens, so this is
        // lossless for the de-duplicated form too.
        user.removeAll { it == "OP" }
        val collapsed = mutableListOf<String>()
        for (tok in user) {
            if (collapsed.lastOrNull() != tok) collapsed.add(tok)
        }
        user = collapsed
        if (user.size == tokens.size && user.zip(tokens).all { (u, t) -> tokenMatches(u, t) }) {
            return true
        }
        // Fallback: the operator ran the fields together with no separator at
        // all ("9BEWA" for "9B EWA"). Peel each required token's width off the
        // alphanumeric stream in order. Only reached once the separated parse
        // above has failed, so it can't turn a real miss into a match.
        return gradeGlued(user.joinToString(""), tokens)
    }

    companion object {
        /**
         * Field separators an operator might type between exchange elements. Any
         * run of these breaks tokens, so "9B/EWA" and "9B-EWA" copy like "9B EWA".
         */
        val fieldSeparators = charArrayOf(' ', '/', '-', ',', '.')

        /** Match run-together input by consuming each token's expected width in turn. */
        fun gradeGlued(input: String, tokens: List<ExchToken>): Boolean {
            val stream = input.uppercase().filter { it.isLetterOrDigit() }
            var idx = 0
            for (t in tokens) {
                val n = t.value.length
                if (n <= 0 || idx + n > stream.length) return false
                if (!tokenMatches(stream.substring(idx, idx + n), t)) return false
                idx += n
            }
            return idx == stream.length   // every character accounted for, nothing extra
        }

        fun tokenMatches(user: String, token: ExchToken): Boolean {
            return when (token.kind) {
                TokenKind.ALPHA -> {
                    val u = user.uppercase().filter { it.isLetter() }
                    u == token.value.uppercase()
                }
                TokenKind.NUMERIC -> {
                    val u = CutNumbers.decodeDigits(user)
                    val a = u.toIntOrNull()
                    val b = token.value.toIntOrNull()
                    if (a != null && b != null) a == b else u == token.value
                }
                TokenKind.RAW -> {
                    user.uppercase().filter { !it.isWhitespace() } == token.value.uppercase()
                }
            }
        }

        fun isRSTLike(s: String): Boolean {
            val d = CutNumbers.decodeDigits(s)
            return d.length == 3 && d.firstOrNull() == '5'
        }

        fun isRepeat(s: String): Boolean {
            val t = s.uppercase()
            return t == "?" || t == "AGN" || t == "AGN?" || t == "QRZ"
        }

        fun isQRS(s: String): Boolean {
            val t = s.uppercase()
            return t == "QRS" || t == "QRS PSE" || t == "PSE QRS" || t == "QRS QRS"
        }

        fun isQRQ(s: String): Boolean = s.uppercase() == "QRQ"

        /**
         * A callsign fragment from typed input: upper-cased, spaces removed, and the
         * trailing query mark(s) stripped (so "W1?" queries the W1 prefix).
         */
        fun fragment(text: String): String {
            var f = text.uppercase().replace(" ", "")
            while (f.endsWith("?")) { f = f.dropLast(1) }
            return f
        }

        fun isSignOff(s: String): Boolean {
            val t = s.uppercase()
            return t == "TU" || t == "TU GL" || t == "73" || t == "TU 73" || t == "R TU"
        }

        /**
         * Closed-range double like Swift's `Double.random(in: a...b)`: tolerates
         * `a == b` (Kotlin's [Random.nextDouble] throws on an empty range).
         */
        internal fun closedDouble(rng: Random, a: Double, b: Double): Double =
            if (a >= b) a else rng.nextDouble(a, b)

        /**
         * Closed-range int like Swift's `Int.random(in: a...b)`: tolerates
         * `a == b` (Kotlin's [Random.nextInt] throws on an empty range).
         */
        internal fun closedInt(rng: Random, a: Int, b: Int): Int =
            if (a >= b) a else rng.nextInt(a, b + 1)
    }

    // MARK: Station factory & helpers

    /**
     * Builds a fresh station with a callsign not already present in [existing].
     * (Swift read `stations` directly; in Kotlin `stations` may be mid-update,
     * so the in-progress list is passed in to dedupe against.)
     */
    private fun makeStation(existing: List<Station>): Station {
        var call = ""
        do {
            call = CallsignGenerator.generate(
                formats = if (config.formats.isEmpty()) CallsignFormat.commonDefaults else config.formats,
                usOnly = config.usOnly, rng = rng
            )
        } while (existing.any { it.call == call })
        val exch = ExchangeSpec.build(
            mode = config.mode,
            cutEnabled = config.cutNumbersEnabled,
            cutDigits = config.cutDigits,
            rstRequired = config.rstRequired,
            rng = rng
        )
        val wpm = closedDouble(rng, minOf(config.minWPM, config.maxWPM), maxOf(config.minWPM, config.maxWPM))
        val offset = if (config.toneSpread <= 0) 0.0
                     else closedDouble(rng, -config.toneSpread, config.toneSpread)
        val vol = closedDouble(
            rng,
            minOf(config.minVolume, config.maxVolume).toDouble(),
            maxOf(config.minVolume, config.maxVolume).toDouble()
        ).toFloat()
        val qsb = config.qsbEnabled && rng.nextDouble(1.0) < 0.5
        val patience = closedInt(rng, minOf(config.giveUpMin, config.giveUpMax), maxOf(config.giveUpMin, config.giveUpMax))
        val station = Station(
            id = nextID, call = call, wpm = wpm, toneOffset = offset,
            volume = vol, qsb = qsb, exchange = exch, patience = patience
        )
        nextID += 1
        return station
    }

    private fun callVoice(s: Station): Voice =
        Voice(
            text = s.call, wpm = s.wpm, toneOffset = s.toneOffset, volume = s.volume,
            qsb = s.qsb, delay = closedDouble(rng, config.minDelay, maxOf(config.minDelay, config.maxDelay))
        )

    private fun exchangeVoice(s: Station): Voice =
        Voice(
            text = s.exchange.sentText, wpm = s.wpm, toneOffset = s.toneOffset,
            volume = s.volume, qsb = s.qsb, delay = 0.2
        )

    private fun index(id: Int): Int? = stations.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    private fun bump(i: Int) { stations[i].attempts += 1 }
    private fun quit(i: Int): Boolean = config.giveUpEnabled && stations[i].attempts > stations[i].patience
    private fun removeStations(ids: List<Int>) {
        stations = stations.filter { it.id !in ids }
    }

    private fun nearestStation(frag: String): Int? {
        if (stations.isEmpty()) return null
        return stations.indices.minByOrNull { MorseDistance.distance(frag, stations[it].call) }
    }
}
