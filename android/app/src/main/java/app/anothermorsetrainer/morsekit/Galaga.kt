package app.anothermorsetrainer.morsekit

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * CW Galaga (#187): enemies fly in along curved entry paths and settle into a
 * formation at the top of the field; from there they dive at the player one
 * at a time and climb back if not shot. The learner shoots an enemy by naming
 * the character it carries — typing it after hearing it (copy) or keying it
 * after seeing it (send) — and consecutive hits build a combo multiplier.
 * Pure game rules — no clock, no audio, no UI. Time enters through
 * [GalagaGame.advance], so the screen drives it from a frame clock and the
 * tests from arithmetic; every random choice comes from the injected [Random],
 * so a seed pins the sequence. Paths are unit-space Béziers in [GalagaPath],
 * so both screens draw the same swoop from the same progress.
 *
 * Pinned by fixtures/galaga.json on both ports. Translated from
 * MorseKit/Galaga.swift; the two must stay twins. The input, difficulty and
 * character-set choices are [InvadersInput], [InvadersDifficulty] and
 * [InvadersCharacterSet] (Invaders.kt), shared across the arcade games.
 */

/** Where an enemy is in its flight. */
enum class GalagaEnemyState {
    /** Flying its entry path; progress 0 at the field edge, 1 at its slot. */
    ENTERING,
    /** Holding its formation slot. */
    FORMED,
    /** Diving at the player; progress 0 at the slot, 1 at the bottom. */
    DIVING,
    /** Climbing back to its slot after a dive that landed; progress 0 at the bottom, 1 at the slot. */
    RETURNING
}

/** One enemy on the field. [row] 0 is the top row; [fromLeft] is the side its entry path starts from. */
data class GalagaEnemy(
    val id: Int,
    val character: Char,
    val row: Int,
    val column: Int,
    val fromLeft: Boolean,
    val state: GalagaEnemyState,
    /** 0…1 along the current leg (entry, dive or return); meaningless when formed. */
    val progress: Double,
    /**
     * Seconds the current leg takes, fixed when the leg starts so a wave
     * change mid-flight does not jolt the ones already flying.
     */
    val legTime: Double
) {
    /** Off its slot: diving or returning. */
    val isOffSlot: Boolean get() = state == GalagaEnemyState.DIVING || state == GalagaEnemyState.RETURNING
}

/** A wave's formation grid. */
data class GalagaFormation(val rows: Int, val columns: Int) {
    val size: Int get() = rows * columns
}

/** What one call to [GalagaGame.advance] did, in order. */
sealed class GalagaEvent {
    /** Released onto its entry path: the moment to send its character. */
    data class Entered(val enemy: GalagaEnemy) : GalagaEvent()
    /** Peeled off its slot into a dive: sent again, since it is the threat now. */
    data class Dived(val enemy: GalagaEnemy) : GalagaEvent()
    /** The dive reached the bottom: a life lost; the enemy is returning. */
    data class Landed(val enemy: GalagaEnemy) : GalagaEvent()
    object GameOver : GalagaEvent()
}

/**
 * The outcome of one shot. [enemy] is the one hit (null on a miss), [points]
 * what the hit scored (the wave bonus not included), and [waveCleared]
 * whether that hit finished the wave.
 */
data class GalagaShot(val enemy: GalagaEnemy?, val points: Int, val waveCleared: Boolean) {
    val isHit: Boolean get() = enemy != null
}

class GalagaGame(
    val config: Config,
    private val rng: Random = Random.Default
) {
    data class Config(
        /** The characters enemies carry; empty falls back to the first two Koch characters. */
        val characters: List<Char>,
        val difficulty: InvadersDifficulty = InvadersDifficulty.NORMAL,
        val lives: Int = 3,
        /** The learner's character speed: where the speed ramp ends. */
        val characterWpm: Double = 20.0
    ) {
        /** Where a game's speed starts: [GalagaGame.rampStart]. */
        val startWpm: Double get() = rampStart(characterWpm)

        /** Where the ramp ends: the character speed, never under the floor. */
        val targetWpm: Double get() = max(minWpm, characterWpm)
    }

    private val pool: List<Char> = config.characters.map { it.uppercaseChar() }.distinct()
        .ifEmpty { MorseCode.kochOrder.take(2) }

    var enemies: List<GalagaEnemy> = emptyList()
        private set
    var score = 0
        private set
    var wave = 1
        private set
    var lives = max(1, config.lives)
        private set
    /** Consecutive hits since the last miss or landed dive. */
    var combo = 0
        private set
    var bestCombo = 0
        private set
    var hits = 0
        private set
    /** Wrong shots plus dives that landed. */
    var misses = 0
        private set
    /** Game time in seconds, the sum of every [advance]. */
    var elapsed = 0.0
        private set
    var isOver = false
        private set
    /**
     * The speed enemies are sent at now: starts at [Config.startWpm], climbs
     * with hits and falls back with landed dives.
     */
    var currentWpm: Double = config.startWpm
        private set
    /** The highest speed the ramp reached this game. */
    var bestWpm: Double = config.startWpm
        private set
    /** How many of this wave's formation have been released so far. */
    var released = 0
        private set

    // The first entry comes one interval in, as every later one does.
    private var sinceEntry = 0.0
    private var sinceDive = 0.0
    private var nextId = 1

    /** This wave's formation grid. */
    val formation: GalagaFormation get() = formation(wave)

    val entryInterval: Double get() = entryInterval(wave, config.difficulty)
    val entryTime: Double get() = entryTime(wave, config.difficulty)
    val diveInterval: Double get() = diveInterval(wave, config.difficulty)
    val diveTime: Double get() = diveTime(wave, config.difficulty)
    val returnTime: Double get() = returnTime(wave, config.difficulty)
    val maxDivers: Int get() = maxDivers(wave)

    /** Hits over every shot and landed dive, 0…1. */
    val accuracy: Double get() = if (hits + misses == 0) 0.0 else hits.toDouble() / (hits + misses)

    /** The multiplier the next hit earns. */
    val multiplier: Int get() = multiplier(combo + 1)

    /** The enemy most in need of shooting, if any; ties go to the earlier release. */
    val mostThreatening: GalagaEnemy? get() = enemies.maxByOrNull { threat(it) }

    /**
     * Move time forward by [seconds]: enemies fly their legs, a dive that
     * reaches the bottom costs a life, the entry clock releases the next of
     * the formation and the dive clock sends one down. Returns what happened,
     * in order. A finished game ignores time.
     */
    fun advance(seconds: Double): List<GalagaEvent> {
        if (isOver || seconds <= 0) return emptyList()
        val events = ArrayList<GalagaEvent>()
        elapsed += seconds

        enemies = enemies.map { e ->
            if (e.state == GalagaEnemyState.FORMED) return@map e
            val moved = e.copy(progress = e.progress + seconds / e.legTime)
            if (moved.progress < 1.0) return@map moved
            when (moved.state) {
                GalagaEnemyState.ENTERING, GalagaEnemyState.RETURNING ->
                    moved.copy(state = GalagaEnemyState.FORMED, progress = 0.0)
                GalagaEnemyState.DIVING -> {
                    lives = max(0, lives - 1)
                    misses += 1
                    combo = 0
                    currentWpm = max(config.startWpm, currentWpm - rampStep)
                    events.add(GalagaEvent.Landed(moved.copy(progress = 1.0)))
                    moved.copy(state = GalagaEnemyState.RETURNING, progress = 0.0, legTime = returnTime)
                }
                GalagaEnemyState.FORMED -> moved
            }
        }
        if (lives == 0) {
            isOver = true
            enemies = emptyList()
            events.add(GalagaEvent.GameOver)
            return events
        }

        if (released < formation.size) {
            sinceEntry += seconds
            while (sinceEntry >= entryInterval && released < formation.size) {
                sinceEntry -= entryInterval
                // Born partway along when the step overshot its release time,
                // so a long frame does not gift it extra hang time.
                events.add(GalagaEvent.Entered(release(progress = sinceEntry / entryTime)))
            }
        }

        // The dive clock runs only while a dive is possible, so the first dive
        // comes one interval after the first enemy settles.
        val offSlot = enemies.count { it.isOffSlot }
        val formed = enemies.filter { it.state == GalagaEnemyState.FORMED }
        if (formed.isNotEmpty() && offSlot < maxDivers) {
            sinceDive += seconds
            if (sinceDive >= diveInterval) {
                sinceDive = 0.0
                val pick = formed[rng.nextInt(formed.size)]
                val diver = pick.copy(state = GalagaEnemyState.DIVING, progress = 0.0, legTime = diveTime)
                enemies = enemies.map { if (it.id == pick.id) diver else it }
                events.add(GalagaEvent.Dived(diver))
            }
        }
        return events
    }

    /**
     * Name a character. Hits the most threatening enemy carrying it and scores
     * [pointsPerHit] (plus [diveBonus] for a diver) times the combo
     * multiplier; with none on the field it is a miss that breaks the combo.
     * The last enemy of a fully released formation clears the wave and adds
     * [waveBonus]. A finished game ignores shots.
     */
    fun shoot(character: Char): GalagaShot {
        if (isOver) return GalagaShot(null, 0, false)
        val c = character.uppercaseChar()
        val target = enemies.filter { it.character == c }.maxByOrNull { threat(it) }
        if (target == null) {
            combo = 0
            misses += 1
            return GalagaShot(null, 0, false)
        }
        enemies = enemies.filter { it.id != target.id }
        combo += 1
        bestCombo = max(bestCombo, combo)
        val base = pointsPerHit + (if (target.state == GalagaEnemyState.DIVING) diveBonus else 0)
        val points = base * multiplier(combo)
        score += points
        hits += 1
        if (hits % hitsPerRampStep == 0) {
            currentWpm = min(config.targetWpm, currentWpm + rampStep)
            bestWpm = max(bestWpm, currentWpm)
        }
        var cleared = false
        if (enemies.isEmpty() && released >= formation.size) {
            score += waveBonus
            wave += 1
            released = 0
            sinceEntry = -waveGap
            sinceDive = 0.0
            cleared = true
        }
        return GalagaShot(target, points, cleared)
    }

    private fun release(progress: Double): GalagaEnemy {
        // Prefer a character not already on the field, so "the most
        // threatening one carrying it" is usually the only one; a
        // two-character pool repeats.
        val onField = enemies.map { it.character }.toSet()
        val fresh = pool.filter { it !in onField }
        val choices = fresh.ifEmpty { pool }
        val character = choices[rng.nextInt(choices.size)]
        val slot = released
        val enemy = GalagaEnemy(
            id = nextId, character = character,
            row = slot / formation.columns, column = slot % formation.columns,
            fromLeft = slot % 2 == 0,
            state = GalagaEnemyState.ENTERING, progress = progress, legTime = entryTime
        )
        nextId += 1
        released += 1
        enemies = enemies + enemy
        return enemy
    }

    companion object {
        // Scoring.
        const val pointsPerHit = 100
        /** Added to [pointsPerHit] when the enemy hit was diving. */
        const val diveBonus = 50
        /** Added to the score when the last enemy of a wave goes down. */
        const val waveBonus = 500
        /** Consecutive hits per multiplier step. */
        const val comboStep = 3
        const val maxMultiplier = 8

        /** Combo multiplier: ×1 for the first three hits in a row, ×2 for the next three, and so on up to ×8. */
        fun multiplier(combo: Int): Int = min(maxMultiplier, 1 + max(0, combo - 1) / comboStep)

        // Formation. Grows with the wave to a 4×6 block.
        const val maxRows = 4
        const val maxColumns = 6

        fun formation(wave: Int): GalagaFormation {
            val w = max(1, wave)
            return GalagaFormation(rows = min(maxRows, 1 + (w + 1) / 2), columns = min(maxColumns, 3 + w))
        }

        /** How many enemies may be off their slot (diving or returning) at once. */
        fun maxDivers(wave: Int): Int = min(3, 1 + (max(1, wave) - 1) / 2)

        // Timings, in seconds on Normal: each is base × decay^(wave − 1),
        // floored, then scaled by the difficulty. The wave gap is not scaled.
        const val baseEntryInterval = 1.4
        const val entryIntervalDecay = 0.92
        const val minEntryInterval = 0.5
        const val baseEntryTime = 2.4
        const val entryTimeDecay = 0.94
        const val minEntryTime = 1.2
        const val baseDiveInterval = 3.0
        const val diveIntervalDecay = 0.90
        const val minDiveInterval = 1.0
        const val baseDiveTime = 4.0
        const val diveTimeDecay = 0.92
        const val minDiveTime = 1.8
        /** The return leg takes this fraction of the dive. */
        const val returnFactor = 0.5
        /** Seconds between a wave's last hit and the next wave's first entry. */
        const val waveGap = 1.5

        private fun decayed(base: Double, decay: Double, floor: Double, wave: Int, difficulty: InvadersDifficulty): Double =
            max(floor, base * decay.pow(max(0, wave - 1))) * difficulty.timeScale

        /** Seconds between entries. */
        fun entryInterval(wave: Int, difficulty: InvadersDifficulty): Double =
            decayed(baseEntryInterval, entryIntervalDecay, minEntryInterval, wave, difficulty)

        /** Seconds an entry path takes. */
        fun entryTime(wave: Int, difficulty: InvadersDifficulty): Double =
            decayed(baseEntryTime, entryTimeDecay, minEntryTime, wave, difficulty)

        /** Seconds between dives while the dive clock runs. */
        fun diveInterval(wave: Int, difficulty: InvadersDifficulty): Double =
            decayed(baseDiveInterval, diveIntervalDecay, minDiveInterval, wave, difficulty)

        /** Seconds a dive takes from the slot to the bottom. */
        fun diveTime(wave: Int, difficulty: InvadersDifficulty): Double =
            decayed(baseDiveTime, diveTimeDecay, minDiveTime, wave, difficulty)

        /** Seconds the climb back to the slot takes after a dive lands. */
        fun returnTime(wave: Int, difficulty: InvadersDifficulty): Double =
            diveTime(wave, difficulty) * returnFactor

        // Speed ramp — gentler than Invaders' (#194): a game opens
        // [rampStartOffset] WPM under the learner's character speed, never
        // under [minWpm], and climbs [rampStep] WPM every [hitsPerRampStep]
        // hits in total up to the character speed; a landed dive steps it
        // back, never under the start. Wrong shots leave it alone. Farnsworth
        // is ignored: a single character has no gaps to stretch.
        const val minWpm = 15.0
        const val rampStartOffset = 8.0
        const val rampStep = 1.0
        const val hitsPerRampStep = 6

        /** The speed a game starts at for a character speed: 8 WPM under it, floored at 15. */
        fun rampStart(characterWpm: Double): Double = max(minWpm, characterWpm - rampStartOffset)

        /**
         * How urgently an enemy needs shooting: a diver (the further down the
         * better), then a returning one (the lower the better), then a formed
         * one on the lowest row, then one still entering.
         */
        fun threat(enemy: GalagaEnemy): Double = when (enemy.state) {
            GalagaEnemyState.DIVING -> 3 + enemy.progress
            GalagaEnemyState.RETURNING -> 2 + (1 - enemy.progress)
            GalagaEnemyState.FORMED -> 1 + enemy.row / 100.0
            GalagaEnemyState.ENTERING -> enemy.progress
        }
    }
}

/**
 * The flight paths, in unit field coordinates: x 0…1 left to right, y 0 at
 * the top and 1 at the bottom where the player sits. Both screens scale these
 * to their canvas, so the swoop is the same on either app. Pinned by
 * fixtures/galaga.json; twin of the Swift `GalagaPath`.
 */
object GalagaPath {
    data class Point(val x: Double, val y: Double)

    /** Where the top row sits, and the spacing between rows. */
    const val formationTop = 0.10
    const val rowPitch = 0.11

    /** The centre of a formation slot. */
    fun slot(row: Int, column: Int, columns: Int): Point =
        Point((column + 0.5) / max(1, columns), formationTop + row * rowPitch)

    /**
     * The entry swoop: in from the side at mid-height, down towards the
     * bottom, up past the far top corner and into the slot.
     */
    fun entry(t: Double, fromLeft: Boolean, slot: Point): Point = cubic(
        t,
        Point(if (fromLeft) -0.08 else 1.08, 0.5),
        Point(if (fromLeft) 0.25 else 0.75, 1.05),
        Point(if (fromLeft) 1.0 else 0.0, 0.0),
        slot
    )

    /** The dive: down from the slot, drifting across to land on the mirrored x. */
    fun dive(t: Double, slot: Point): Point = cubic(
        t,
        slot,
        Point(slot.x, slot.y + 0.4),
        Point(1 - slot.x, 0.6),
        Point(1 - slot.x, 1.0)
    )

    /** Where an enemy is right now, from its state and progress. */
    fun position(enemy: GalagaEnemy, columns: Int): Point {
        val s = slot(enemy.row, enemy.column, columns)
        val t = enemy.progress.coerceIn(0.0, 1.0)
        return when (enemy.state) {
            GalagaEnemyState.ENTERING -> entry(t, enemy.fromLeft, s)
            GalagaEnemyState.FORMED -> s
            GalagaEnemyState.DIVING -> dive(t, s)
            GalagaEnemyState.RETURNING -> dive(1 - t, s)
        }
    }

    private fun cubic(t: Double, p0: Point, p1: Point, p2: Point, p3: Point): Point {
        val u = 1 - t
        val a = u * u * u
        val b = 3 * u * u * t
        val c = 3 * u * t * t
        val d = t * t * t
        return Point(a * p0.x + b * p1.x + c * p2.x + d * p3.x, a * p0.y + b * p1.y + c * p2.y + d * p3.y)
    }
}
