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
        val hitsPerWave: Int = 10,
        /** The learner's character speed: where the speed ramp (#178) ends. */
        val characterWpm: Double = 20.0
    ) {
        /** Where a game's speed starts: [InvadersGame.rampStart]. */
        val startWpm: Double get() = rampStart(characterWpm)

        /** Where the ramp ends: the character speed, never under the floor. */
        val targetWpm: Double get() = max(minWpm, characterWpm)
    }

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
    /**
     * The speed invaders are sent at now (#178): starts at [Config.startWpm],
     * climbs with hits and falls back with landings.
     */
    var currentWpm: Double = config.startWpm
        private set
    /** The highest speed the ramp reached this game. */
    var bestWpm: Double = config.startWpm
        private set
    /**
     * Hits in a row at the current speed since the last change of speed, wrong
     * shot or landing (#194); [hitsPerRampStep] of them step it up.
     */
    var rampStreak = 0
        private set
    /** Settling hits still to come after the last change of speed (#194); they count for nothing. */
    var rampSettling = 0
        private set

    /** Hits each character is owed after being missed (#194); absent is 0. */
    private val missDebt = HashMap<Char, Int>()

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

    /** Every pool character's spawn weight right now (#194): 1 unless it is owed hits. */
    val spawnWeights: Map<Char, Double>
        get() = pool.associateWith { spawnWeight(missDebt[it] ?: 0) }

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
            noteMissed(e.character)
            changeSpeed(max(config.startWpm, currentWpm - rampStepDown))
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
            rampStreak = 0
            // The character missed is the one nearest the ground — the one the
            // learner was presumably naming, and the one the screen records
            // against the confusion matrix.
            lowest?.let { noteMissed(it.character) }
            return InvadersShot(null, 0, false)
        }
        invaders = invaders.filter { it.id != target.id }
        combo += 1
        bestCombo = max(bestCombo, combo)
        val points = pointsPerHit * multiplier(combo)
        score += points
        hits += 1
        noteHit(c)
        if (rampSettling > 0) {
            rampSettling -= 1
        } else {
            rampStreak += 1
            if (rampStreak >= hitsPerRampStep) {
                changeSpeed(min(config.targetWpm, currentWpm + rampStepUp))
            }
        }
        waveHits += 1
        var cleared = false
        if (waveHits >= max(1, config.hitsPerWave)) {
            wave += 1
            waveHits = 0
            cleared = true
        }
        return InvadersShot(target, points, cleared)
    }

    /**
     * Moves the ramp to [wpm]: the streak restarts either way, and a change of
     * speed opens a hold of [rampHold] settling hits.
     */
    private fun changeSpeed(wpm: Double) {
        rampStreak = 0
        if (wpm == currentWpm) return
        currentWpm = wpm
        bestWpm = max(bestWpm, wpm)
        rampSettling = rampHold
    }

    private fun noteMissed(c: Char) {
        missDebt[c] = min(maxMissDebt, (missDebt[c] ?: 0) + debtPerMiss)
    }

    private fun noteHit(c: Char) {
        val debt = missDebt[c] ?: return
        if (debt > 1) missDebt[c] = debt - 1 else missDebt.remove(c)
    }

    private fun spawn(progress: Double): Invader {
        // Prefer a character not already on the field, so "the lowest one
        // carrying it" is usually the only one; a two-character pool repeats.
        // Among those, a character the learner has been missing is weighted
        // heavier (#194): one roll, then [pick] over the weights.
        val onField = invaders.map { it.character }.toSet()
        val fresh = pool.filter { it !in onField }
        val choices = fresh.ifEmpty { pool }
        val weights = choices.map { spawnWeight(missDebt[it] ?: 0) }
        val character = choices[pick(weights, rng.nextDouble())]
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

        // Speed ramp (#178, retuned in #194). A game opens [rampStartOffset]
        // WPM under the learner's character speed — never under [minWpm], the
        // app-wide character-speed floor — and climbs towards the character
        // speed only as the learner consolidates: [hitsPerRampStep] hits in a
        // row at the current speed step it up [rampStepUp] WPM. A wrong shot
        // resets that streak and leaves the speed alone; a landing resets it
        // and steps the speed down [rampStepDown], never under the start.
        // After every change of speed the first [rampHold] hits settle — they
        // count for nothing, so a fumble while adjusting to the new speed
        // costs nothing either. Farnsworth is ignored: a single character has
        // no gaps to stretch. Pinned by fixtures/invaders-ramp.json on both
        // ports.
        const val minWpm = 15.0
        const val rampStartOffset = 10.0
        const val rampStepUp = 1.0
        const val rampStepDown = 2.0
        const val hitsPerRampStep = 6
        const val rampHold = 3

        /**
         * The speed a game starts at for a character speed: 10 WPM under it,
         * floored at 15. At or under 15 there is no ramp.
         */
        fun rampStart(characterWpm: Double): Double = max(minWpm, characterWpm - rampStartOffset)

        // Adaptive character weighting (#194). A character the learner misses
        // — an invader that lands, or a wrong shot made while it was on the
        // lowest invader — is owed [debtPerMiss] hits, up to [maxMissDebt];
        // every hit on it pays one off. Its spawn weight is
        // 1 + [missWeightBonus] × the debt, so a missed character comes round
        // more often until it has been hit twice more than missed, then is
        // back to normal. Per game; nothing persists. Pinned by
        // fixtures/invaders-ramp.json on both ports.
        const val debtPerMiss = 2
        const val maxMissDebt = 6
        const val missWeightBonus = 0.5

        /** The spawn weight of a character owed [debt] hits: 1 when owed nothing. */
        fun spawnWeight(debt: Int): Double = 1 + missWeightBonus * max(0, debt)

        /**
         * The index a roll in [0, 1) lands on when [weights] are laid end to
         * end: the first whose running total exceeds [roll] × the sum. A roll
         * at or past 1 lands on the last. Pure, so the fixture can pin the
         * decision without pinning either port's generator.
         */
        fun pick(weights: List<Double>, roll: Double): Int {
            val x = roll * weights.sum()
            var total = 0.0
            for ((i, w) in weights.withIndex()) {
                total += w
                if (x < total) return i
            }
            return max(0, weights.size - 1)
        }
    }
}

/**
 * The hear-it/type-it on-screen keyboard (#178): a QWERTY layout, so the
 * reflex being trained is the one a real keyboard rewards. The digit row leads
 * when the pool has any digit, the three letter rows are always there, and
 * anything else in the pool (punctuation, prosigns) makes a final row in pool
 * order without repeats. A key outside the pool stays in its row — the screen
 * shows it dimmed and dead — so the layout never shifts as the Koch set grows.
 * Pinned by fixtures/invaders-ramp.json; twin of the Swift `InvadersKeyboard`.
 */
object InvadersKeyboard {
    val digitRow: List<Char> = "1234567890".toList()
    val letterRows: List<List<Char>> = listOf(
        "QWERTYUIOP".toList(),
        "ASDFGHJKL".toList(),
        "ZXCVBNM".toList()
    )

    /** The rows to lay out for [pool], top to bottom. */
    fun rows(pool: List<Char>): List<List<Char>> {
        val upper = pool.map { it.uppercaseChar() }
        val standard = (digitRow + letterRows.flatten()).toSet()
        val rows = ArrayList<List<Char>>()
        if (upper.any { it in digitRow }) rows.add(digitRow)
        rows.addAll(letterRows)
        val extras = upper.filter { it !in standard }.distinct()
        if (extras.isNotEmpty()) rows.add(extras)
        return rows
    }
}
