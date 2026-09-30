package app.anothermorsetrainer.morsekit

// First Four (#265): the minimum CW a brand-new operator needs to hunt one
// POTA activator — their call, their state, "?" and "73", copied and sent,
// then three short scripted scenes (a busted call, an unanswered call, and a
// whole contact). docs/first-four-design.md is the spec; every rule below is
// pinned by fixtures/first-four.json, which the Swift MorseKitCheck reads
// too. Twin of ios/Sources/MorseKit/FirstFour.swift.

/** The seven stages, in the order the stage list recommends. [raw] is the fixture's name. */
enum class FirstFourStage(val raw: String) {
    CALL("call"),
    STATE("state"),
    QUESTION("question"),
    SEVENTY_THREE("seventyThree"),
    BUSTED_CALL("bustedCall"),
    NO_REPLY("noReply"),
    WALKTHROUGH("walkthrough");

    /** The four element stages each copy, then send, one piece of text. */
    val isElement: Boolean
        get() = this == CALL || this == STATE || this == QUESTION || this == SEVENTY_THREE

    companion object {
        fun fromRaw(raw: String): FirstFourStage? = entries.firstOrNull { it.raw == raw }
    }
}

/** An element stage's two halves: hear it and type it, then key it. */
enum class FirstFourPhase(val raw: String) {
    COPY("copy"),
    SEND("send");

    companion object {
        fun fromRaw(raw: String): FirstFourPhase? = entries.firstOrNull { it.raw == raw }
    }
}

/** An activator for a scene: a callsign and the state they are in. */
data class FirstFourStation(val call: String, val state: String)

/** One step of a scene. */
data class FirstFourBeat(
    val kind: Kind,
    /** What plays (hear, copy) or what to key (send); empty otherwise. */
    val text: String,
    val cue: Cue,
    /** What a copy beat asks for; null otherwise. */
    val answer: String? = null
) {
    enum class Kind(val raw: String) {
        /** The activator transmits; Continue moves on. */
        HEAR("hear"),
        /** Nothing comes back. */
        SILENCE("silence"),
        /** The activator transmits and the learner types one piece of it. */
        COPY("copy"),
        /** The learner keys [text]. */
        SEND("send"),
        /** The right thing to do is nothing: tap Wait. */
        WAIT("wait");

        companion object {
            fun fromRaw(raw: String): Kind? = entries.firstOrNull { it.raw == raw }
        }
    }

    /**
     * Which explanation the screen shows beside the beat. The words are each
     * platform's own; the cue is what the fixture pins.
     */
    enum class Cue(val raw: String) {
        CQ("cq"), CALL_THEM("callThem"), PARTIAL("partial"), RESEND("resend"), ACK("ack"),
        NO_REPLY("noReply"), CALL_AGAIN("callAgain"), OTHER_STATION("otherStation"),
        STAY_QUIET("stayQuiet"), QRZ("qrz"), THEIR_EXCHANGE("theirExchange"),
        YOUR_EXCHANGE("yourExchange"), SIGN_OFF("signOff");

        companion object {
            fun fromRaw(raw: String): Cue? = entries.firstOrNull { it.raw == raw }
        }
    }
}

/** The rules: constants, validation, matching, tables, scenes. */
object FirstFour {

    // Pass criteria (defaults the maintainer can change; see the design note).

    /** Correct copies in a row that finish an element stage's copy phase. */
    const val COPY_STREAK_TO_PASS = 3
    /** Correct sends in a row that finish an element stage's send phase. */
    const val SEND_STREAK_TO_PASS = 3
    /** Clean busted-call rounds that pass that stage. */
    const val BUSTED_ROUNDS_TO_PASS = 3
    /** Clean no-reply scenes that pass that stage: scene A, then scene B. */
    const val NO_REPLY_SCENES_TO_PASS = 2
    /** Clean walkthroughs that pass the last stage. */
    const val WALKTHROUGH_RUNS_TO_PASS = 2

    /** The callsign Settings ships with — a placeholder, not the learner's. */
    const val DEFAULT_CALL = "W1AW"

    /** Clean runs a scene stage needs; null for an element stage. */
    fun cleanRunsToPass(stage: FirstFourStage): Int? = when (stage) {
        FirstFourStage.BUSTED_CALL -> BUSTED_ROUNDS_TO_PASS
        FirstFourStage.NO_REPLY -> NO_REPLY_SCENES_TO_PASS
        FirstFourStage.WALKTHROUGH -> WALKTHROUGH_RUNS_TO_PASS
        else -> null
    }

    // Callsign and state.

    /** Upper-cased with every whitespace character removed; nothing else. */
    fun normalizeCall(raw: String): String = raw.uppercase().filterNot { it.isWhitespace() }

    fun normalizeState(raw: String): String = raw.uppercase().filterNot { it.isWhitespace() }

    private fun isAsciiLetter(c: Char) = c in 'A'..'Z'
    private fun isAsciiDigit(c: Char) = c in '0'..'9'

    /** 3–10 characters of A–Z, 0–9 and "/", with a letter and a digit. */
    fun isValidCall(raw: String): Boolean {
        val call = normalizeCall(raw)
        if (call.length !in 3..10) return false
        if (!call.all { isAsciiLetter(it) || isAsciiDigit(it) || it == '/' }) return false
        return call.any(::isAsciiLetter) && call.any(::isAsciiDigit)
    }

    /** 2–3 letters. Not checked against a list, so a province works too. */
    fun isValidState(raw: String): Boolean {
        val state = normalizeState(raw)
        return state.length in 2..3 && state.all(::isAsciiLetter)
    }

    /**
     * What the call field starts with: the saved call, unless it is the
     * settings placeholder (or empty), which the learner has not entered.
     */
    fun prefillCall(saved: String): String {
        val call = normalizeCall(saved)
        return if (call == DEFAULT_CALL) "" else call
    }

    // Elements.

    /** What an element stage drills: the call, the state, "?" or "73". */
    fun element(stage: FirstFourStage, call: String, state: String): String? = when (stage) {
        FirstFourStage.CALL -> normalizeCall(call)
        FirstFourStage.STATE -> normalizeState(state)
        FirstFourStage.QUESTION -> "?"
        FirstFourStage.SEVENTY_THREE -> "73"
        else -> null
    }

    // Matching.

    /**
     * Upper-cased with every whitespace character removed: word spacing is
     * what a beginner's fist and a typed answer are least sure of.
     */
    fun compact(s: String): String = s.uppercase().filterNot { it.isWhitespace() }

    /** A typed copy is right when it is the expected text, spacing aside. */
    fun copyMatches(typed: String, expected: String): Boolean {
        val t = compact(typed)
        return t.isNotEmpty() && t == compact(expected)
    }

    /**
     * A keyed send is right when it is the expected text, spacing aside — or
     * the expected text twice, as operators often send their call.
     */
    fun sendMatches(sent: String, expected: String): Boolean {
        val s = compact(sent)
        val e = compact(expected)
        return s.isNotEmpty() && (s == e || s == e + e)
    }

    // Busted-call partials.

    /**
     * A bare "?" first, then every proper prefix of two or more characters
     * with "?" after it, skipping a prefix that ends in "/".
     */
    fun partials(call: String): List<String> {
        val c = normalizeCall(call)
        val out = mutableListOf("?")
        for (k in 2 until c.length) {
            if (c[k - 1] != '/') out += c.substring(0, k) + "?"
        }
        return out
    }

    /** The partial busted round [round] uses (they rotate). */
    fun partial(call: String, round: Int): String {
        val list = partials(call)
        return list[Math.floorMod(round, list.size)]
    }

    // Stations.

    /** Fictional activators, pinned in the fixture so both ports carry the same. */
    val activators: List<FirstFourStation> = listOf(
        FirstFourStation("K4RTZ", "NC"),
        FirstFourStation("W0PQA", "CO"),
        FirstFourStation("N7XKT", "AZ"),
        FirstFourStation("KB3MZL", "PA"),
        FirstFourStation("AC9WD", "IL"),
        FirstFourStation("W5JBQ", "TX"),
        FirstFourStation("K1VLM", "ME"),
        FirstFourStation("N6GUZ", "CA")
    )

    /** The other hunter an activator answers instead of you (no-reply scene B). */
    val otherHunters: List<String> = listOf("W8KDP", "KE0RJ")

    /** Row [index] (mod the count) of the activators that are not you. */
    fun activator(index: Int, excluding: String): FirstFourStation {
        val me = normalizeCall(excluding)
        val pool = activators.filter { it.call != me }
        return pool[Math.floorMod(index, pool.size)]
    }

    /** Row [index] (mod the count) of the other hunters that are not you. */
    fun otherHunter(index: Int, excluding: String): String {
        val me = normalizeCall(excluding)
        val pool = otherHunters.filter { it != me }
        return pool[Math.floorMod(index, pool.size)]
    }

    // Scenes.

    private fun ack(me: String, activator: FirstFourStation) =
        "$me 5NN ${activator.state} ${activator.state} BK"

    /** The busted-call round: you call, they catch part of it, you send it all again and wait. */
    fun bustedCallScene(call: String, partial: String, activator: FirstFourStation): List<FirstFourBeat> {
        val me = normalizeCall(call)
        return listOf(
            FirstFourBeat(FirstFourBeat.Kind.HEAR, "CQ POTA DE ${activator.call} K", FirstFourBeat.Cue.CQ),
            FirstFourBeat(FirstFourBeat.Kind.SEND, me, FirstFourBeat.Cue.CALL_THEM),
            FirstFourBeat(FirstFourBeat.Kind.HEAR, partial, FirstFourBeat.Cue.PARTIAL),
            FirstFourBeat(FirstFourBeat.Kind.SEND, me, FirstFourBeat.Cue.RESEND),
            FirstFourBeat(FirstFourBeat.Kind.HEAR, ack(me, activator), FirstFourBeat.Cue.ACK)
        )
    }

    /** No reply, scene A: nobody answers, so you call again. */
    fun noReplySceneA(call: String, activator: FirstFourStation): List<FirstFourBeat> {
        val me = normalizeCall(call)
        return listOf(
            FirstFourBeat(FirstFourBeat.Kind.HEAR, "CQ POTA DE ${activator.call} K", FirstFourBeat.Cue.CQ),
            FirstFourBeat(FirstFourBeat.Kind.SEND, me, FirstFourBeat.Cue.CALL_THEM),
            FirstFourBeat(FirstFourBeat.Kind.SILENCE, "", FirstFourBeat.Cue.NO_REPLY),
            FirstFourBeat(FirstFourBeat.Kind.SEND, me, FirstFourBeat.Cue.CALL_AGAIN),
            FirstFourBeat(FirstFourBeat.Kind.HEAR, ack(me, activator), FirstFourBeat.Cue.ACK)
        )
    }

    /** No reply, scene B: they answer someone else, so you wait for QRZ. */
    fun noReplySceneB(call: String, activator: FirstFourStation, otherHunter: String): List<FirstFourBeat> {
        val me = normalizeCall(call)
        return listOf(
            FirstFourBeat(FirstFourBeat.Kind.HEAR, "CQ POTA DE ${activator.call} K", FirstFourBeat.Cue.CQ),
            FirstFourBeat(FirstFourBeat.Kind.SEND, me, FirstFourBeat.Cue.CALL_THEM),
            FirstFourBeat(
                FirstFourBeat.Kind.HEAR,
                "$otherHunter 5NN ${activator.state} ${activator.state} BK",
                FirstFourBeat.Cue.OTHER_STATION
            ),
            FirstFourBeat(FirstFourBeat.Kind.WAIT, "", FirstFourBeat.Cue.STAY_QUIET),
            FirstFourBeat(FirstFourBeat.Kind.HEAR, "TU 73 QRZ", FirstFourBeat.Cue.QRZ),
            FirstFourBeat(FirstFourBeat.Kind.SEND, me, FirstFourBeat.Cue.CALL_AGAIN),
            FirstFourBeat(FirstFourBeat.Kind.HEAR, ack(me, activator), FirstFourBeat.Cue.ACK)
        )
    }

    /** Which no-reply scene plays next: A until it has a clean run, then B. */
    fun noReplyUsesSceneB(cleanRuns: Int): Boolean = cleanRuns > 0

    /** The whole minimal hunter-side contact. */
    fun walkthroughScene(call: String, state: String, activator: FirstFourStation): List<FirstFourBeat> {
        val me = normalizeCall(call)
        val st = normalizeState(state)
        return listOf(
            FirstFourBeat(FirstFourBeat.Kind.HEAR, "CQ POTA DE ${activator.call} ${activator.call} K", FirstFourBeat.Cue.CQ),
            FirstFourBeat(FirstFourBeat.Kind.SEND, me, FirstFourBeat.Cue.CALL_THEM),
            FirstFourBeat(FirstFourBeat.Kind.COPY, ack(me, activator), FirstFourBeat.Cue.THEIR_EXCHANGE, answer = activator.state),
            FirstFourBeat(FirstFourBeat.Kind.SEND, "5NN $st 73", FirstFourBeat.Cue.YOUR_EXCHANGE),
            FirstFourBeat(FirstFourBeat.Kind.HEAR, "TU 73 E E", FirstFourBeat.Cue.SIGN_OFF)
        )
    }
}

/** A learner's response to the current beat. */
sealed interface FirstFourResponse {
    data object Continued : FirstFourResponse
    data class Sent(val text: String) : FirstFourResponse
    data class Copied(val text: String) : FirstFourResponse
    data object Waited : FirstFourResponse
}

/** How a response went. [raw] is the fixture's name. */
enum class FirstFourVerdict(val raw: String) {
    ADVANCE("advance"),
    FINISHED("finished"),
    /** Keyed something that is not what the beat asks for. */
    SENT_WRONG("sentWrong"),
    /** Typed something that is not what was sent. */
    COPY_WRONG("copyWrong"),
    /** It was the learner's turn to send. */
    YOUR_TURN("yourTurn"),
    /** The right thing was to wait, not to key. */
    STAY_QUIET("stayQuiet"),
    /** The activator is transmitting; listen first. */
    NOT_YOUR_TURN("notYourTurn")
}

/** Walks a scene beat by beat, grading each response. */
class FirstFourScene(val beats: List<FirstFourBeat>) {
    var index: Int = 0
        private set
    var mistakes: Int = 0
        private set

    val isFinished: Boolean get() = index >= beats.size
    /** Finished with no mistakes. */
    val isClean: Boolean get() = isFinished && mistakes == 0
    val current: FirstFourBeat? get() = beats.getOrNull(index)

    fun respond(response: FirstFourResponse): FirstFourVerdict {
        val beat = current ?: return FirstFourVerdict.FINISHED
        val verdict = when (beat.kind) {
            FirstFourBeat.Kind.HEAR, FirstFourBeat.Kind.SILENCE ->
                if (response == FirstFourResponse.Continued) FirstFourVerdict.ADVANCE else FirstFourVerdict.NOT_YOUR_TURN
            FirstFourBeat.Kind.SEND ->
                if (response is FirstFourResponse.Sent) {
                    if (FirstFour.sendMatches(response.text, beat.text)) FirstFourVerdict.ADVANCE else FirstFourVerdict.SENT_WRONG
                } else {
                    FirstFourVerdict.YOUR_TURN
                }
            FirstFourBeat.Kind.COPY ->
                if (response is FirstFourResponse.Copied && FirstFour.copyMatches(response.text, beat.answer ?: "")) {
                    FirstFourVerdict.ADVANCE
                } else {
                    FirstFourVerdict.COPY_WRONG
                }
            FirstFourBeat.Kind.WAIT ->
                if (response == FirstFourResponse.Waited) FirstFourVerdict.ADVANCE else FirstFourVerdict.STAY_QUIET
        }
        if (verdict == FirstFourVerdict.ADVANCE) index++ else mistakes++
        return verdict
    }
}

/**
 * Which stages have passed, and the counters that decide the rest. Persisted
 * by FirstFourStore; the in-phase streak is not (three in a row means
 * three in one sitting).
 */
class FirstFourProgress {
    private val passedSet = linkedSetOf<FirstFourStage>()
    private val copyPassedSet = linkedSetOf<FirstFourStage>()
    private val cleanRunCounts = mutableMapOf<FirstFourStage, Int>()

    val passed: Set<FirstFourStage> get() = passedSet
    /** Element stages whose copy phase is done. */
    val copyPassed: Set<FirstFourStage> get() = copyPassedSet

    /** The current phase's run of correct answers. */
    var streak: Int = 0
        private set
    private var streakKey: String? = null

    fun hasPassed(stage: FirstFourStage): Boolean = stage in passedSet
    val passedCount: Int get() = passedSet.size
    val isComplete: Boolean get() = FirstFourStage.entries.all { it in passedSet }
    val nextStage: FirstFourStage? get() = FirstFourStage.entries.firstOrNull { it !in passedSet }
    fun cleanRuns(stage: FirstFourStage): Int = cleanRunCounts[stage] ?: 0

    /**
     * Where an element stage opens: the send phase once copy is done and the
     * stage is still open, else copy (a passed stage is reviewed from the top).
     */
    fun openingPhase(stage: FirstFourStage): FirstFourPhase =
        if (stage in copyPassedSet && stage !in passedSet) FirstFourPhase.SEND else FirstFourPhase.COPY

    /** Count one element answer. Returns true when it completed the phase. */
    fun recordElement(stage: FirstFourStage, phase: FirstFourPhase, correct: Boolean): Boolean {
        val key = "${stage.raw}.${phase.raw}"
        if (streakKey != key) { streak = 0; streakKey = key }
        if (!correct) { streak = 0; return false }
        streak++
        val needed = if (phase == FirstFourPhase.COPY) FirstFour.COPY_STREAK_TO_PASS else FirstFour.SEND_STREAK_TO_PASS
        if (streak < needed) return false
        streak = 0
        copyPassedSet += stage
        if (phase == FirstFourPhase.SEND) passedSet += stage
        return true
    }

    /** Count one finished scene run. Returns true when this run passed the stage. */
    fun recordScene(stage: FirstFourStage, clean: Boolean): Boolean {
        val needed = FirstFour.cleanRunsToPass(stage) ?: return false
        if (!clean) return false
        val runs = cleanRuns(stage) + 1
        cleanRunCounts[stage] = runs
        if (runs < needed || stage in passedSet) return false
        passedSet += stage
        return true
    }

    /** Clean runs per scene stage, for the store to save. */
    val cleanRunsByStage: Map<FirstFourStage, Int> get() = cleanRunCounts.toMap()

    companion object {
        /** A saved record, as the store kept it; the streak starts again at 0. */
        fun restore(
            passed: Collection<FirstFourStage>,
            copyPassed: Collection<FirstFourStage>,
            cleanRuns: Map<FirstFourStage, Int>
        ): FirstFourProgress = FirstFourProgress().apply {
            passedSet += passed
            copyPassedSet += copyPassed
            cleanRunCounts += cleanRuns
        }
    }
}
