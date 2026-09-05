package app.anothermorsetrainer.morsekit

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Morse Invaders (#170): characters descend the play field in columns; the
 * learner shoots each one by naming it — typing it after hearing it (ICR) or
 * keying it after seeing it. Pure game rules — no clock, no audio, no UI. Time
 * is modelled explicitly through [InvadersGame.advance], so the screen drives it
 * from a frame clock and the tests from arithmetic; every random choice comes
 * from the injected [Random], so a seed pins the spawn sequence.
 *
 * Translated from MorseKit/Invaders.swift; the two must stay twins.
 */

/** How the learner names an invader. */
enum class InvadersInput(val label: String, val blurb: String) {
    ICR("Hear it, type it", "Invaders come down blank. Each one is sent in Morse when it appears (tap it to hear it again); type the character you heard to shoot the lowest one carrying it."),
    KEYING("See it, key it", "Each invader shows its character. Key it on the on-screen key or a hardware key; the decoded character shoots the lowest one carrying it.")
}

/** Scales every spawn interval and fall time — the same rules, more or less breathing room. */
enum class InvadersDifficulty(val label: String, val timeScale: Double) {
    RELAXED("Relaxed", 1.35),
    NORMAL("Normal", 1.0),
    FAST("Fast", 0.75)
}

/** Which characters the invaders carry. */
enum class InvadersCharacterSet(val label: String) {
    ACTIVE("My active characters"),
    FULL("Full alphabet and digits")
}

/**
 * One invader on the field. [progress] runs 0 at the top to 1 at the ground;
 * [fallTime] is how many seconds that takes, fixed at spawn so a wave change
 * mid-flight does not jolt the ones already falling.
 */
data class Invader(
    val id: Int,
    val character: Char,
    val column: Int,
    val progress: Double,
    val fallTime: Double
)

/** What one call to [InvadersGame.advance] did, in order. */
sealed class InvadersEvent {
    data class Spawned(val invader: Invader) : InvadersEvent()
    data class Escaped(val invader: Invader) : InvadersEvent()
    object GameOver : InvadersEvent()
}

/**
 * The outcome of one shot. [invader] is the one hit (null on a miss), [points]
 * what the hit scored, and [waveCleared] whether that hit finished the wave.
 */
data class InvadersShot(val invader: Invader?, val points: Int, val waveCleared: Boolean) {
    val isHit: Boolean get() = invader != null
}

class InvadersGame(
    val config: Config,
    private val rng: Random = Random.Default
) {
    data class Config(
        /** The characters invaders carry; empty falls back to the first two Koch characters. */
        val characters: List<Char>,
        val difficulty: InvadersDifficulty = InvadersDifficulty.NORMAL,
        val columns: Int = 5,
        val lives: Int = 3,
        /** Hits that clear a wave and tighten the timings. */
        val hitsPerWave: Int = 10
    )

    private val pool: List<Char> = config.characters.map { it.uppercaseChar() }.distinct()
        .ifEmpty { MorseCode.kochOrder.take(2) }

    var invaders: List<Invader> = emptyList()
        private set
    var score = 0
        private set
    var wave = 1
        private set
    var lives = max(1, config.lives)
        private set
    /** Consecutive hits since the last miss or escape. */
    var combo = 0
        private set
    var bestCombo = 0
        private set
    var hits = 0
        private set
    /** Wrong shots plus invaders that reached the ground. */
    var misses = 0
        private set
    /** Game time in seconds, the sum of every [advance]. */
    var elapsed = 0.0
        private set
    var isOver = false
        private set

    private var waveHits = 0
    private var sinceSpawn = 0.0
    private var nextId = 1
    private var lastColumn = -1

    /** Seconds between spawns at the current wave. */
    val spawnInterval: Double get() = spawnInterval(wave, config.difficulty)

    /** Seconds a fresh invader takes to reach the ground at the current wave. */
    val fallTime: Double get() = fallTime(wave, config.difficulty)

    /** Hits over every shot and escape, 0…1. */
    val accuracy: Double get() = if (hits + misses == 0) 0.0 else hits.toDouble() / (hits + misses)

    /** The invader nearest the ground, if any. */
    val lowest: Invader? get() = invaders.maxByOrNull { it.progress }

    /** The multiplier the next hit earns. */
    val multiplier: Int get() = multiplier(combo + 1)

    /**
     * Move time forward by [seconds]: invaders fall, any that reach the ground
     * cost a life, and the spawn clock releases new ones. Returns what happened,
     * in order. A finished game ignores time.
     */
    fun advance(seconds: Double): List<InvadersEvent> {
        if (isOver || seconds <= 0) return emptyList()
        val events = ArrayList<InvadersEvent>()
        elapsed += seconds

        val moved = invaders.map { it.copy(progress = it.progress + seconds / it.fallTime) }
        val escaped = moved.filter { it.progress >= 1.0 }
        invaders = moved.filter { it.progress < 1.0 }
        for (e in escaped) {
            lives = max(0, lives - 1)
            misses += 1
            combo = 0
            events.add(InvadersEvent.Escaped(e.copy(progress = 1.0)))
        }
        if (lives == 0) {
            isOver = true
            invaders = emptyList()
            events.add(InvadersEvent.GameOver)
            return events
        }

        sinceSpawn += seconds
        while (sinceSpawn >= spawnInterval) {
            sinceSpawn -= spawnInterval
            // Born partway down when the step overshot its spawn time, so a
            // long frame does not gift the invader extra hang time.
            val invader = spawn(progress = sinceSpawn / fallTime)
            events.add(InvadersEvent.Spawned(invader))
        }
        return events
    }

    /**
     * Name a character. Hits the lowest invader carrying it and scores
     * `pointsPerHit` times the combo multiplier; with none on the field it is a
     * miss that breaks the combo. A finished game ignores shots.
     */
    fun shoot(character: Char): InvadersShot {
        if (isOver) return InvadersShot(null, 0, false)
        val c = character.uppercaseChar()
        val target = invaders.filter { it.character == c }.maxByOrNull { it.progress }
        if (target == null) {
            combo = 0
            misses += 1
            return InvadersShot(null, 0, false)
        }
        invaders = invaders.filter { it.id != target.id }
        combo += 1
        bestCombo = max(bestCombo, combo)
        val points = pointsPerHit * multiplier(combo)
        score += points
        hits += 1
        waveHits += 1
        var cleared = false
        if (waveHits >= max(1, config.hitsPerWave)) {
            wave += 1
            waveHits = 0
            cleared = true
        }
        return InvadersShot(target, points, cleared)
    }

    private fun spawn(progress: Double): Invader {
        // Prefer a character not already on the field, so "the lowest one
        // carrying it" is usually the only one; a two-character pool repeats.
        val onField = invaders.map { it.character }.toSet()
        val fresh = pool.filter { it !in onField }
        val choices = fresh.ifEmpty { pool }
        val character = choices[rng.nextInt(choices.size)]
        val columns = max(1, config.columns)
        val column = if (columns == 1) 0 else {
            val open = (0 until columns).filter { it != lastColumn }
            open[rng.nextInt(open.size)]
        }
        lastColumn = column
        val invader = Invader(nextId, character, column, progress, fallTime)
        nextId += 1
        invaders = invaders + invader
        return invader
    }

    companion object {
        const val pointsPerHit = 100
        const val baseSpawnInterval = 2.4
        const val baseFallTime = 8.0
        const val minSpawnInterval = 0.9
        const val minFallTime = 3.0

        /** Spawn interval for a wave: 12% tighter per wave, floored, then scaled by difficulty. */
        fun spawnInterval(wave: Int, difficulty: InvadersDifficulty): Double =
            max(minSpawnInterval, baseSpawnInterval * 0.88.pow(max(0, wave - 1))) * difficulty.timeScale

        /** Fall time for a wave: 10% faster per wave, floored, then scaled by difficulty. */
        fun fallTime(wave: Int, difficulty: InvadersDifficulty): Double =
            max(minFallTime, baseFallTime * 0.9.pow(max(0, wave - 1))) * difficulty.timeScale

        /** Combo multiplier: ×1 for the first three hits in a row, ×2 for the next three, up to ×4. */
        fun multiplier(combo: Int): Int = min(4, 1 + max(0, combo - 1) / 3)
    }
}
