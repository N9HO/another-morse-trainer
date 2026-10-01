package app.anothermorsetrainer.morsekit

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * CW Asteroids (#189, part of #170): asteroids drift in from the rim of the
 * field toward the ship at the centre, each carrying a label — a character in
 * the early waves, a short word or callsign later — and the learner destroys
 * one by sending its label (see it, send it) or by tapping the one whose label
 * the game just sent (hear it, tap it). Pure game rules — no clock, no audio,
 * no UI. Time enters through [AsteroidsGame.advance], randomness through the
 * injected [Random]; positions are normalised 0…1 so the screen only draws.
 *
 * Translated from MorseKit/Asteroids.swift; the two must stay twins. Pinned by
 * fixtures/asteroids.json on both ports.
 */

/** How the learner destroys an asteroid. */
enum class AsteroidsInput(val label: String, val blurb: String) {
    SEND("See it, send it", "Each asteroid shows its label. Key it on the on-screen key or a hardware key; the decoded characters destroy the asteroid whose label they spell. A word splits into its characters when hit."),
    COPY("Hear it, tap it", "The game sends one asteroid's label in Morse (tap the ship to hear it again). Tap the asteroid carrying what you heard; a wrong asteroid is a miss.")
}

/**
 * One asteroid on the field. [angle] is where it sits around the ship, in
 * radians; [progress] runs 0 at the rim to 1 at the ship and takes
 * [approachTime] seconds, fixed at spawn so a wave change mid-flight does not
 * jolt the ones already on their way. [isFragment] marks a piece split off a
 * word that was hit.
 */
data class Asteroid(
    val id: Int,
    val label: String,
    val angle: Double,
    val progress: Double,
    val approachTime: Double,
    val isFragment: Boolean = false
) {
    /** Normalised position, 0…1 on both axes, the ship at (0.5, 0.5). */
    val x: Double get() = 0.5 + 0.5 * (1 - progress) * cos(angle)
    val y: Double get() = 0.5 + 0.5 * (1 - progress) * sin(angle)

    /** The direction it travels: straight at the ship. */
    val heading: Double get() = angle + PI
}

/** What one call to [AsteroidsGame.advance] did, in order. */
sealed class AsteroidsEvent {
    data class Spawned(val asteroid: Asteroid) : AsteroidsEvent()
    /** An asteroid reached the ship. */
    data class Struck(val asteroid: Asteroid) : AsteroidsEvent()
    /** Copy mode: this asteroid is armed; the screen sends its label. */
    data class Cued(val asteroid: Asteroid) : AsteroidsEvent()
    object GameOver : AsteroidsEvent()
}

enum class AsteroidsOutcome { HIT, PARTIAL, MISS, IGNORED }

/**
 * The outcome of one send or tap. [asteroid] is the one destroyed on a hit
 * and [fragments] what it split into; on a miss [expected] is the character
 * that should have come and [chosen] the one that did. [cued] is the next
 * armed asteroid when the hit freed the field (copy mode).
 */
data class AsteroidsShot(
    val outcome: AsteroidsOutcome,
    val asteroid: Asteroid? = null,
    val points: Int = 0,
    val waveCleared: Boolean = false,
    val fragments: List<Asteroid> = emptyList(),
    val expected: Char? = null,
    val chosen: Char? = null,
    val cued: Asteroid? = null
) {
    val isHit: Boolean get() = outcome == AsteroidsOutcome.HIT
}

class AsteroidsGame(
    val config: Config,
    private val rng: Random = Random.Default
) {
    data class Config(
        /** The characters single asteroids carry; empty falls back to the first two Koch characters. */
        val characters: List<Char>,
        /** Candidate word labels, most useful first; filtered to the character set. */
        val words: List<String> = emptyList(),
        val input: AsteroidsInput = AsteroidsInput.SEND,
        val difficulty: InvadersDifficulty = InvadersDifficulty.NORMAL,
        val lives: Int = defaultLives,
        /** Hits that clear a wave and tighten the timings. */
        val hitsPerWave: Int = defaultHitsPerWave,
        /** The learner's character speed: where the copy-mode speed ramp ends. */
        val characterWpm: Double = 20.0,
        /** Whether word spawns may be generated callsigns. */
        val callsigns: Boolean = true
    ) {
        val startWpm: Double get() = rampStart(characterWpm)
        val targetWpm: Double get() = max(minWpm, characterWpm)
    }

    private val pool: List<Char> = config.characters.map { it.uppercaseChar() }.distinct()
        .ifEmpty { MorseCode.kochOrder.take(2) }

    /** The words this game can spawn, after [wordPool]'s filter. */
    val wordPool: List<String> = wordPool(config.words, pool)

    var asteroids: List<Asteroid> = emptyList()
        private set
    var score = 0
        private set
    var wave = 1
        private set
    var lives = max(1, config.lives)
        private set
    /** Consecutive hits since the last miss or strike. */
    var combo = 0
        private set
    var bestCombo = 0
        private set
    var hits = 0
        private set
    /** Misses plus strikes on the ship. */
    var misses = 0
        private set
    /** Game time in seconds, the sum of every [advance]. */
    var elapsed = 0.0
        private set
    var isOver = false
        private set
    /** The speed labels are sent at now (copy mode). */
    var currentWpm: Double = config.startWpm
        private set
    var bestWpm: Double = config.startWpm
        private set
    /** Send mode: the characters sent since the buffer was last resolved. */
    var sendBuffer = ""
        private set
    /** Copy mode: the asteroid whose label was sent, if any. */
    var armedId: Int? = null
        private set

    private var waveHits = 0
    private var sinceSpawn = 0.0
    private var sinceKey = 0.0
    private var nextId = 1
    private var lastSector = -1

    val spawnInterval: Double get() = spawnInterval(wave, config.difficulty)
    val approachTime: Double get() = approachTime(wave, config.difficulty)

    /** Hits over every hit, miss and strike, 0…1. */
    val accuracy: Double get() = if (hits + misses == 0) 0.0 else hits.toDouble() / (hits + misses)

    /** The asteroid nearest the ship: highest progress, then lowest id. */
    val nearest: Asteroid? get() = nearestOf(asteroids)

    /** Copy mode: the armed asteroid, if it is still on the field. */
    val armed: Asteroid? get() = asteroids.firstOrNull { it.id == armedId }

    /** The multiplier the next hit earns. */
    val multiplier: Int get() = multiplier(combo + 1)

    /**
     * Move time forward by [seconds]: asteroids close in, any that reach the
     * ship cost a life, a stale partial send is dropped, the spawn clock
     * releases new ones, and copy mode arms the next target. Returns what
     * happened, in order. A finished game ignores time.
     */
    fun advance(seconds: Double): List<AsteroidsEvent> {
        if (isOver || seconds <= 0) return emptyList()
        val events = ArrayList<AsteroidsEvent>()
        elapsed += seconds

        val moved = asteroids.map { it.copy(progress = it.progress + seconds / it.approachTime) }
        val struck = moved.filter { it.progress >= 1.0 }
        asteroids = moved.filter { it.progress < 1.0 }
        for (a in struck) {
            lives = max(0, lives - 1)
            misses += 1
            combo = 0
            currentWpm = max(config.startWpm, currentWpm - rampStep)
            if (a.id == armedId) armedId = null
            events.add(AsteroidsEvent.Struck(a.copy(progress = 1.0)))
        }
        if (lives == 0) {
            isOver = true
            asteroids = emptyList()
            armedId = null
            sendBuffer = ""
            events.add(AsteroidsEvent.GameOver)
            return events
        }

        sinceKey += seconds
        if (sendBuffer.isNotEmpty() && sinceKey >= sendBufferTimeout) sendBuffer = ""

        sinceSpawn += seconds
        while (sinceSpawn >= spawnInterval) {
            sinceSpawn -= spawnInterval
            // A full field skips the spawn; the clock still resets. Born
            // partway in when the step overshot its spawn time.
            if (asteroids.size < maxOnField) {
                events.add(AsteroidsEvent.Spawned(spawn(progress = sinceSpawn / approachTime)))
            }
        }
        recue()?.let { events.add(AsteroidsEvent.Cued(it)) }
        return events
    }

    /**
     * Send mode: one decoded character. The buffer grows until it spells a
     * label (a hit on the nearest asteroid carrying it), can still start one
     * (a partial), or cannot (a miss, confused with the character that should
     * have come next). A finished game ignores it.
     */
    fun send(character: Char): AsteroidsShot {
        if (isOver) return AsteroidsShot(AsteroidsOutcome.IGNORED)
        val c = character.uppercaseChar()
        if (c == ' ') return AsteroidsShot(AsteroidsOutcome.IGNORED)
        val previous = sendBuffer
        sendBuffer += c
        sinceKey = 0.0
        val target = nearestOf(asteroids.filter { it.label == sendBuffer })
        if (target != null) {
            sendBuffer = ""
            return destroy(target)
        }
        if (asteroids.any { it.label.startsWith(sendBuffer) }) {
            return AsteroidsShot(AsteroidsOutcome.PARTIAL)
        }
        // The nearest asteroid the buffer was still spelling names the
        // character that was expected in place of this one.
        val candidate = nearestOf(asteroids.filter { it.label.startsWith(previous) })
        val expected = if (candidate != null && previous.length < candidate.label.length) candidate.label[previous.length] else null
        sendBuffer = ""
        return miss(expected, c)
    }

    /**
     * Copy mode: a tap on the asteroid with [id]. A hit if it carries the armed
     * label, a miss if it carries another, ignored if nothing is there or
     * nothing is armed.
     */
    fun tap(id: Int): AsteroidsShot {
        if (isOver) return AsteroidsShot(AsteroidsOutcome.IGNORED)
        val tapped = asteroids.firstOrNull { it.id == id } ?: return AsteroidsShot(AsteroidsOutcome.IGNORED)
        val target = armed ?: return AsteroidsShot(AsteroidsOutcome.IGNORED)
        if (tapped.label == target.label) {
            armedId = null
            return destroy(tapped)
        }
        return miss(target.label.firstOrNull(), tapped.label.firstOrNull())
    }

    /**
     * Put an asteroid on the field directly — for the tests and previews; the
     * game's own spawns go through the clock. Copy mode arms it if nothing
     * else is armed.
     */
    fun place(label: String, angle: Double, progress: Double): Asteroid {
        val a = Asteroid(nextId, label.uppercase(), angle, progress, approachTime)
        nextId += 1
        asteroids = asteroids + a
        recue()
        return a
    }

    private fun destroy(target: Asteroid): AsteroidsShot {
        asteroids = asteroids.filter { it.id != target.id }
        combo += 1
        bestCombo = max(bestCombo, combo)
        val points = pointsPerCharacter * target.label.length * multiplier(combo)
        score += points
        hits += 1
        if (hits % hitsPerRampStep == 0) {
            currentWpm = min(config.targetWpm, currentWpm + rampStep)
            bestWpm = max(bestWpm, currentWpm)
        }
        waveHits += 1
        var cleared = false
        if (waveHits >= max(1, config.hitsPerWave)) {
            wave += 1
            waveHits = 0
            cleared = true
        }
        val fragments = ArrayList<Asteroid>()
        if (target.label.length > 1) {
            val n = target.label.length.toDouble()
            target.label.forEachIndexed { k, ch ->
                val angle = target.angle + (k - (n - 1) / 2) * splitSpread
                fragments.add(Asteroid(nextId, ch.toString(), angle, target.progress, approachTime, isFragment = true))
                nextId += 1
            }
            asteroids = asteroids + fragments
        }
        return AsteroidsShot(AsteroidsOutcome.HIT, target, points, cleared, fragments, cued = recue())
    }

    private fun miss(expected: Char?, chosen: Char?): AsteroidsShot {
        combo = 0
        misses += 1
        return AsteroidsShot(AsteroidsOutcome.MISS, expected = expected, chosen = chosen)
    }

    /** Copy mode: arm a random asteroid when none is armed. Returns it. */
    private fun recue(): Asteroid? {
        if (config.input != AsteroidsInput.COPY || armed != null || asteroids.isEmpty()) return null
        val a = asteroids[rng.nextInt(asteroids.size)]
        armedId = a.id
        return a
    }

    private fun spawn(progress: Double): Asteroid {
        val onField = asteroids.map { it.label }.toSet()
        var label: String? = null
        val chance = wordChance(wave)
        if (chance > 0 && rng.nextDouble() < chance) {
            if (config.callsigns && rng.nextInt(3) == 0) label = callsign()
            if (label == null && wordPool.isNotEmpty()) {
                val fresh = wordPool.filter { it !in onField }
                val choices = fresh.ifEmpty { wordPool }
                label = choices[rng.nextInt(choices.size)]
            }
        }
        if (label == null) {
            // Prefer a character not already on the field, so a label is
            // usually carried by one asteroid; a two-character set repeats.
            val fresh = pool.filter { it.toString() !in onField }
            val choices = fresh.ifEmpty { pool }
            label = choices[rng.nextInt(choices.size)].toString()
        }
        val open = (0 until sectors).filter { it != lastSector }
        val sector = open[rng.nextInt(open.size)]
        lastSector = sector
        val angle = (sector + 0.5) * 2 * PI / sectors
        val a = Asteroid(nextId, label ?: pool[0].toString(), angle, progress, approachTime)
        nextId += 1
        asteroids = asteroids + a
        return a
    }

    /** A generated callsign the character set can spell, or null after a few tries. */
    private fun callsign(): String? {
        val set = pool.toSet()
        repeat(8) {
            val call = CallsignGenerator.generate(CallsignFormat.commonDefaults, usOnly = false, rng = rng)
            if (call.length <= maxWordLength && call.all { it in set }) return call
        }
        return null
    }

    companion object {
        const val pointsPerCharacter = 100
        const val defaultLives = 3
        const val defaultHitsPerWave = 8
        const val maxOnField = 5
        const val sectors = 12
        const val baseSpawnInterval = 3.0
        const val minSpawnInterval = 1.2
        const val spawnTightening = 0.9
        const val baseApproachTime = 12.0
        const val minApproachTime = 5.0
        const val approachTightening = 0.92
        /** Radians between neighbouring fragments of a split word. */
        const val splitSpread = 0.18
        const val wordWaveStart = 3
        const val wordChancePerWave = 0.15
        const val maxWordChance = 0.5
        const val wordRank = 300
        const val minWordLength = 2
        const val maxWordLength = 5
        /** Seconds of silence after which a partial send is dropped. */
        const val sendBufferTimeout = 2.5

        /** Spawn interval for a wave: 10% tighter per wave, floored, then scaled by difficulty. */
        fun spawnInterval(wave: Int, difficulty: InvadersDifficulty): Double =
            max(minSpawnInterval, baseSpawnInterval * spawnTightening.pow(max(0, wave - 1))) * difficulty.timeScale

        /** Approach time for a wave: 8% faster per wave, floored, then scaled by difficulty. */
        fun approachTime(wave: Int, difficulty: InvadersDifficulty): Double =
            max(minApproachTime, baseApproachTime * approachTightening.pow(max(0, wave - 1))) * difficulty.timeScale

        /** Combo multiplier: ×1 for the first three hits in a row, ×2 for the next three, up to ×4. */
        fun multiplier(combo: Int): Int = min(4, 1 + max(0, combo - 1) / 3)

        /**
         * The share of spawns that are words at a wave: none before
         * [wordWaveStart], then 15% more each wave up to a half.
         */
        fun wordChance(wave: Int): Double =
            if (wave < wordWaveStart) 0.0 else min(maxWordChance, wordChancePerWave * (wave - wordWaveStart + 1))

        /**
         * The word pool for a character set: the first [wordRank] of [words],
         * upper-cased, [minWordLength]…[maxWordLength] long, spelt entirely
         * from the set, without repeats, in list order.
         */
        fun wordPool(words: List<String>, characters: List<Char>): List<String> {
            val set = characters.map { it.uppercaseChar() }.toSet()
            return words.take(wordRank)
                .map { it.uppercase() }
                .filter { it.length in minWordLength..maxWordLength }
                .filter { w -> w.all { it in set } }
                .distinct()
        }

        // Speed ramp, copy mode. Gentler than Invaders' (#194): a game opens
        // [rampStartOffset] WPM under the learner's character speed — never
        // under [minWpm], the app-wide floor — and climbs [rampStep] WPM every
        // [hitsPerRampStep] hits in total up to the character speed; a strike
        // on the ship steps it back, never under the start. Misses leave it
        // alone.
        const val minWpm = 15.0
        const val rampStartOffset = 8.0
        const val rampStep = 1.0
        const val hitsPerRampStep = 4

        fun rampStart(characterWpm: Double): Double = max(minWpm, characterWpm - rampStartOffset)

        /** The asteroid nearest the ship: highest progress, then lowest id. */
        private fun nearestOf(list: List<Asteroid>): Asteroid? =
            list.minWithOrNull(compareByDescending<Asteroid> { it.progress }.thenBy { it.id })
    }
}
