package app.anothermorsetrainer.morsekit

import kotlin.math.abs
import kotlin.math.max

// CW Operating Procedure (#294, #295): on-air etiquette for hunting POTA
// activators, one rule at a time — eight lessons of concept, demo and quick
// scenarios (the first is #294's zero beat / RIT / XIT / offset lesson, with
// its pileup demo and a tune-to-zero-beat drill), and a "What should you do?"
// mode over the lessons' scenarios. docs/operating-procedure-design.md is the
// spec; every rule below is pinned by fixtures/operating-procedure.json, which
// the Swift MorseKitCheck reads too. Twin of
// ios/Sources/MorseKit/OperatingProcedure.swift (and of the desktop tree's own
// copy of this file).
//
// Call and state validation are this file's own copy of First Four's rules,
// so the section never depends on First Four's port.

/**
 * The eight lessons, in the order the lesson list recommends. [raw] is the
 * fixture's name. Offsetting (#294) comes first, straight after First Four
 * (maintainer, 2026-10-03).
 */
enum class OpLesson(val raw: String) {
    OFFSET("offset"),
    SIGNALS("signals"),
    WHEN("when"),
    ONCE("once"),
    PARTIAL("partial"),
    ME("me"),
    EXCHANGE("exchange"),
    MISTAKE("mistake");

    companion object {
        fun fromRaw(raw: String): OpLesson? = entries.firstOrNull { it.raw == raw }
    }
}

/** What a scenario offers the learner. */
sealed class OpChoice {
    /** Key this text. */
    data class Send(val text: String) : OpChoice()
    /** Stay silent and listen. */
    data object Silent : OpChoice()
    /** A named answer; the words are each platform's own, the key is pinned. */
    data class Option(val key: String) : OpChoice()

    val isAction: Boolean get() = this !is Option
}

/**
 * One quick question: a clip (what the activator just sent; may be empty), a
 * generated detail the situation text mentions (may be empty), and the
 * choices, **the primary right answer first**. [accepted] lists every choice
 * that counts as right (the exchange has two accepted forms). The screen
 * shuffles the choices.
 */
data class OpScenario(
    val id: String,
    val lesson: OpLesson,
    val clip: String,
    val detail: String = "",
    val choices: List<OpChoice>,
    val accepted: List<Int> = listOf(0)
) {
    /** The primary right answer. */
    val correct: OpChoice get() = choices[0]
    /** Every choice is send or stay silent: these make up "What should you do?". */
    val isAction: Boolean get() = choices.all { it.isAction }

    /** True when picking choice [index] (in [choices] order) is right. */
    fun accepts(index: Int): Boolean = index in accepted
    /** True when picking [choice] is right. */
    fun accepts(choice: OpChoice): Boolean = accepts(choices.indexOf(choice))
}

/** A demo clip: an example of right or wrong (or just "listen"), as a short transcript. */
data class OpDemo(val kind: Kind, val lines: List<Line>) {
    enum class Kind(val raw: String) { LISTEN("listen"), RIGHT("right"), WRONG("wrong") }
    enum class Who(val raw: String) { ACTIVATOR("activator"), YOU("you") }
    data class Line(val who: Who, val text: String)
}

/** One caller in the pileup demo, ready for the mixer. */
data class OpPileupVoice(
    val text: String,
    val pitch: Double,
    val wpm: Double,
    val gain: Double,
    val delay: Double,
    val isYou: Boolean
)

data class OpStation(val call: String, val state: String)

/** The rules: constants, validation, generated texts, scenarios, demos and the offset maths. */
object OperatingProcedure {

    // Constants (defaults the maintainer can change; see the design note)

    /** Scenarios dealt per "What should you do?" run. */
    const val SCENARIO_RUN_LENGTH = 10
    /** Right answers in a row that pass the offsetting lesson's zero-beat drill. */
    const val ZERO_BEAT_STREAK_TO_PASS = 3
    /** How close to the activator's frequency counts as zero beat, in hertz. */
    const val ZERO_BEAT_TOLERANCE_HZ = 20.0
    /** The lowest pitch anything plays at — the Pileup Runner's floor too. */
    const val MINIMUM_PITCH_HZ = 200.0
    const val MINIMUM_WPM = 5.0
    /** Where you call in the pileup demo's second and third passes. */
    const val DEMO_YOUR_OFFSET_HZ = 80.0
    /** The activator's offset in the RIT demo. */
    const val RIT_DEMO_STATION_OFFSET_HZ = 200.0
    /** The RIT demo's control: ± this, in steps of [RIT_STEP_HZ]. */
    const val RIT_RANGE_HZ = 300.0
    const val RIT_STEP_HZ = 10.0
    /**
     * Where an error goes in a clip or a choice: `<ERR>` (any shape, picked
     * when it plays) or `<ERR:n>` (row n of [errorVariants]). Not a prosign:
     * an error can sound like anything, and the lesson teaches recognising
     * it, not copying one shape (maintainer, 2026-10-03).
     */
    const val ERROR_TOKEN = "<ERR>"
    /** How an error is shown: a word, never a fixed run of dots. */
    const val ERROR_DISPLAY = "[error]"
    /** Stands in for a pileup-demo caller whose call is the learner's own. */
    const val REPLACEMENT_CALLER = "KC2VWM"
    /** The callsign Settings ships with — a placeholder, not the learner's. */
    const val DEFAULT_CALL = "W1AW"

    /** Where the offsetting drill starts the activator, round by round (hertz). */
    val drillStarts: List<Double> = listOf(180.0, -120.0, 250.0, -70.0, 90.0, -210.0, 140.0, -260.0)
    /** The tuning buttons, in hertz. */
    val knobSteps: List<Double> = listOf(-50.0, -10.0, 10.0, 50.0)

    // Stations

    /** First Four's first two activators: the scenarios use the first that is not the learner. */
    val activators: List<OpStation> = listOf(OpStation("K4RTZ", "NC"), OpStation("W0PQA", "CO"))
    val otherHunters: List<String> = listOf("W8KDP", "KE0RJ")

    fun activator(call: String): OpStation {
        val me = normalizeCall(call)
        return activators.firstOrNull { it.call != me } ?: activators[0]
    }

    fun otherHunter(call: String): String {
        val me = normalizeCall(call)
        return otherHunters.firstOrNull { it != me } ?: otherHunters[0]
    }

    // Call and state

    fun normalizeCall(raw: String): String = raw.uppercase().filterNot { it.isWhitespace() }
    fun normalizeState(raw: String): String = raw.uppercase().filterNot { it.isWhitespace() }

    private fun isLetter(c: Char) = c in 'A'..'Z'
    private fun isDigit(c: Char) = c in '0'..'9'

    /** 3–10 characters of A–Z, 0–9 and "/", with a letter and a digit. */
    fun isValidCall(raw: String): Boolean {
        val call = normalizeCall(raw)
        if (call.length !in 3..10) return false
        if (!call.all { isLetter(it) || isDigit(it) || it == '/' }) return false
        return call.any(::isLetter) && call.any(::isDigit)
    }

    /** 2–3 letters. */
    fun isValidState(raw: String): Boolean {
        val state = normalizeState(raw)
        return state.length in 2..3 && state.all(::isLetter)
    }

    /** The saved call, unless it is the settings placeholder. */
    fun prefillCall(saved: String): String {
        val call = normalizeCall(saved)
        return if (call == DEFAULT_CALL) "" else call
    }

    // Generated texts

    /** A letter steps through A–Z, a digit through 0–9, by [n], wrapping. */
    fun shift(c: Char, n: Int): Char {
        fun step(base: Char, count: Int): Char {
            val i = c - base
            val j = ((i + n) % count + count) % count
            return base + j
        }
        return when {
            isLetter(c) -> step('A', 26)
            isDigit(c) -> step('0', 10)
            else -> c
        }
    }

    /**
     * The partial-calls rule: what is left of the partial once its "?"s are gone is
     * yours when it occurs, unbroken, in your call. A bare "?" is everyone's.
     */
    fun partialMatches(partial: String, call: String): Boolean {
        val p = normalizeCall(partial).trimEnd('?')
        if (p.isEmpty()) return true
        return normalizeCall(call).contains(p)
    }

    /**
     * A partial that is certainly not yours: your call up to its first digit,
     * that digit moved on two (then one more at a time while it is still in
     * your call). `N9HO` → `N1?`.
     */
    fun notMinePartial(call: String): String {
        val me = normalizeCall(call)
        val d = me.indexOfFirst(::isDigit)
        if (d < 0) return "?"
        var digit = shift(me[d], 2)
        var head = me.substring(0, d) + digit
        var tries = 0
        while (me.contains(head) && tries < 10) {
            digit = shift(digit, 1)
            head = me.substring(0, d) + digit
            tries++
        }
        return "$head?"
    }

    /**
     * A call one letter from yours: the first letter after your first digit
     * moved back six (`N9HO` → `N9BO`); with no letter there, your last letter.
     */
    fun nearMiss(call: String): String {
        val c = normalizeCall(call).toCharArray()
        if (c.isEmpty()) return ""
        val d = c.indexOfFirst(::isDigit)
        var i = -1
        if (d >= 0) {
            for (k in d + 1 until c.size) if (isLetter(c[k])) { i = k; break }
        }
        if (i < 0) i = c.indexOfLast(::isLetter)
        if (i < 0) return String(c)
        c[i] = shift(c[i], -6)
        return String(c)
    }

    /** A clip or choice as it is shown: every error token becomes [ERROR_DISPLAY]. */
    fun display(text: String): String = clipParts(text).joinToString(" ") { part ->
        when (part) {
            is ClipPart.Text -> part.text
            is ClipPart.Error -> ERROR_DISPLAY
        }
    }

    /** The hunter's reply, the same as First Four's: report, state, 73. The primary form. */
    fun reply(state: String): String = "5NN ${normalizeState(state)} 73"

    /**
     * WB0RLJ's order (RST, state, BK; then 73 and dit-dit after their TU),
     * accepted as a second form (maintainer, 2026-10-03). Its first turn.
     */
    fun replyBK(state: String): String = "5NN ${normalizeState(state)} BK"

    /** …and its second, after the activator's `TU 73 E E`. */
    const val CLOSE_BK = "73 E E"

    // Errors

    /**
     * One way an error can sound: how many dits (5–8), sent run together
     * (one keying, element gaps) or slapped as separate dits (character
     * gaps), at the learner's speed times [speed].
     */
    data class ErrorVariant(val count: Int, val runTogether: Boolean, val speed: Double) {
        /** Run together, as a dot pattern. */
        val pattern: String get() = ".".repeat(count)
        /** Slapped, as separate letters E. */
        val spacedText: String get() = "E".repeat(count)
    }

    /** The shapes an error takes in the app, so no two sound alike. */
    val errorVariants: List<ErrorVariant> = listOf(
        ErrorVariant(8, true, 1.0),
        ErrorVariant(5, true, 1.5),
        ErrorVariant(6, false, 0.8),
        ErrorVariant(7, true, 1.25),
        ErrorVariant(5, false, 1.0),
        ErrorVariant(8, false, 1.5),
    )

    /** Row [index] of [errorVariants], cycling. */
    fun errorVariant(index: Int): ErrorVariant {
        val n = errorVariants.size
        return errorVariants[((index % n) + n) % n]
    }

    /** A piece of a clip: Morse text, or an error (with its row, if pinned). */
    sealed class ClipPart {
        data class Text(val text: String) : ClipPart()
        data class Error(val row: Int?) : ClipPart()
    }

    /**
     * Split a clip on its error tokens, so a player can sound each error in
     * its own shape. Words are joined by single spaces; empty text is dropped.
     */
    fun clipParts(text: String): List<ClipPart> {
        val parts = mutableListOf<ClipPart>()
        val words = mutableListOf<String>()
        fun flush() {
            if (words.isNotEmpty()) parts += ClipPart.Text(words.joinToString(" "))
            words.clear()
        }
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val pinned = if (word.startsWith("<ERR:") && word.endsWith(">")) {
                word.substring(5, word.length - 1).toIntOrNull()
            } else null
            when {
                word == ERROR_TOKEN -> { flush(); parts += ClipPart.Error(null) }
                pinned != null -> { flush(); parts += ClipPart.Error(pinned) }
                else -> words += word
            }
        }
        flush()
        return parts
    }

    /** The exchange lesson's wrong example: a ragchew where a POTA exchange belongs. */
    fun ragchew(call: String, state: String, activator: String): String {
        val c = normalizeCall(call)
        val s = normalizeState(state)
        return "$activator DE $c TNX FER CALL UR 5NN 5NN NAME JOE QTH $s $s HW? $activator DE $c KN"
    }

    private fun shiftLast(s: String, n: Int): String =
        if (s.isEmpty()) s else s.dropLast(1) + shift(s.last(), n)

    // Scenarios

    /** Every lesson's scenarios, in lesson order, for this call and state. */
    fun scenarios(call: String, state: String): List<OpScenario> {
        val me = normalizeCall(call)
        val st = normalizeState(state)
        val act = activator(me)
        val other = otherHunter(me)
        val theirAck = "$me 5NN ${act.state} ${act.state} BK"
        val otherAck = "$other 5NN ${act.state} ${act.state} BK"
        val otherDone = "$other TU 73 E E"
        val mine = OpChoice.Send(me)
        val silent = OpChoice.Silent
        val near = nearMiss(me)
        val myReply = reply(st)
        val last = me.takeLast(1)
        val stateWrong = shiftLast(st, 1)
        val myReplyBK = replyBK(st)
        val callWrong = shiftLast(me, 1)
        val err = ERROR_TOKEN
        fun opt(vararg keys: String) = keys.map { OpChoice.Option(it) }
        fun s(
            id: String,
            lesson: OpLesson,
            clip: String,
            choices: List<OpChoice>,
            detail: String = "",
            accepted: List<Int> = listOf(0)
        ) = OpScenario(id, lesson, clip, detail, choices, accepted)
        val both = listOf(0, 1)

        return listOf(
            s("offset.pileup", OpLesson.OFFSET, "", opt("offsetSmall", "zeroBeat", "twoKUp")),
            s("offset.rit", OpLesson.OFFSET, "", opt("rit", "xit", "pitch")),
            s("offset.xit", OpLesson.OFFSET, "", opt("xit", "rit", "pitch")),
            s("offset.tune", OpLesson.OFFSET, "", opt("tuneAway", "tuneOnQuick", "tuneOnLow")),

            s("signals.as", OpLesson.SIGNALS, "<AS>", opt("wait", "goAhead", "goodbye")),
            s("signals.qrz", OpLesson.SIGNALS, "QRZ?", opt("whoIsCalling", "sayAgain", "sorry")),
            s("signals.ee", OpLesson.SIGNALS, "E E", opt("goodbye", "error", "whoIsCalling")),
            s("signals.bk", OpLesson.SIGNALS, "BK", opt("backToYou", "wait", "sorry")),
            s("signals.agn", OpLesson.SIGNALS, "AGN?", opt("sayAgain", "goodbye", "backToYou")),

            s("when.dits", OpLesson.WHEN, otherDone, listOf(mine, silent)),
            s("when.inProgress", OpLesson.WHEN, otherAck, listOf(silent, mine)),
            s("when.as", OpLesson.WHEN, "<AS>", listOf(silent, mine)),
            s("when.sriQrz", OpLesson.WHEN, "SRI SRI QRZ?", listOf(mine, silent)),

            s("once.cq", OpLesson.ONCE, "CQ POTA DE ${act.call} K",
                listOf(mine, OpChoice.Send("${act.call} DE $me K"), OpChoice.Send("$me $me $me"))),
            s("once.qrz", OpLesson.ONCE, "QRZ?",
                listOf(mine, OpChoice.Send("DE $me K"), OpChoice.Send("$me $me"))),
            s("once.dits", OpLesson.ONCE, otherDone,
                listOf(mine, OpChoice.Send("${act.call} $me"), silent)),

            s("partial.prefix", OpLesson.PARTIAL, me.take(2) + "?", listOf(mine, silent)),
            s("partial.notMine", OpLesson.PARTIAL, notMinePartial(me), listOf(silent, mine)),
            s("partial.suffix", OpLesson.PARTIAL, me.takeLast(2) + "?", listOf(mine, silent)),
            s("partial.fullCall", OpLesson.PARTIAL, me.dropLast(1) + "?",
                listOf(mine, OpChoice.Send(last), silent)),

            s("me.other", OpLesson.ME, otherAck, listOf(silent, mine)),
            s("me.mine", OpLesson.ME, theirAck,
                listOf(OpChoice.Send(myReply), OpChoice.Send(myReplyBK), mine, silent), accepted = both),
            s("me.close", OpLesson.ME, "$near 5NN ${act.state} ${act.state} BK", listOf(silent, mine), near),
            s("me.closeAsked", OpLesson.ME, "$near?", listOf(mine, silent), near),

            s("exchange.reply", OpLesson.EXCHANGE, theirAck,
                listOf(
                    OpChoice.Send(myReply), OpChoice.Send(myReplyBK),
                    OpChoice.Send(ragchew(me, st, act.call)), OpChoice.Send("$me 5NN $st")
                ),
                accepted = both),
            s("exchange.agn", OpLesson.EXCHANGE, "AGN?",
                listOf(OpChoice.Send(myReply), OpChoice.Send(myReplyBK), mine, silent), accepted = both),
            s("exchange.dits", OpLesson.EXCHANGE, "TU 73 E E",
                listOf(OpChoice.Send("E E"), OpChoice.Send(CLOSE_BK), mine, OpChoice.Send("TU 73 GL DE $me SK")),
                accepted = both),
            s("exchange.stop", OpLesson.EXCHANGE, "QRZ?", listOf(silent, mine)),

            s("mistake.call", OpLesson.MISTAKE, "",
                listOf(OpChoice.Send("$err $me"), OpChoice.Send("SRI $me"), silent), callWrong),
            s("mistake.last", OpLesson.MISTAKE, "",
                listOf(OpChoice.Send("$err $me"), OpChoice.Send("$err $last"), silent), shiftLast(me, -6)),
            s("mistake.state", OpLesson.MISTAKE, "",
                listOf(OpChoice.Send("$err $st 73"), OpChoice.Send("5NN $stateWrong $st 73"), silent),
                "5NN $stateWrong"),
            s("mistake.hear", OpLesson.MISTAKE, "$callWrong $err $theirAck",
                listOf(OpChoice.Send(myReply), OpChoice.Send(myReplyBK), mine, silent), callWrong, accepted = both),
        )
    }

    /** One lesson's scenarios. */
    fun scenarios(lesson: OpLesson, call: String, state: String): List<OpScenario> =
        scenarios(call, state).filter { it.lesson == lesson }

    /** "What should you do?"'s pool: every action scenario, in lesson order. */
    fun actionPool(call: String, state: String): List<OpScenario> =
        scenarios(call, state).filter { it.isAction }

    // Demos

    /** A lesson's right/wrong clips. The offsetting lesson's demos are the pileup and RIT demos, so its list is empty. */
    fun demos(lesson: OpLesson, call: String, state: String): List<OpDemo> {
        val me = normalizeCall(call)
        val st = normalizeState(state)
        val act = activator(me)
        val other = otherHunter(me)
        val theirAck = "$me 5NN ${act.state} ${act.state} BK"
        val a = OpDemo.Who.ACTIVATOR
        val y = OpDemo.Who.YOU
        fun wrong(vararg l: OpDemo.Line) = OpDemo(OpDemo.Kind.WRONG, l.toList())
        fun right(vararg l: OpDemo.Line) = OpDemo(OpDemo.Kind.RIGHT, l.toList())
        fun line(w: OpDemo.Who, t: String) = OpDemo.Line(w, t)
        return when (lesson) {
            OpLesson.SIGNALS -> listOf("?", "AGN?", "<AS>", "BK", "SRI", "QRZ?", "E E")
                .map { OpDemo(OpDemo.Kind.LISTEN, listOf(line(a, it))) }
            OpLesson.WHEN -> listOf(
                wrong(line(a, "$other 5NN ${act.state} ${act.state} BK"), line(y, me)),
                right(line(a, "$other TU 73 E E"), line(y, me)),
            )
            OpLesson.ONCE -> listOf(
                wrong(line(a, "CQ POTA DE ${act.call} K"), line(y, "${act.call} DE $me $me K")),
                right(line(a, "CQ POTA DE ${act.call} K"), line(y, me)),
            )
            OpLesson.PARTIAL -> listOf(
                wrong(line(a, notMinePartial(me)), line(y, me)),
                right(line(a, me.take(2) + "?"), line(y, me)),
            )
            OpLesson.ME -> {
                val near = nearMiss(me)
                listOf(
                    wrong(line(a, "$near 5NN ${act.state} ${act.state} BK"), line(y, me)),
                    right(line(a, "$near?"), line(y, me), line(a, theirAck)),
                )
            }
            OpLesson.EXCHANGE -> listOf(
                wrong(line(a, theirAck), line(y, ragchew(me, st, act.call))),
                right(line(a, theirAck), line(y, reply(st)), line(a, "TU 73 E E"), line(y, "E E")),
                right(line(a, theirAck), line(y, replyBK(st)), line(a, "TU 73 E E"), line(y, CLOSE_BK)),
            )
            OpLesson.MISTAKE -> {
                val wrongCall = shiftLast(me, 1)
                listOf(
                    wrong(line(y, wrongCall)),
                    right(line(y, "$wrongCall $ERROR_TOKEN $me")),
                    OpDemo(OpDemo.Kind.LISTEN, listOf(line(a, "$wrongCall <ERR:1> $me"))),
                    OpDemo(OpDemo.Kind.LISTEN, listOf(line(a, "$wrongCall <ERR:2> $me"))),
                    OpDemo(OpDemo.Kind.LISTEN, listOf(line(a, "$wrongCall <ERR:5> $me"))),
                )
            }
            OpLesson.OFFSET -> emptyList()
        }
    }

    // The pileup demo (#294)

    enum class PileupPass(val raw: String) {
        /** Everyone, you included, on the activator's frequency. */
        ZERO_BEAT("zeroBeat"),
        /** Only you off it. */
        YOU_OFFSET("youOffset"),
        /** Everyone on their own offset. */
        ALL_OFFSET("allOffset");

        companion object {
            fun fromRaw(raw: String): PileupPass? = entries.firstOrNull { it.raw == raw }
        }
    }

    private class DemoCaller(
        val call: String?,          // null = you
        val wpmDelta: Double,
        val gain: Double,
        val delay: Double,
        val spreadOffset: Double    // the ALL_OFFSET pass
    )

    /** Mix order. You are in the middle, as you would be in a real pileup. */
    private val demoCallers = listOf(
        DemoCaller("W8KDP", 2.0, 0.80, 0.15, -160.0),
        DemoCaller("KE0RJ", -2.0, 0.90, 0.30, -70.0),
        DemoCaller(null, 0.0, 1.00, 0.00, DEMO_YOUR_OFFSET_HZ),
        DemoCaller("AB7TF", 4.0, 0.70, 0.10, 30.0),
        DemoCaller("N4LQX", -1.0, 0.85, 0.22, 150.0),
    )

    /**
     * The callers of one pass, as the activator hears them: each at the
     * learner's tone plus their offset, sending their call once.
     */
    fun pileupVoices(pass: PileupPass, call: String, tone: Double, wpm: Double): List<OpPileupVoice> {
        val me = normalizeCall(call)
        return demoCallers.map { c ->
            val isYou = c.call == null
            val offset = when (pass) {
                PileupPass.ZERO_BEAT -> 0.0
                PileupPass.YOU_OFFSET -> if (isYou) DEMO_YOUR_OFFSET_HZ else 0.0
                PileupPass.ALL_OFFSET -> c.spreadOffset
            }
            val text = when {
                isYou -> me
                c.call == me -> REPLACEMENT_CALLER
                else -> c.call!!
            }
            OpPileupVoice(
                text = text,
                pitch = audible(tone + offset),
                wpm = max(MINIMUM_WPM, wpm + c.wpmDelta),
                gain = c.gain,
                delay = c.delay,
                isYou = isYou
            )
        }
    }

    // Offset maths
    //
    // All in hertz. An "offset" is a frequency relative to the activator's;
    // higher frequency sounds higher, as on the upper sideband (CW-reverse
    // would flip the signs and is not modelled).

    /** The pitch you hear a station at: your tone, plus how far they are from where you listen. */
    fun heardPitch(tone: Double, station: Double, vfo: Double, rit: Double): Double =
        tone + station - (vfo + rit)

    /** How far from the station you transmit (the VFO, moved by XIT). */
    fun transmitOffset(station: Double, vfo: Double, xit: Double): Double = vfo + xit - station

    fun isZeroBeat(transmitOffset: Double): Boolean = abs(transmitOffset) <= ZERO_BEAT_TOLERANCE_HZ

    fun audible(pitch: Double): Double = max(MINIMUM_PITCH_HZ, pitch)

    /** Where the drill puts the activator in round [round] (the table cycles). */
    fun drillStart(round: Int): Double {
        val n = drillStarts.size
        return drillStarts[((round % n) + n) % n]
    }
}

/**
 * A lesson's scenarios answered once each, in order. A wrong answer is a
 * mistake and still moves on; the run is clean when it finished without one.
 */
class OpScenarioRun(val scenarios: List<OpScenario>) {
    var index: Int = 0
        private set
    var mistakes: Int = 0
        private set

    val current: OpScenario? get() = scenarios.getOrNull(index)
    val isFinished: Boolean get() = index >= scenarios.size
    val isClean: Boolean get() = isFinished && mistakes == 0

    /** Answer with [choice], its index in the scenario's right-first order. True when right. */
    fun answer(choice: Int): Boolean {
        val s = current ?: return false
        val right = s.accepts(choice)
        if (!right) mistakes++
        index++
        return right
    }

    /** The same, by choice value — what a screen with shuffled buttons has. */
    fun answer(choice: OpChoice): Boolean {
        val s = current ?: return false
        val i = s.choices.indexOf(choice)
        if (i < 0) return false
        return answer(i)
    }
}

/**
 * Which lessons have passed, which have a clean scenario run, and whether
 * the offsetting drill has passed. Immutable, so a screen can hold it as state:
 * each record returns the next progress and whether it passed a lesson just
 * now. The drill's in-a-row streak is not saved (three in a row means three
 * in one sitting).
 */
data class OperatingProcedureProgress(
    val passed: Set<OpLesson> = emptySet(),
    val cleanRuns: Set<OpLesson> = emptySet(),
    val drillPassed: Boolean = false,
    val drillStreak: Int = 0
) {
    data class Recorded(val progress: OperatingProcedureProgress, val passedNow: Boolean)

    fun hasPassed(lesson: OpLesson): Boolean = lesson in passed
    fun hasCleanRun(lesson: OpLesson): Boolean = lesson in cleanRuns
    val passedCount: Int get() = passed.size
    val isComplete: Boolean get() = OpLesson.entries.all { it in passed }
    val nextLesson: OpLesson? get() = OpLesson.entries.firstOrNull { it !in passed }

    /** Count one finished scenario run. */
    fun recordRun(lesson: OpLesson, clean: Boolean): Recorded {
        if (!clean) return Recorded(this, false)
        return copy(cleanRuns = cleanRuns + lesson).settle(lesson)
    }

    /** Count one drill answer. */
    fun recordDrill(correct: Boolean): Recorded {
        if (!correct) return Recorded(copy(drillStreak = 0), false)
        val streak = drillStreak + 1
        if (streak < OperatingProcedure.ZERO_BEAT_STREAK_TO_PASS) return Recorded(copy(drillStreak = streak), false)
        return copy(drillStreak = 0, drillPassed = true).settle(OpLesson.OFFSET)
    }

    private fun settle(lesson: OpLesson): Recorded {
        if (lesson in passed || lesson !in cleanRuns || (lesson == OpLesson.OFFSET && !drillPassed)) {
            return Recorded(this, false)
        }
        return Recorded(copy(passed = passed + lesson), true)
    }

    /** The saved form's lists: lesson names, sorted. */
    val passedNames: List<String> get() = passed.map { it.raw }.sorted()
    val cleanRunNames: List<String> get() = cleanRuns.map { it.raw }.sorted()

    companion object {
        /** A saved record; unknown lesson names (from a newer build) are skipped. */
        fun restore(passed: Collection<String>, cleanRuns: Collection<String>, drillPassed: Boolean) =
            OperatingProcedureProgress(
                passed = passed.mapNotNull(OpLesson::fromRaw).toSet(),
                cleanRuns = cleanRuns.mapNotNull(OpLesson::fromRaw).toSet(),
                drillPassed = drillPassed
            )
    }
}
