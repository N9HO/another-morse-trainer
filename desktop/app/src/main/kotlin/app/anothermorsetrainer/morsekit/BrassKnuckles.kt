package app.anothermorsetrainer.morsekit

/**
 * Brass Knuckles Knock Out: a boxing game that teaches Morse. Created by Derick
 * (KO6PBZ) for the HamCube's PocketCW firmware (Kristen, K5MME), sponsored by
 * The Brass Knuckle Gang. GPL-3.0-or-later, ported here with the author's
 * permission (see docs/brass-knuckles-design.md).
 *
 * The opponent telegraphs each punch by sending a character or two (the tell).
 * Key it back before the punch lands and you dodge it; the opponent is then
 * open, and every character you send is a punch: all dits jab, all dahs hook,
 * a mix throws a combo. Clean timing hits harder. Clean counters earn stars,
 * and a star buys an uppercut: send a whole word or callsign without an error.
 *
 * Pure game rules, like [AsteroidsGame]: no clock, no audio, no UI. Time enters
 * as a millisecond tick count passed to every call (it wraps, as the firmware's
 * does); the screen calls [BoxingFight.update] at [BoxingFight.deadline] and
 * reads the state to draw it. Translated from the game's Rust rules
 * (`logic/src/boxing.rs`, `rng.rs`, `ticks.rs`) so that a seeded fight plays
 * out identically; the Swift port is its twin, and both are pinned by
 * fixtures/brass-knuckles.json.
 *
 * Differences from the original, on purpose:
 *  - the keyer, decoder, timing quality and letter statistics are AMT's, so the
 *    screen hands in the keyed character and a [BoxingQuality], and tells
 *    "lean on weak characters" through [BoxingFight.learn];
 *  - no flash-record framing: AMT keeps progress in its own stores, so
 *    [BoxingCareer] has no encode/decode;
 *  - the drilled set is the 41 Koch characters; the original's three trailing
 *    prosigns are never sent by any fighter (the last fighter's set ends at
 *    character 41).
 */

/** How well a keyed character was timed (the original's `Quality`). */
enum class BoxingQuality { GOOD, OFF, BAD }

/** Which hand. */
enum class BoxingSide {
    LEFT, RIGHT;
    fun other(): BoxingSide = if (this == LEFT) RIGHT else LEFT
}

/** What a character you send throws. */
sealed class BoxingPunch {
    object Jab : BoxingPunch()
    object Hook : BoxingPunch()
    /** One punch per run of the same element, at most four. */
    data class Combo(val punches: Int) : BoxingPunch()
}

/** What the opponent is doing, for the screen. */
sealed class BoxingFoe {
    object Idle : BoxingFoe()
    data class Windup(val side: BoxingSide) : BoxingFoe()
    data class Punch(val side: BoxingSide) : BoxingFoe()
    data class Whiff(val side: BoxingSide) : BoxingFoe()
    object Open : BoxingFoe()
    data class Hurt(val side: BoxingSide) : BoxingFoe()
    object Block : BoxingFoe()
    object Stunned : BoxingFoe()
    object Down : BoxingFoe()
    object Out : BoxingFoe()
    object Rise : BoxingFoe()
    object Victory : BoxingFoe()
}

/** What you are doing, for the screen. */
sealed class BoxingMe {
    object Idle : BoxingMe()
    data class Dodge(val side: BoxingSide) : BoxingMe()
    data class Jab(val side: BoxingSide) : BoxingMe()
    data class Hook(val side: BoxingSide) : BoxingMe()
    data class Combo(val punches: Int) : BoxingMe()
    object Uppercut : BoxingMe()
    data class Blocked(val side: BoxingSide) : BoxingMe()
    /** A pattern no character uses: a swing at air. */
    object Miss : BoxingMe()
    object Hurt : BoxingMe()
    object Down : BoxingMe()
    object Rise : BoxingMe()
    object Victory : BoxingMe()
}

enum class BoxingOutcome { KO, TKO, DECISION }

sealed class BoxingPhase {
    /** The fighter's card, before the first bell. */
    object Intro : BoxingPhase()
    object RoundStart : BoxingPhase()
    object Fight : BoxingPhase()
    /** The opponent is down; the count is [BoxingFight.count]. */
    object FoeDown : BoxingPhase()
    /** You are down: key [BoxingFight.getUp] before the count reaches ten. */
    object MeDown : BoxingPhase()
    object RoundEnd : BoxingPhase()
    /** The fight is over: you won or lost, and how. */
    data class Over(val won: Boolean, val how: BoxingOutcome) : BoxingPhase()
}

/** A short message for the screen. */
sealed class BoxingCallout {
    object Dodge : BoxingCallout()
    /** Your keying did not match the tell. */
    data class Wrong(val sent: Char) : BoxingCallout()
    object TooSlow : BoxingCallout()
    object Clean : BoxingCallout()
    object Star : BoxingCallout()
    object Uppercut : BoxingCallout()
    object Blocked : BoxingCallout()
    object Missed : BoxingCallout()
    object Down : BoxingCallout()
    object Up : BoxingCallout()
}

/** One opponent on the ladder. */
data class BoxingFighter(
    val name: String,
    /** Shown on the intro card. */
    val from: String,
    val blurb: String,
    /** Koch characters this fighter's tells use, counted from the start of [BoxingRules.KOCH]. */
    val koch: Int,
    /** Added to the receive speed. */
    val speedBump: Int,
    /** Farnsworth speed as a percent of the character speed. */
    val spacingPct: Int,
    /** The tell shows on screen while it plays. */
    val hint: Boolean,
    /** Band noise and fading levels, on top of your own settings. */
    val noise: Int,
    val fading: Int,
    /** Longest tell, in characters. */
    val maxTell: Int,
    val hp: Int,
    /** Damage one landed punch does to you. */
    val power: Int,
    /** Time after the tell ends to start keying it back, before your keying time is added. */
    val reactMs: Int,
    /** Pause between attacks: shortest, then the random extra. */
    val restLeastMs: Int,
    val restExtraMs: Int,
    /** How long the opponent stays open after you dodge. */
    val openMs: Int,
    /** Offset of the opponent's tone from yours. */
    val pitchOffsetHz: Int,
    /** How unevenly the tells are keyed, in percent. */
    val fist: Int
) {
    /** The Koch characters this fighter sends. */
    val set: String get() = BoxingRules.KOCH.substring(0, koch)

    /** The characters this fighter adds to the one before. */
    fun newChars(index: Int): String {
        val from = if (index == 0) 0 else BoxingRules.LADDER[index - 1].koch
        return BoxingRules.KOCH.substring(from, koch)
    }

    /** Character and Farnsworth speed of the tells, from your receive speed: (wpm, effective). */
    fun speeds(baseWpm: Int, farnsworth: Int): Pair<Int, Int> {
        val wpm = minOf(baseWpm + speedBump, 60)
        var effective = maxOf(wpm * spacingPct / 100, 5)
        if (farnsworth > 0) effective = minOf(effective, farnsworth)
        return Pair(wpm, minOf(effective, wpm))
    }
}

object BoxingRules {
    const val FIGHTERS = 8
    const val ROUNDS = 3
    const val ROUND_MS = 90_000
    const val MAX_STARS = 3
    /** Clean counters that earn a star. */
    const val CLEAN_PER_STAR = 3
    const val PLAYER_HP = 100
    /** Knockdowns in one fight that end it. */
    const val KNOCKDOWNS_TO_KO = 3
    const val MAX_TELL = 3
    const val MAX_WORD = 10
    /** One count of the referee over a fighter on the canvas. */
    const val COUNT_MS = 1000
    const val ROUND_BANNER_MS = 1800
    const val ROUND_END_MS = 2600
    /** How long the punch takes to land once thrown, and how long the opponent stays off balance after a dodge. */
    const val PUNCH_MS = 260
    const val WHIFF_MS = 420
    /** The pause after you take a punch, before the next attack can start. */
    const val STAGGER_MS = 700
    /** Time to send the uppercut word: this per character, after this much to start. */
    const val STAR_START_MS = 2500
    const val STAR_CHAR_MS = 1200
    const val UPPERCUT_DAMAGE = 34
    const val GET_UP_COUNT = 10

    /** The Koch order LCWO uses: every character a fighter can send. */
    const val KOCH = "KMURESNAPTLWI.JZ=FOY,VG5/Q92H38B?47C1D60X"

    val LADDER: List<BoxingFighter> = listOf(
        BoxingFighter("LID LARRY", "THE BOTTOM OF THE BAND", "CALLS CQ ON TOP OF EVERYONE",
            5, 0, 40, true, 0, 0, 1, 60, 12, 2600, 1800, 1400, 4800, -60, 14),
        BoxingFighter("QRM QUEEN", "RIGHT ON YOUR FREQUENCY", "SHE SPLATTERS AND SHE KNOWS IT",
            10, 0, 50, true, 1, 0, 1, 75, 13, 2300, 1600, 1300, 4500, 90, 4),
        BoxingFighter("PILEUP PETE", "THE FRONT OF EVERY PILEUP", "NEVER WAITS HIS TURN",
            15, 1, 60, true, 2, 0, 1, 85, 14, 2000, 1400, 1200, 4200, -40, 6),
        BoxingFighter("BAREFOOT BOB", "100 WATTS AND A WIRE", "NO AMP, NO HINTS, NO MERCY",
            20, 2, 70, false, 3, 0, 1, 95, 15, 1800, 1300, 1100, 3900, 50, 0),
        BoxingFighter("CONTEST CARL", "A 48 HOUR CONTEST", "599 TU. NEXT!",
            25, 3, 80, false, 3, 0, 2, 105, 17, 1600, 1100, 1000, 3600, -80, 0),
        BoxingFighter("SKIP SADIE", "THE F2 LAYER", "HERE ONE SECOND, GONE THE NEXT",
            30, 4, 90, false, 4, 5, 2, 115, 18, 1450, 1000, 900, 3300, 110, 3),
        BoxingFighter("SIDESWIPER SID", "A KEY CUT FROM A HACKSAW BLADE", "WAGS BOTH WAYS, FAST",
            35, 5, 100, false, 5, 6, 3, 125, 20, 1300, 900, 800, 3000, -100, 10),
        BoxingFighter("BARON VON KILOWATT", "A CASTLE FULL OF AMPLIFIERS", "THE LEGAL LIMIT IS A SUGGESTION",
            41, 6, 100, false, 6, 7, 3, 140, 23, 1150, 800, 700, 2800, 0, 0)
    )

    /** Words for uppercuts. Each fighter uses the ones made only of its characters. */
    val WORDS: List<String> = listOf(
        "MURK", "EMU", "RUE", "SURE", "USER", "MUSE", "RUSE", "SUM", "SKA", "TREK", "MARK", "PARK", "SNAP", "STAMP", "MASTER",
        "PAUSE", "SPARK", "TRAMP", "ANTS", "RANK", "TUNE", "NAME", "UR", "RST", "TNX", "TU", "ES", "PSE", "QSL", "QRP", "QRM",
        "QRZ", "QTH", "SKED", "POTA", "SOTA", "CQ", "DE", "73", "5NN", "599", "WX", "RIG", "ANT", "KEY", "DIPOLE", "WATTS",
        "BAND", "WIRE", "LOOP", "YAGI", "BEAM", "FIST", "SPLIT", "PILEUP", "LID", "DX", "CW", "WPM", "FB", "OM", "YL", "GM",
        "HAM", "RADIO", "MORSE", "BOXING", "PUNCH", "JAB", "HOOK", "KNOCKOUT", "CHAMP"
    )

    /** Dots and dashes for [c], or null for a character with no code. */
    fun code(c: Char): String? {
        val u = c.uppercaseChar()
        return MorseCode.table[u] ?: MorseCode.optionalPunctuation[u]
    }

    /** The punch character [c] throws, from its code. */
    fun punchOf(c: Char): BoxingPunch? {
        val code = code(c) ?: return null
        if (code.all { it == '.' }) return BoxingPunch.Jab
        if (code.all { it == '-' }) return BoxingPunch.Hook
        var runs = 1
        for (i in 1 until code.length) if (code[i] != code[i - 1]) runs++
        return BoxingPunch.Combo(minOf(runs, 4))
    }

    private fun qualityPercent(q: BoxingQuality): Int = when (q) {
        BoxingQuality.GOOD -> 100
        BoxingQuality.OFF -> 65
        BoxingQuality.BAD -> 35
    }

    /** Damage of [c] thrown with [quality] timing. */
    fun damage(c: Char, quality: BoxingQuality): Int {
        val code = code(c) ?: return 0
        val len = code.length
        val base = when (punchOf(c)) {
            BoxingPunch.Jab -> 3 + len
            BoxingPunch.Hook -> 4 + len
            is BoxingPunch.Combo -> 3 + 2 * len
            null -> 0
        }
        return base * qualityPercent(quality) / 100
    }

    /** The ideal length of [c] in dots, with no gap after it. */
    fun units(c: Char): Int {
        val code = code(c) ?: return 0
        val dahs = code.count { it == '-' }
        return (code.length - dahs) + 3 * dahs + code.length - 1
    }

    /** Index of [c] in [KOCH], or -1. */
    fun indexOf(c: Char): Int = KOCH.indexOf(c)

    // Millisecond ticks wrap every 49 days; two times compare correctly while they are under 24 days apart.
    private const val HALF = 2147483647L // u32::MAX / 2

    fun reached(now: Int, at: Int): Boolean = (now - at).toUInt().toLong() < HALF
    fun msUntil(now: Int, at: Int): Int = if (reached(now, at)) 0 else at - now
    fun earlier(a: Int, b: Int): Int = if (reached(b, a)) a else b
}

/** The game's xorshift32 generator, so a seed plays out the same on every port. */
class BoxingRng(seed: Int) {
    private var s: Int = seed or 1

    /** A number in 0 until [n] (n treated as at least 1). */
    fun below(n: Int): Int {
        s = s xor (s shl 13)
        s = s xor (s ushr 17)
        s = s xor (s shl 5)
        return Integer.remainderUnsigned(s, maxOf(n, 1))
    }

    /** A random US call in call area [digit], as the game's `us_call` builds it. */
    fun usCall(digit: Int): String {
        fun pick(letters: String): Char = letters[below(letters.length)]
        val (prefixLen, suffixLen) = US_SHAPES[pick("012345678") - '0']
        val sb = StringBuilder()
        val first = when {
            prefixLen == 1 -> pick("KNW")
            suffixLen == 3 -> pick("KW")
            else -> pick("KNWA")
        }
        sb.append(first)
        if (prefixLen == 2) {
            sb.append(
                when {
                    first == 'A' -> pick(A_SECOND)
                    first == 'W' && suffixLen == 3 -> pick(W_2X3_SECOND)
                    else -> pick(MAINLAND_SECOND)
                }
            )
        }
        sb.append('0' + digit)
        for (k in 0 until suffixLen) {
            val twoByThree = prefixLen == 2 && suffixLen == 3
            sb.append(pick(if (k == 0 && twoByThree) SUFFIX_2X3_FIRST else "ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
        }
        return sb.toString()
    }

    private companion object {
        const val MAINLAND_SECOND = "ABCDEFGIJKMNOQRSTUVWXYZ"
        const val A_SECOND = "ABCDEFGHIJK"
        const val W_2X3_SECOND = "ABDEFGIJNOQSUVWXYZ"
        const val SUFFIX_2X3_FIRST = "ABCDEFGHIJKLMNOPQRSTUVWYZ"
        val US_SHAPES = listOf(1 to 2, 1 to 3, 1 to 3, 2 to 1, 2 to 2, 2 to 2, 2 to 3, 2 to 3, 2 to 3)
    }
}

/** Counts for the results screen. */
class BoxingStats {
    var tells = 0
    var dodged = 0
    var punches = 0
    var clean = 0
    var damage = 0
    var taken = 0
    var uppercuts = 0
    var knockdowns = 0
    var knocked = 0
    /** Tries and right per character of [BoxingRules.KOCH], this fight only. */
    val tries = IntArray(BoxingRules.KOCH.length)
    val right = IntArray(BoxingRules.KOCH.length)

    fun accuracy(): Int? = if (tells > 0) dodged * 100 / tells else null
    fun cleanPercent(): Int? = if (punches > 0) clean * 100 / punches else null

    internal fun count(c: Char, wasRight: Boolean) {
        val i = BoxingRules.indexOf(c)
        if (i < 0) return
        tries[i] = minOf(tries[i] + 1, 255)
        right[i] = minOf(right[i] + if (wasRight) 1 else 0, 255)
    }

    /** Characters sent this fight, worst first: (character, right, tries). Misses first, then the lowest share right, then the most tries. */
    fun worst(): List<Triple<Char, Int, Int>> =
        BoxingRules.KOCH.indices.filter { tries[it] > 0 }
            .sortedWith(compareBy({ right[it] * 1000 / tries[it] }, { -tries[it] }))
            .map { Triple(BoxingRules.KOCH[it], right[it], tries[it]) }
}

/** Saved progress: how far up the ladder you are, the hint setting, and your best score and wins against each fighter. */
class BoxingCareer(
    /** Fighters you can pick: the first is always open, and each win opens the next. */
    var unlocked: Int = 1,
    var hints: Boolean = true,
    /** Best scores in tens of points, so a long fight still fits 16 bits. */
    val best: IntArray = IntArray(BoxingRules.FIGHTERS),
    val wins: IntArray = IntArray(BoxingRules.FIGHTERS)
) {
    /** Records a finished fight; true when it opened the next fighter. */
    fun finished(fighter: Int, won: Boolean, score: Int): Boolean {
        if (fighter !in 0 until BoxingRules.FIGHTERS) return false
        best[fighter] = maxOf(best[fighter], minOf(score / SCORE_UNIT, 65535))
        if (!won) return false
        wins[fighter] = minOf(wins[fighter] + 1, 255)
        val opens = fighter + 1 == unlocked && fighter + 1 < BoxingRules.FIGHTERS
        if (opens) unlocked += 1
        return opens
    }

    fun bestScore(fighter: Int): Int = best[fighter] * SCORE_UNIT

    /** Every fighter beaten at least once. */
    fun champion(): Boolean = wins[BoxingRules.FIGHTERS - 1] > 0

    companion object {
        const val SCORE_UNIT = 10
    }
}

/** What happens between the bells. */
private sealed class Duel {
    /** The next attack starts at the deadline. */
    object Neutral : Duel()
    /** The tell is playing; you can already key it back. */
    object Tell : Duel()
    /** The tell ended; the punch lands at the deadline. */
    object Window : Duel()
    /** The punch is on its way: it lands, or misses you after a dodge, at the deadline. */
    data class Punch(val dodged: Boolean) : Duel()
    /** Your counters land until the deadline. */
    object Open : Duel()
    /** You armed a star: send the word before the deadline. */
    object Star : Duel()
    /** The pause after a punch landed on you. */
    object Stagger : Duel()
}

/**
 * One fight against a ladder fighter. Every call that moves time takes the
 * current tick count [now] in milliseconds.
 */
class BoxingFight(
    fighter: Int,
    seed: Int,
    hints: Boolean,
    /** Your keyer's dot length in milliseconds. */
    myDitMs: Int,
    callsign: String
) {
    val fighter: Int = fighter.coerceIn(0, BoxingRules.FIGHTERS - 1)
    var phase: BoxingPhase = BoxingPhase.Intro
        private set
    private var duel: Duel = Duel.Neutral
    /** When the current phase or duel step started, and when it ends. */
    private var since = 0
    private var due: Int? = null
    var round = 1
        private set
    private var roundEnds = 0
    /** Round time left while the clock is stopped: before the bell, on the canvas. */
    private var roundLeft = BoxingRules.ROUND_MS
    var foeHp: Int = BoxingRules.LADDER[this.fighter].hp
    var myHp: Int = BoxingRules.PLAYER_HP
    var stars = 0
    /** Clean counters toward the next star. */
    var cleanRun = 0
        private set
    var score = 0
        private set
    var tell = ""
        private set
    /** Your keying of the tell so far. */
    var answer = ""
        private set
    /** The tell is shown: while it plays with hints, and once it is over. */
    var tellShown = false
        private set
    /** The uppercut word, while a star is armed, and what you have sent of it. */
    var word = ""
        private set
    var typed = ""
        private set
    /** The referee's count over a fighter on the canvas. */
    var count = 0
        private set
    /** The count the opponent gets up at; GET_UP_COUNT stays down. */
    private var getUpAt = 0
    /** What you key to get up. */
    var getUp: Char = BoxingRules.KOCH[0]
        private set
    private var foeEvent: Pair<BoxingFoe, Int>? = null
    private var meEvent: Pair<BoxingMe, Int>? = null
    var callout: Pair<BoxingCallout, Int>? = null
        private set
    /** The punching hand of the attack. */
    var side = BoxingSide.RIGHT
        private set
    /** Your last punching hand, so jabs alternate. */
    private var mySide = BoxingSide.LEFT
    val stats = BoxingStats()
    private val rng = BoxingRng(seed)
    /** A tell waiting for the screen to play it. */
    private var play: String? = null
    /** The screen should stop the tell now. */
    var stop = false
    private var hints = hints
    private var myDitMs = myDitMs
    private var pausedAt: Int? = null
    private val callsign: String = callsign.take(BoxingRules.MAX_WORD)
    /** Drill weight per Koch character, from your letter statistics. */
    private var weights = IntArray(BoxingRules.KOCH.length) { 8 }
    /** Characters of the uppercut word keyed with timing better than bad. */
    private var starClean = 0
    private val copies = ArrayDeque<Pair<Char, Char?>>()

    val foe: BoxingFighter get() = BoxingRules.LADDER[fighter]

    /** The duel step's name, for the fixture's snapshots. */
    internal val duelName: String
        get() = when (val d = duel) {
            Duel.Neutral -> "Neutral"
            Duel.Tell -> "Tell"
            Duel.Window -> "Window"
            is Duel.Punch -> "Punch:${d.dodged}"
            Duel.Open -> "Open"
            Duel.Star -> "Star"
            Duel.Stagger -> "Stagger"
        }

    /** Takes the drill weight of each Koch character, so tells lean on the characters you copy worst. */
    fun learn(weights: IntArray) {
        require(weights.size == BoxingRules.KOCH.length)
        this.weights = weights.copyOf()
    }

    fun setHints(on: Boolean) { hints = on }
    fun setDitMs(ms: Int) { myDitMs = ms }

    /** The tell shows while it plays. */
    fun hinting(): Boolean = hints && foe.hint
    fun paused(): Boolean = pausedAt != null
    /** The opponent is open to counters. */
    fun open(): Boolean = phase == BoxingPhase.Fight && duel == Duel.Open
    /** A star is armed and its word is on screen. */
    fun starring(): Boolean = phase == BoxingPhase.Fight && duel == Duel.Star
    /** The tell is playing or waiting for your answer. */
    fun telling(): Boolean = phase == BoxingPhase.Fight && (duel == Duel.Tell || duel == Duel.Window)
    /** The tell is sounding now. */
    fun sounding(): Boolean = phase == BoxingPhase.Fight && duel == Duel.Tell

    /** Time left to answer the tell once it has played, or to send the uppercut word: (left, whole window). */
    fun window(now: Int): Pair<Int, Int>? {
        val d = due ?: return null
        if (phase != BoxingPhase.Fight || !(duel == Duel.Window || duel == Duel.Star)) return null
        return Pair(BoxingRules.msUntil(pausedAt ?: now, d), d - since)
    }

    /** Round time left. */
    fun clockMs(now: Int): Int {
        if (phase != BoxingPhase.Fight) return roundLeft
        return BoxingRules.msUntil(pausedAt ?: now, roundEnds)
    }

    /** The opponent's pose at [now], and when it started. */
    fun foePose(now: Int): Pair<BoxingFoe, Int> {
        foeEvent?.let { (pose, at) -> if (now - at in 0 until foeEventMs(pose)) return Pair(pose, at) }
        val d = duel
        val p = phase
        val pose: BoxingFoe = when (p) {
            BoxingPhase.Fight -> when (d) {
                Duel.Tell, Duel.Window -> BoxingFoe.Windup(side)
                is Duel.Punch -> if (d.dodged) BoxingFoe.Whiff(side) else BoxingFoe.Punch(side)
                Duel.Open -> BoxingFoe.Open
                Duel.Star -> BoxingFoe.Stunned
                Duel.Neutral, Duel.Stagger -> BoxingFoe.Idle
            }
            BoxingPhase.FoeDown -> if (getUpAt >= BoxingRules.GET_UP_COUNT) BoxingFoe.Out else BoxingFoe.Down
            BoxingPhase.MeDown -> BoxingFoe.Victory
            is BoxingPhase.Over -> when {
                p.won && p.how == BoxingOutcome.DECISION -> BoxingFoe.Idle
                p.won -> BoxingFoe.Out
                else -> BoxingFoe.Victory
            }
            BoxingPhase.Intro, BoxingPhase.RoundStart, BoxingPhase.RoundEnd -> BoxingFoe.Idle
        }
        return Pair(pose, since)
    }

    /** Your pose at [now], and when it started. */
    fun mePose(now: Int): Pair<BoxingMe, Int> {
        meEvent?.let { (pose, at) -> if (now - at in 0 until meEventMs(pose)) return Pair(pose, at) }
        val d = duel
        val p = phase
        val pose: BoxingMe = when {
            p == BoxingPhase.Fight && d is Duel.Punch && d.dodged -> BoxingMe.Dodge(side.other())
            p == BoxingPhase.MeDown -> BoxingMe.Down
            p is BoxingPhase.Over && p.won -> BoxingMe.Victory
            p is BoxingPhase.Over && p.how == BoxingOutcome.DECISION -> BoxingMe.Idle
            p is BoxingPhase.Over -> BoxingMe.Down
            else -> BoxingMe.Idle
        }
        return Pair(pose, since)
    }

    /** The tell to start playing now. */
    fun takePlay(): String? {
        val p = play
        play = null
        return p
    }

    /** When [update] should next run. */
    fun deadline(): Int? {
        if (pausedAt != null) return null
        val round = if (phase == BoxingPhase.Fight) roundEnds else null
        val a = due
        return when {
            a != null && round != null -> BoxingRules.earlier(a, round)
            else -> a ?: round
        }
    }

    private fun step(next: Duel, now: Int, dueAt: Int?) {
        duel = next
        since = now
        due = dueAt
    }

    private fun enter(next: BoxingPhase, now: Int, dueAt: Int?) {
        phase = next
        since = now
        due = dueAt
    }

    /** Rings the bell: from the intro card, or between rounds. False when it is not time. */
    fun start(now: Int): Boolean {
        when {
            phase == BoxingPhase.Intro -> {}
            phase == BoxingPhase.RoundEnd && due == null -> nextRound()
            else -> return false
        }
        enter(BoxingPhase.RoundStart, now, now + BoxingRules.ROUND_BANNER_MS)
        foeEvent = null
        meEvent = null
        return true
    }

    fun pause(now: Int) {
        if (pausedAt != null || phase is BoxingPhase.Over || phase == BoxingPhase.Intro) return
        pausedAt = now
        stop = true
        // A tell cut short is taken back, and a new one starts after the pause.
        if (phase == BoxingPhase.Fight && duel == Duel.Tell) {
            stats.tells -= 1
            tell = ""
            answer = ""
            tellShown = false
            step(Duel.Neutral, now, now + 800)
        }
    }

    fun resume(now: Int) {
        val at = pausedAt ?: return
        pausedAt = null
        val gap = now - at
        due = due?.plus(gap)
        roundEnds += gap
        since += gap
        foeEvent = foeEvent?.let { Pair(it.first, it.second + gap) }
        meEvent = meEvent?.let { Pair(it.first, it.second + gap) }
        callout = callout?.let { Pair(it.first, it.second + gap) }
    }

    private fun nextRound() {
        round += 1
        roundLeft = BoxingRules.ROUND_MS
        // The corners patch both fighters up a little.
        val f = foe
        foeHp = minOf(foeHp + (f.hp - foeHp) / 4, f.hp)
        myHp = minOf(myHp + (BoxingRules.PLAYER_HP - myHp) / 3, BoxingRules.PLAYER_HP)
    }

    /** Runs whatever is due at [now]. */
    fun update(now: Int) {
        if (pausedAt != null) return
        val isDue = due?.let { BoxingRules.reached(now, it) } ?: false
        when (phase) {
            BoxingPhase.RoundStart -> if (isDue) {
                enter(BoxingPhase.Fight, now, null)
                roundEnds = now + roundLeft
                step(Duel.Neutral, now, now + 900)
            }
            BoxingPhase.Fight -> {
                // A punch on its way still lands after the bell.
                if (BoxingRules.reached(now, roundEnds) && duel !is Duel.Punch) return bell(now)
                if (isDue) duelDue(now)
            }
            BoxingPhase.FoeDown -> if (isDue) foeCount(now)
            BoxingPhase.MeDown -> if (isDue) myCount(now)
            BoxingPhase.RoundEnd -> if (isDue) due = null
            else -> {}
        }
    }

    private fun bell(now: Int) {
        stop = true
        roundLeft = 0
        clearTexts()
        if (round >= BoxingRules.ROUNDS) {
            val won = points() > foePoints()
            if (won) score += 2000
            return finish(won, BoxingOutcome.DECISION, now)
        }
        enter(BoxingPhase.RoundEnd, now, now + BoxingRules.ROUND_END_MS)
        duel = Duel.Neutral
    }

    private fun clearTexts() {
        tell = ""
        answer = ""
        tellShown = false
        word = ""
        typed = ""
    }

    /** The judges' points for you: damage dealt, dodges and knockdowns. */
    fun points(): Int = stats.damage + 2 * stats.dodged + 10 * stats.knockdowns

    /** The judges' points for the opponent. */
    fun foePoints(): Int = stats.taken + 10 * stats.knocked

    private fun duelDue(now: Int) {
        when (val d = duel) {
            Duel.Neutral, Duel.Stagger -> attack(now)
            Duel.Tell -> {}
            Duel.Window -> {
                val t = tell
                for (c in t.drop(answer.length)) judge(c, null)
                callout = Pair(BoxingCallout.TooSlow, now)
                throwPunch(now, false)
            }
            is Duel.Punch -> if (!d.dodged) land(now) else step(Duel.Open, now, now + foe.openMs)
            Duel.Open, Duel.Star -> rest(now)
        }
    }

    private fun rest(now: Int) {
        val wait = foe.restLeastMs + rng.below(foe.restExtraMs + 1)
        word = ""
        typed = ""
        step(Duel.Neutral, now, now + wait)
    }

    /** Starts a tell. */
    private fun attack(now: Int) {
        tell = pickTell()
        answer = ""
        tellShown = hinting()
        side = if (rng.below(2) == 0) BoxingSide.LEFT else BoxingSide.RIGHT
        stats.tells += 1
        play = tell
        step(Duel.Tell, now, null)
    }

    /** The tell finished playing at [now]: the reply window opens. */
    fun tellPlayed(now: Int) {
        if (phase != BoxingPhase.Fight || duel != Duel.Tell || pausedAt != null) return
        val keying = tell.sumOf { BoxingRules.units(it) + 3 } * myDitMs
        step(Duel.Window, now, now + foe.reactMs + keying)
    }

    internal fun pickTell(): String {
        val most = foe.maxTell
        var len = 1 + rng.below(most)
        // Long tells come up less often than short ones.
        if (len > 1 && rng.below(3) == 0) len -= 1
        val sb = StringBuilder()
        repeat(len) { sb.append(pickChar()) }
        return sb.toString()
    }

    /** A character of this fighter's set, weighted toward weak ones and the fighter's own. */
    internal fun pickChar(): Char {
        val f = foe
        val n = f.koch
        val ownFrom = n - f.newChars(fighter).length
        fun weight(i: Int) = weights[i] * if (i >= ownFrom) 3 else 1
        val total = (0 until n).sumOf { weight(it) }
        var r = rng.below(total)
        for (i in 0 until n) {
            if (r < weight(i)) return BoxingRules.KOCH[i]
            r -= weight(i)
        }
        return BoxingRules.KOCH[n - 1]
    }

    /** The opponent throws: you dodged, or it lands at the end of the swing. */
    private fun throwPunch(now: Int, dodged: Boolean) {
        stop = true
        tellShown = true
        val swing = if (dodged) BoxingRules.WHIFF_MS else BoxingRules.PUNCH_MS
        step(Duel.Punch(dodged), now, now + swing)
    }

    private fun land(now: Int) {
        val power = foe.power
        myHp = maxOf(myHp - power, 0)
        stats.taken += power
        cleanRun = 0
        meEvent = Pair(BoxingMe.Hurt, now)
        if (myHp == 0) return knockedDown(now)
        val wait = BoxingRules.STAGGER_MS + foe.restLeastMs / 2
        step(Duel.Stagger, now, now + wait)
    }

    /** A character you keyed, with the worst timing quality in it. */
    fun key(c: Char, quality: BoxingQuality, now: Int) {
        if (pausedAt != null) return
        when (phase) {
            BoxingPhase.Fight -> {}
            BoxingPhase.MeDown -> return tryGetUp(c, now)
            else -> return
        }
        when (duel) {
            Duel.Tell, Duel.Window -> answerTell(c, now)
            Duel.Open -> counter(c, quality, now)
            Duel.Star -> starChar(c, quality, now)
            Duel.Neutral, Duel.Stagger -> {
                // Into a raised guard.
                mySide = mySide.other()
                meEvent = Pair(BoxingMe.Blocked(mySide), now)
                foeEvent = Pair(BoxingFoe.Block, now)
                callout = Pair(BoxingCallout.Blocked, now)
            }
            is Duel.Punch -> {}
        }
    }

    /** You keyed a pattern no character uses. */
    fun keyUnknown(now: Int) {
        if (pausedAt != null) return
        val p = phase
        val d = duel
        when {
            p == BoxingPhase.MeDown -> tryGetUp('*', now)
            p == BoxingPhase.Fight && (d == Duel.Tell || d == Duel.Window) -> answerTell('*', now)
            p == BoxingPhase.Fight && d == Duel.Open -> {
                meEvent = Pair(BoxingMe.Miss, now)
                callout = Pair(BoxingCallout.Missed, now)
            }
            p == BoxingPhase.Fight && d == Duel.Star -> starChar('*', BoxingQuality.BAD, now)
        }
    }

    private fun answerTell(c: Char, now: Int) {
        val at = answer.length
        val want = tell.getOrNull(at) ?: return
        answer += c
        if (c != want) {
            judge(want, c)
            for (rest in tell.drop(at + 1)) judge(rest, null)
            callout = Pair(BoxingCallout.Wrong(c), now)
            return throwPunch(now, false)
        }
        judge(want, want)
        if (answer.length == tell.length) {
            stats.dodged += 1
            // A dodge before the tell even ends scores more.
            score += if (duel == Duel.Tell) 80 else 50
            callout = Pair(BoxingCallout.Dodge, now)
            throwPunch(now, true)
        }
    }

    private fun counter(c: Char, quality: BoxingQuality, now: Int) {
        val punch = BoxingRules.punchOf(c) ?: return
        val hit = BoxingRules.damage(c, quality)
        stats.punches += 1
        mySide = mySide.other()
        val pose = when (punch) {
            BoxingPunch.Jab -> BoxingMe.Jab(mySide)
            BoxingPunch.Hook -> BoxingMe.Hook(mySide)
            is BoxingPunch.Combo -> BoxingMe.Combo(punch.punches)
        }
        meEvent = Pair(pose, now)
        // A hook from your left hand snaps the head to your right.
        foeEvent = Pair(BoxingFoe.Hurt(mySide.other()), now)
        score += hit * 10
        if (quality == BoxingQuality.GOOD) {
            stats.clean += 1
            score += 20
            cleanRun += 1
            callout = Pair(BoxingCallout.Clean, now)
            if (cleanRun >= BoxingRules.CLEAN_PER_STAR) {
                cleanRun = 0
                if (stars < BoxingRules.MAX_STARS) {
                    stars += 1
                    callout = Pair(BoxingCallout.Star, now)
                }
            }
        } else {
            callout = null
        }
        hitFoe(hit, false, now)
    }

    private fun hitFoe(hit: Int, uppercut: Boolean, now: Int) {
        foeHp = maxOf(foeHp - hit, 0)
        stats.damage += hit
        if (foeHp == 0) foeKnockedDown(uppercut, now)
    }

    /** Arms a star, if you have one: the opponent is stunned while you send the word. False when no star can be armed now. */
    fun star(now: Int): Boolean {
        val ready = phase == BoxingPhase.Fight && stars > 0 && pausedAt == null &&
            (duel == Duel.Neutral || duel == Duel.Open || duel == Duel.Stagger)
        if (!ready) return false
        stars -= 1
        word = pickWord()
        typed = ""
        starClean = 0
        callout = null
        foeEvent = null
        val time = BoxingRules.STAR_START_MS + BoxingRules.STAR_CHAR_MS * word.length
        step(Duel.Star, now, now + time)
        return true
    }

    /** A word of this fighter's characters, your callsign, or a callsign. */
    internal fun pickWord(): String {
        val set = foe.set
        val fits = BoxingRules.WORDS.filter { w -> w.all { it in set } }
        val count = fits.size
        val call = count == 0 || rng.below(4) == 0
        if (!call) return fits.getOrNull(rng.below(count)) ?: ""
        // Early fighters know too few characters for most calls: send your own.
        val yours = callsign.isNotEmpty() && (fighter < 4 || rng.below(2) == 0)
        if (yours) return callsign
        val digit = rng.below(10)
        return rng.usCall(digit).take(BoxingRules.MAX_WORD)
    }

    private fun starChar(c: Char, quality: BoxingQuality, now: Int) {
        val at = typed.length
        val want = word.getOrNull(at) ?: return
        typed += c
        if (c != want) {
            meEvent = Pair(BoxingMe.Miss, now)
            callout = Pair(BoxingCallout.Missed, now)
            return rest(now)
        }
        if (quality != BoxingQuality.BAD) starClean += 1
        if (typed.length < word.length) return
        val clean = starClean * 100 / word.length
        val full = BoxingRules.UPPERCUT_DAMAGE + 2 * word.length
        val hit = if (clean >= 80) full else full * 3 / 4
        stats.uppercuts += 1
        score += 500
        meEvent = Pair(BoxingMe.Uppercut, now)
        foeEvent = Pair(BoxingFoe.Hurt(BoxingSide.LEFT), now)
        callout = Pair(BoxingCallout.Uppercut, now)
        rest(now)
        hitFoe(hit, true, now)
    }

    private fun foeKnockedDown(uppercut: Boolean, now: Int) {
        stop = true
        stats.knockdowns += 1
        score += 1000
        roundLeft = BoxingRules.msUntil(now, roundEnds)
        count = 0
        callout = Pair(BoxingCallout.Down, now)
        clearTexts()
        val kd = stats.knockdowns
        // Each knockdown keeps the opponent down longer. An uppercut that empties the health bar,
        // or the third knockdown, keeps him down for good.
        getUpAt = if (uppercut || kd >= BoxingRules.KNOCKDOWNS_TO_KO) BoxingRules.GET_UP_COUNT else 2 + 2 * kd
        foeEvent = null
        duel = Duel.Neutral
        enter(BoxingPhase.FoeDown, now, now + BoxingRules.COUNT_MS)
    }

    private fun foeCount(now: Int) {
        count += 1
        if (count >= BoxingRules.GET_UP_COUNT) {
            score += 5000 + roundLeft / 1000 * 10
            val how = if (stats.knockdowns >= BoxingRules.KNOCKDOWNS_TO_KO) BoxingOutcome.TKO else BoxingOutcome.KO
            return finish(true, how, now)
        }
        if (count < getUpAt) {
            due = now + BoxingRules.COUNT_MS
            return
        }
        val share = 60 - 15 * stats.knockdowns
        foeHp = maxOf(foe.hp * share / 100, 10)
        foeEvent = Pair(BoxingFoe.Rise, now)
        callout = Pair(BoxingCallout.Up, now)
        enter(BoxingPhase.Fight, now, null)
        roundEnds = now + roundLeft
        rest(now)
    }

    private fun knockedDown(now: Int) {
        stop = true
        stats.knocked += 1
        meEvent = null
        roundLeft = BoxingRules.msUntil(now, roundEnds)
        count = 0
        callout = Pair(BoxingCallout.Down, now)
        clearTexts()
        duel = Duel.Neutral
        if (stats.knocked >= BoxingRules.KNOCKDOWNS_TO_KO) return finish(false, BoxingOutcome.TKO, now)
        getUp = weakestKnown()
        enter(BoxingPhase.MeDown, now, now + BoxingRules.COUNT_MS)
    }

    /** The character you copy worst among the ones this fighter sends (the last of equals, as the original picks). */
    private fun weakestKnown(): Char {
        val n = foe.koch
        var worst = 0
        var bestW = Int.MIN_VALUE
        var bestR = Int.MIN_VALUE
        for (i in 0 until n) {
            val r = rng.below(4)
            if (weights[i] > bestW || (weights[i] == bestW && r >= bestR)) {
                bestW = weights[i]
                bestR = r
                worst = i
            }
        }
        return BoxingRules.KOCH[worst]
    }

    private fun myCount(now: Int) {
        count += 1
        if (count >= BoxingRules.GET_UP_COUNT) return finish(false, BoxingOutcome.KO, now)
        due = now + BoxingRules.COUNT_MS
    }

    private fun tryGetUp(c: Char, now: Int) {
        judge(getUp, c)
        if (c != getUp) {
            callout = Pair(BoxingCallout.Wrong(c), now)
            return
        }
        val share = 70 - 20 * stats.knocked
        myHp = maxOf(BoxingRules.PLAYER_HP * share / 100, 15)
        meEvent = Pair(BoxingMe.Rise, now)
        callout = Pair(BoxingCallout.Up, now)
        enter(BoxingPhase.Fight, now, null)
        roundEnds = now + roundLeft
        rest(now)
    }

    private fun finish(won: Boolean, how: BoxingOutcome, now: Int) {
        stop = true
        foeEvent = null
        meEvent = null
        enter(BoxingPhase.Over(won, how), now, null)
    }

    /** One judged character: [copied] is null when it was missed. */
    private fun judge(sent: Char, copied: Char?) {
        stats.count(sent, copied == sent)
        // An unknown pattern is a miss, not a mixup.
        val known = copied?.takeIf { BoxingRules.indexOf(it) >= 0 }
        if (copies.size == COPIES_MAX) copies.removeFirst()
        copies.addLast(Pair(sent, known))
    }

    /** Takes the characters judged since the last call, for the letter statistics: (sent, copied), copied null when missed. */
    fun takeCopies(each: (Char, Char?) -> Unit) {
        for ((sent, copied) in copies) each(sent, copied)
        copies.clear()
    }

    private companion object {
        const val COPIES_MAX = 8

        fun foeEventMs(pose: BoxingFoe): Int = when (pose) {
            is BoxingFoe.Hurt -> 380
            BoxingFoe.Block -> 320
            BoxingFoe.Rise -> 700
            else -> 0
        }

        fun meEventMs(pose: BoxingMe): Int = when (pose) {
            is BoxingMe.Jab -> 240
            is BoxingMe.Hook -> 320
            is BoxingMe.Combo -> 150 * pose.punches + 120
            BoxingMe.Uppercut -> 760
            is BoxingMe.Blocked -> 280
            BoxingMe.Miss -> 300
            BoxingMe.Hurt -> 520
            BoxingMe.Rise -> 700
            else -> 0
        }
    }
}

/** What a mark or gap is, for judging timing. */
enum class BoxingKind(val ideal: Double) { DIT(1.0), DAH(3.0), ELEMENT_GAP(1.0), CHAR_GAP(3.0), WORD_GAP(7.0) }

/** A character the key spelled, and the worst timing in it; [char] is null for a pattern no character uses. */
data class BoxingJudged(val char: Char?, val quality: BoxingQuality)

/**
 * Judges keying the way the game does (logic/src/timing.rs): each mark and gap is measured in dots,
 * a character is judged on its own marks and the gaps inside it (not the pause before it), and a
 * character ends two dots after its last mark. Feed it key edges with [edge] and call [poll] on the clock.
 */
object BoxingTiming {
    private const val GOOD_ERROR = 0.20
    private const val DAH_DOTS = 2.0
    private const val CHAR_GAP_DOTS = 2.0
    private const val WORD_GAP_DOTS = 5.0

    /** The decoder's split points below and above the ideal length, in dots. */
    private fun splits(k: BoxingKind): Pair<Double?, Double?> = when (k) {
        BoxingKind.DIT -> null to DAH_DOTS
        BoxingKind.DAH -> DAH_DOTS to null
        BoxingKind.ELEMENT_GAP -> null to CHAR_GAP_DOTS
        BoxingKind.CHAR_GAP -> CHAR_GAP_DOTS to WORD_GAP_DOTS
        BoxingKind.WORD_GAP -> WORD_GAP_DOTS to null
    }

    /**
     * Good: within 20% of ideal. Bad: more than halfway from ideal to a decoder split point.
     * Off: between, and beyond ideal on a side with no split point.
     */
    fun quality(kind: BoxingKind, units: Double): BoxingQuality {
        val ideal = kind.ideal
        if (Math.abs(units / ideal - 1.0) <= GOOD_ERROR) return BoxingQuality.GOOD
        val (below, above) = splits(kind)
        val split = if (units < ideal) below else above
        return if (split != null && Math.abs(units - ideal) > Math.abs(split - ideal) / 2.0) BoxingQuality.BAD else BoxingQuality.OFF
    }

    private val CODES: Map<String, Char> by lazy {
        val m = HashMap<String, Char>()
        for (c in BoxingRules.KOCH) BoxingRules.code(c)?.let { m.putIfAbsent(it, c) }
        m
    }

    /** The character for dots and dashes [marks], or null. */
    fun charOf(marks: String): Char? = CODES[marks]

    const val MAX_MARKS = 8
}

class BoxingKeying {
    private var downAt: Int? = null
    private var upAt: Int? = null
    private val marks = StringBuilder()
    private var worst = BoxingQuality.GOOD

    /** The dots and dashes of the character being keyed. */
    fun marks(): String = marks.toString()

    /** The worst timing so far in the character being keyed. */
    fun worst(): BoxingQuality = worst

    fun reset() {
        downAt = null; upAt = null; marks.setLength(0); worst = BoxingQuality.GOOD
    }

    private fun worse(a: BoxingQuality, b: BoxingQuality) = if (b.ordinal > a.ordinal) b else a

    private fun finish(): BoxingJudged {
        val j = BoxingJudged(if (marks.length > BoxingTiming.MAX_MARKS) null else BoxingTiming.charOf(marks.toString()), worst)
        marks.setLength(0); worst = BoxingQuality.GOOD; upAt = null
        return j
    }

    /** The key went down or up at [atMs]; returns the character the edge finished, if it was a down after a long pause. */
    fun edge(down: Boolean, atMs: Int, ditMs: Int): BoxingJudged? {
        if (down) {
            if (downAt != null) return null
            var done: BoxingJudged? = null
            val up = upAt
            if (up != null && marks.isNotEmpty()) {
                val gap = (atMs - up).toDouble() / ditMs
                if (gap >= 2.0) done = finish() else worst = worse(worst, BoxingTiming.quality(BoxingKind.ELEMENT_GAP, gap))
            }
            downAt = atMs
            upAt = null
            return done
        }
        val start = downAt ?: return null
        downAt = null
        upAt = atMs
        val units = (atMs - start).toDouble() / ditMs
        val dah = units >= 2.0
        marks.append(if (dah) '-' else '.')
        worst = worse(worst, BoxingTiming.quality(if (dah) BoxingKind.DAH else BoxingKind.DIT, units))
        return null
    }

    /** Ends the character once the key has been up two dots. */
    fun poll(nowMs: Int, ditMs: Int): BoxingJudged? {
        val up = upAt ?: return null
        if (downAt != null || marks.isEmpty()) return null
        return if (nowMs - up >= 2 * ditMs) finish() else null
    }
}
