package app.anothermorsetrainer.morsekit

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * CW Frogger (#190, part of #170): a frog crosses three lanes of traffic, a
 * median and three lanes of river to the far bank. Movement is classic
 * Frogger — a hop at a time in four directions — and Morse decides what is
 * safe: every vehicle and log carries a character from the learner's set, and
 * each lane is cued by sending one of them in Morse. On the road the cued
 * vehicle is harmless and every other one is fatal; on the river only the
 * cued log floats. Pure game rules — no clock, no audio, no UI. Time enters
 * through [FroggerGame.advance], randomness through the injected [Random]
 * (which picks labels and cues only — object positions are laid out evenly,
 * so a scripted crossing is reproducible on both ports).
 *
 * Translated from MorseKit/Frogger.swift; the two must stay twins, and
 * fixtures/frogger.json pins both.
 */

/** A hop. Up is toward the goal bank. */
enum class FroggerDirection { UP, DOWN, LEFT, RIGHT }

/** What a lane carries. */
enum class FroggerLaneKind { ROAD, RIVER }

/**
 * How much the labels help, by wave: every label shown; a lane's labels
 * hidden once it has been cued (cross from memory); no labels at all, the
 * objects announcing themselves in Morse as they enter.
 */
enum class FroggerLabelStage { VISIBLE, MEMORY, HIDDEN }

/**
 * One lane's layout: where it is, which way it flows, and how its objects are
 * sized and spaced. [baseSpeed] is board widths per second at wave 1 on Normal.
 */
data class FroggerLane(
    val row: Int,
    val kind: FroggerLaneKind,
    /** +1 flows right, -1 flows left. */
    val direction: Int,
    val count: Int,
    val width: Double,
    val baseSpeed: Double
)

/**
 * A vehicle or a log. [x] is its centre across the board, 0…1 wrapping;
 * [width] its span in the same units.
 */
data class FroggerObject(
    val id: Int,
    val row: Int,
    val kind: FroggerLaneKind,
    val character: Char,
    val x: Double,
    val width: Double
)

/** Where the frog is: its row (0 the start bank) and centre across the board. */
data class FroggerFrog(val row: Int, val x: Double)

/** What one call to [FroggerGame.advance] or [FroggerGame.move] did, in order. */
sealed class FroggerEvent {
    /** The frog moved. */
    object Hopped : FroggerEvent()
    /** A lane got its cue: the character to send for [row]. */
    data class Cue(val row: Int, val character: Char) : FroggerEvent()
    /** A cued vehicle passed through the frog — a correct decision. */
    data class Passed(val obj: FroggerObject, val points: Int) : FroggerEvent()
    /** The frog landed on a cued log — a correct decision. */
    data class Landed(val obj: FroggerObject, val points: Int) : FroggerEvent()
    /** A vehicle not carrying the cue hit the frog. */
    data class Squashed(val obj: FroggerObject, val cue: Char) : FroggerEvent()
    /** The frog landed on a log not carrying the cue. */
    data class Sank(val obj: FroggerObject, val cue: Char) : FroggerEvent()
    /** The frog landed in the water. */
    data class Drowned(val cue: Char) : FroggerEvent()
    /** The frog reached the goal bank; the next wave has begun. */
    data class Crossed(val points: Int) : FroggerEvent()
    /** An object wrapped round and entered the board again. */
    data class Entered(val obj: FroggerObject) : FroggerEvent()
    object GameOver : FroggerEvent()
}

class FroggerGame(
    val config: Config,
    private val rng: Random = Random.Default
) {
    data class Config(
        /** The characters the objects carry; empty falls back to the first two Koch characters. */
        val characters: List<Char>,
        val difficulty: InvadersDifficulty = InvadersDifficulty.NORMAL,
        val lives: Int = 3,
        /** The learner's character speed: where the speed ramp ends. */
        val characterWpm: Double = 20.0
    ) {
        val startWpm: Double get() = rampStart(characterWpm)
        val targetWpm: Double get() = max(minWpm, characterWpm)
    }

    private val pool: List<Char> = config.characters.map { it.uppercaseChar() }.distinct()
        .ifEmpty { MorseCode.kochOrder.take(2) }

    var objects: List<FroggerObject> = emptyList()
        private set
    var frog = FroggerFrog(startRow, 0.5)
        private set
    /** The log the frog is riding, if any. */
    var ridingId: Int? = null
        private set
    /** The cue for each cued lane this crossing, by row. */
    var cues: Map<Int, Char> = emptyMap()
        private set
    var score = 0
        private set
    var wave = 1
        private set
    var lives = max(1, config.lives)
        private set
    /** Consecutive correct decisions since the last death. */
    var combo = 0
        private set
    var bestCombo = 0
        private set
    /** Correct decisions: cued vehicles passed through, cued logs landed on. */
    var decisions = 0
        private set
    /** Deaths: wrong vehicle, wrong log, or water. */
    var misses = 0
        private set
    var crossings = 0
        private set
    /** Game time in seconds, the sum of every [advance]. */
    var elapsed = 0.0
        private set
    var isOver = false
        private set
    var currentWpm: Double = config.startWpm
        private set
    var bestWpm: Double = config.startWpm
        private set

    private var maxRowThisCrossing = 0
    /** Cued vehicles currently overlapping the frog, already credited. */
    private var passingIds: Set<Int> = emptySet()
    private var nextId = 1

    init {
        layOutLanes()
        assignCue(startRow + 1)
    }

    val labelStage: FroggerLabelStage get() = labelStage(wave)

    /** Whether [row]'s labels are drawn at this wave. */
    fun isLabelVisible(row: Int): Boolean = when (labelStage) {
        FroggerLabelStage.VISIBLE -> true
        FroggerLabelStage.MEMORY -> cues[row] == null
        FroggerLabelStage.HIDDEN -> false
    }

    /** The cue for the lane above the frog, if that is a lane. */
    val nextCue: Char? get() = cues[frog.row + 1]

    /** The row the next cue is for, if the frog has a lane above it. */
    val nextCueRow: Int? get() = if (lane(frog.row + 1) == null) null else frog.row + 1

    fun speed(row: Int): Double {
        val lane = lane(row) ?: return 0.0
        return laneSpeed(lane.baseSpeed, wave, config.difficulty)
    }

    /** Correct decisions over decisions and deaths, 0…1. */
    val accuracy: Double get() = if (decisions + misses == 0) 0.0 else decisions.toDouble() / (decisions + misses)

    /** The multiplier the next correct decision earns. */
    val multiplier: Int get() = multiplier(combo + 1)

    /**
     * Move time forward by [seconds]: the lanes flow, a ridden log carries the
     * frog, and road contact is checked. Returns what happened, in order. A
     * finished game ignores time.
     */
    fun advance(seconds: Double): List<FroggerEvent> {
        if (isOver || seconds <= 0) return emptyList()
        val events = ArrayList<FroggerEvent>()
        elapsed += seconds
        objects = objects.map { obj ->
            val lane = lane(obj.row) ?: return@map obj
            val delta = lane.direction * speed(lane.row) * seconds
            var x = obj.x + delta
            if (ridingId == obj.id) frog = frog.copy(x = wrap(frog.x + delta))
            if (x >= 1 || x < 0) {
                x = wrap(x)
                val moved = obj.copy(x = x)
                events.add(FroggerEvent.Entered(moved))
                moved
            } else {
                obj.copy(x = x)
            }
        }
        if (lane(frog.row)?.kind == FroggerLaneKind.ROAD) checkRoad(events)
        return events
    }

    /**
     * Hop one row or one column. Returns what happened; a hop that would not
     * move the frog (down off the start bank, sideways at the edge) returns
     * nothing. A finished game ignores hops.
     */
    fun move(direction: FroggerDirection): List<FroggerEvent> {
        if (isOver) return emptyList()
        var row = frog.row
        var x = frog.x
        when (direction) {
            FroggerDirection.UP -> row += 1
            FroggerDirection.DOWN -> row = max(startRow, row - 1)
            FroggerDirection.LEFT -> x = max(hop / 2, x - hop)
            FroggerDirection.RIGHT -> x = min(1 - hop / 2, x + hop)
        }
        if (row == frog.row && abs(x - frog.x) < 1e-9) return emptyList()
        val events = ArrayList<FroggerEvent>()
        events.add(FroggerEvent.Hopped)
        frog = FroggerFrog(row, x)
        ridingId = null
        passingIds = emptySet()
        if (row > maxRowThisCrossing) {
            maxRowThisCrossing = row
            score += pointsPerHop
        }
        if (row == goalRow) {
            score += pointsPerCrossing
            crossings += 1
            wave += 1
            layOutLanes()
            resetFrog()
            events.add(FroggerEvent.Crossed(pointsPerCrossing))
            assignCue(startRow + 1, events)
            return events
        }
        val lane = lane(row)
        val dead = when (lane?.kind) {
            FroggerLaneKind.RIVER -> checkRiver(events)
            FroggerLaneKind.ROAD -> checkRoad(events)
            null -> false
        }
        if (!dead && !isOver) assignCue(row + 1, events)
        return events
    }

    // Rules

    /**
     * Road contact for the frog's current row. Any vehicle not carrying the
     * cue kills; each cued vehicle is credited once for as long as it
     * overlaps. Returns whether the frog died.
     */
    private fun checkRoad(events: MutableList<FroggerEvent>): Boolean {
        val lane = lane(frog.row)
        if (lane == null || lane.kind != FroggerLaneKind.ROAD) return false
        val cue = cues[frog.row]
        val overlapping = objects.filter {
            it.row == frog.row && wrappedDistance(it.x, frog.x) < it.width / 2 + frogHalfWidth
        }
        val wrong = overlapping.firstOrNull { it.character != cue }
        if (wrong != null) {
            die(FroggerEvent.Squashed(wrong, cue ?: wrong.character), events)
            return true
        }
        for (obj in overlapping) {
            if (obj.id in passingIds) continue
            val points = credit()
            events.add(FroggerEvent.Passed(obj, points))
        }
        passingIds = overlapping.map { it.id }.toSet()
        return false
    }

    /**
     * The landing on a river row: a cued log under the frog's centre is a
     * correct decision and carries it; another log sinks; water drowns.
     */
    private fun checkRiver(events: MutableList<FroggerEvent>): Boolean {
        val cue = cues[frog.row]
        val log = objects
            .filter { it.row == frog.row && wrappedDistance(it.x, frog.x) < it.width / 2 }
            .minByOrNull { wrappedDistance(it.x, frog.x) }
        if (log == null) {
            die(FroggerEvent.Drowned(cue ?: ' '), events)
            return true
        }
        if (log.character == cue) {
            ridingId = log.id
            val points = credit()
            events.add(FroggerEvent.Landed(log, points))
            return false
        }
        die(FroggerEvent.Sank(log, cue ?: log.character), events)
        return true
    }

    private fun credit(): Int {
        combo += 1
        bestCombo = max(bestCombo, combo)
        val points = pointsPerDecision * multiplier(combo)
        score += points
        decisions += 1
        if (decisions % decisionsPerRampStep == 0) {
            currentWpm = min(config.targetWpm, currentWpm + rampStep)
            bestWpm = max(bestWpm, currentWpm)
        }
        return points
    }

    private fun die(event: FroggerEvent, events: MutableList<FroggerEvent>) {
        lives = max(0, lives - 1)
        misses += 1
        combo = 0
        currentWpm = max(config.startWpm, currentWpm - rampStep)
        events.add(event)
        resetFrog()
        if (lives == 0) {
            isOver = true
            events.add(FroggerEvent.GameOver)
        } else {
            assignCue(startRow + 1, events)
        }
    }

    private fun resetFrog() {
        frog = FroggerFrog(startRow, 0.5)
        ridingId = null
        passingIds = emptySet()
        maxRowThisCrossing = 0
        cues = emptyMap()
    }

    /**
     * Give [row] its cue if it is a lane without one: one of the characters
     * its objects carry, so there is always something safe to aim for.
     */
    private fun assignCue(row: Int, events: MutableList<FroggerEvent>) {
        val character = assignCue(row) ?: return
        events.add(FroggerEvent.Cue(row, character))
    }

    private fun assignCue(row: Int): Char? {
        if (lane(row) == null || cues[row] != null) return null
        val labels = objects.filter { it.row == row }.map { it.character }
        if (labels.isEmpty()) return null
        val character = labels[rng.nextInt(labels.size)]
        cues = cues + (row to character)
        return character
    }

    /** Fresh objects in every lane, evenly spaced, each with a random label. */
    private fun layOutLanes() {
        val fresh = ArrayList<FroggerObject>()
        for (lane in lanes) {
            for (i in 0 until lane.count) {
                val character = pool[rng.nextInt(pool.size)]
                fresh.add(FroggerObject(nextId, lane.row, lane.kind, character, (i + 0.5) / lane.count, lane.width))
                nextId += 1
            }
        }
        objects = fresh
    }

    companion object {
        // The board. Rows count up from the start bank; the lanes are laid out
        // so each section gets faster toward the middle of the crossing.
        const val rows = 9
        const val startRow = 0
        const val medianRow = 4
        const val goalRow = 8
        /** Sideways hops across the board. */
        const val columns = 7
        val hop: Double get() = 1.0 / columns
        /** Half the frog's width for road contact, in board widths. */
        const val frogHalfWidth = 0.04
        val lanes: List<FroggerLane> = listOf(
            FroggerLane(1, FroggerLaneKind.ROAD, 1, 3, 0.12, 0.16),
            FroggerLane(2, FroggerLaneKind.ROAD, -1, 3, 0.12, 0.20),
            FroggerLane(3, FroggerLaneKind.ROAD, 1, 3, 0.12, 0.24),
            FroggerLane(5, FroggerLaneKind.RIVER, -1, 3, 0.18, 0.14),
            FroggerLane(6, FroggerLaneKind.RIVER, 1, 3, 0.18, 0.18),
            FroggerLane(7, FroggerLaneKind.RIVER, -1, 3, 0.18, 0.22)
        )

        // Speeds tighten 8% a wave up to a cap, then difficulty stretches or
        // compresses the whole board.
        const val waveSpeedGrowth = 1.08
        const val maxLaneSpeed = 0.5

        const val pointsPerHop = 10
        const val pointsPerDecision = 50
        const val pointsPerCrossing = 200

        /**
         * The difficulty ladder: labels shown through wave 2, hidden once cued
         * from wave 3, gone (objects announce themselves) from wave 5.
         */
        const val memoryFromWave = 3
        const val hiddenFromWave = 5

        // Speed ramp, the game's own constants (Invaders has its own): open 8
        // WPM under the character speed, never under the app-wide floor, step
        // up 2 every four correct decisions in total, step back 2 on a death,
        // never under the start.
        const val minWpm = 15.0
        const val rampStartOffset = 8.0
        const val rampStep = 2.0
        const val decisionsPerRampStep = 4

        /** A lane's speed in board widths per second for a wave and difficulty. */
        fun laneSpeed(baseSpeed: Double, wave: Int, difficulty: InvadersDifficulty): Double =
            min(maxLaneSpeed, baseSpeed * waveSpeedGrowth.pow(max(0, wave - 1))) / difficulty.timeScale

        /** Combo multiplier: ×1 for the first three decisions in a row, ×2 for the next three, up to ×4. */
        fun multiplier(combo: Int): Int = min(4, 1 + max(0, combo - 1) / 3)

        fun labelStage(wave: Int): FroggerLabelStage = when {
            wave >= hiddenFromWave -> FroggerLabelStage.HIDDEN
            wave >= memoryFromWave -> FroggerLabelStage.MEMORY
            else -> FroggerLabelStage.VISIBLE
        }

        fun rampStart(characterWpm: Double): Double = max(minWpm, characterWpm - rampStartOffset)

        fun lane(row: Int): FroggerLane? = lanes.firstOrNull { it.row == row }

        /** The shorter way round between two positions on the wrapping board. */
        fun wrappedDistance(a: Double, b: Double): Double {
            val d = abs(a - b)
            return min(d, 1 - d)
        }

        private fun wrap(x: Double): Double = x - floor(x)
    }
}
