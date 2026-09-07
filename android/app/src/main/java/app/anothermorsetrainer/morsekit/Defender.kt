package app.anothermorsetrainer.morsekit

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Morse Defender (#188, part of #170): assets — cities and ships, each with a
 * callsign — sit along the bottom of the field. Attackers launch at the top,
 * and each announces its target's callsign in Morse; the learner copies the
 * callsign and routes a defence to that asset before the attacker arrives.
 * Pure game rules — no clock, no audio, no UI. Time enters through
 * [DefenderGame.advance], so the screen drives it from a frame clock and the
 * tests from arithmetic; every random choice comes from the injected [Random],
 * so a seed pins the callsigns and the launch sequence. The difficulty table,
 * scoring, ramp and a scripted scenario are pinned by fixtures/defender.json
 * on both ports.
 *
 * Translated from MorseKit/Defender.swift; the two must stay twins.
 */

/** How the learner routes a defence. */
enum class DefenderInput(val label: String, val blurb: String) {
    TAP(
        "Tap the target",
        "Each attacker sends the callsign of the asset it is heading for (tap the attacker to hear it again). Copy it and tap that asset to route the defence."
    ),
    TYPED(
        "Type the callsign",
        "Each attacker sends its target's callsign (tap the attacker to hear it again). Type the callsign on the keyboard; the defence routes itself the moment it matches an asset."
    )
}

/**
 * One asset under defence. [isAlive] goes false when an attacker reaches it;
 * the rubble stays in the row so the layout does not shift mid-game.
 */
data class DefenderAsset(
    val id: Int,
    val callsign: String,
    val isAlive: Boolean = true
)

/**
 * One attacker in flight. [progress] runs 0 at launch to 1 at its target;
 * [travelTime] is how many seconds that takes, fixed at launch so a wave
 * change mid-flight does not jolt the ones already flying. [column] is where
 * it descends; the target is known only by the callsign it sends.
 */
data class DefenderAttacker(
    val id: Int,
    val targetId: Int,
    val callsign: String,
    val column: Int,
    val progress: Double,
    val travelTime: Double
)

/** What one call to [DefenderGame.advance] did, in order. */
sealed class DefenderEvent {
    data class Launched(val attacker: DefenderAttacker) : DefenderEvent()
    /** An attacker reached its asset; the asset is passed after destruction. */
    data class Struck(val attacker: DefenderAttacker, val asset: DefenderAsset) : DefenderEvent()
    object GameOver : DefenderEvent()
}

/**
 * The outcome of one routed defence. [attacker] is the one destroyed (null on
 * a wasted shot), [asset] the one routed to (null when a typed callsign
 * matched nothing), [points] what the hit scored, [waveCleared] whether it
 * finished the wave, and [reinforced] the asset that wave clear added or
 * rebuilt, if any.
 */
data class DefenderRoute(
    val attacker: DefenderAttacker?,
    val asset: DefenderAsset?,
    val points: Int,
    val waveCleared: Boolean,
    val reinforced: DefenderAsset?
) {
    val isHit: Boolean get() = attacker != null

    companion object {
        val miss = DefenderRoute(null, null, 0, false, null)
    }
}

class DefenderGame(
    val config: Config,
    private val rng: Random = Random.Default
) {
    data class Config(
        /** The learner's characters, for the synthetic callsigns of [InvadersCharacterSet.ACTIVE]. */
        val characters: List<Char>,
        /** ACTIVE: call-like tokens spelled from [characters]; FULL: real callsigns. */
        val callsigns: InvadersCharacterSet = InvadersCharacterSet.ACTIVE,
        val difficulty: InvadersDifficulty = InvadersDifficulty.NORMAL,
        val columns: Int = defaultColumns,
        val startAssets: Int = defaultStartAssets,
        val maxAssets: Int = defaultMaxAssets,
        /** Hits that clear a wave, tighten the timings and reinforce the defence. */
        val hitsPerWave: Int = defaultHitsPerWave,
        /** The learner's character speed: where the speed ramp ends. */
        val characterWpm: Double = 20.0
    ) {
        /** Where a game's speed starts: [DefenderGame.rampStart]. */
        val startWpm: Double get() = rampStart(characterWpm)

        /** Where the ramp ends: the character speed, never under the floor. */
        val targetWpm: Double get() = max(minWpm, characterWpm)
    }

    var assets: List<DefenderAsset> = emptyList()
        private set
    var attackers: List<DefenderAttacker> = emptyList()
        private set
    var score = 0
        private set
    var wave = 1
        private set
    /** Consecutive hits since the last wasted shot or strike. */
    var combo = 0
        private set
    var bestCombo = 0
        private set
    var hits = 0
        private set
    /** Wasted shots plus attackers that reached their asset. */
    var misses = 0
        private set
    /** Game time in seconds, the sum of every [advance]. */
    var elapsed = 0.0
        private set
    var isOver = false
        private set
    /**
     * The speed callsigns are sent at now: starts at [Config.startWpm], climbs
     * with hits and falls back with strikes.
     */
    var currentWpm: Double = config.startWpm
        private set
    /** The highest speed the ramp reached this game. */
    var bestWpm: Double = config.startWpm
        private set

    private var waveHits = 0
    private var sinceSpawn = 0.0
    private var nextAssetId = 1
    private var nextAttackerId = 1
    private var lastColumn = -1

    init {
        repeat(max(1, min(config.startAssets, max(1, config.maxAssets)))) { placeAsset() }
    }

    /**
     * The characters a typed callsign can be spelled from: the synthetic
     * alphabet, or every letter and digit for real callsigns.
     */
    val keyboardPool: List<Char>
        get() = when (config.callsigns) {
            InvadersCharacterSet.ACTIVE -> callsignAlphabet(config.characters)
            InvadersCharacterSet.FULL -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toList()
        }

    /** Seconds between launches at the current wave. */
    val spawnInterval: Double get() = spawnInterval(wave, config.difficulty)

    /** Seconds a fresh attacker takes to reach its asset at the current wave. */
    val travelTime: Double get() = travelTime(wave, config.difficulty)

    /** Attackers allowed in flight at the current wave. */
    val concurrent: Int get() = concurrent(wave)

    /** Assets still standing. */
    val liveAssets: Int get() = assets.count { it.isAlive }

    /** Hits over every shot and strike, 0…1. */
    val accuracy: Double get() = if (hits + misses == 0) 0.0 else hits.toDouble() / (hits + misses)

    /** The attacker nearest its target, if any. */
    val nearestArrival: DefenderAttacker? get() = attackers.maxByOrNull { it.progress }

    /** The multiplier the next hit earns. */
    val multiplier: Int get() = multiplier(combo + 1)

    /**
     * The longest callsign among the standing assets — how many characters a
     * typed copy can run to before it is surely wrong.
     */
    val longestLiveCallsign: Int get() = assets.filter { it.isAlive }.maxOfOrNull { it.callsign.length } ?: 0

    /** Whether any attacker in flight is aimed at [assetId]. */
    fun isTargeted(assetId: Int): Boolean = attackers.any { it.targetId == assetId }

    /**
     * Move time forward by [seconds]: attackers fly, any that arrive destroy
     * their asset (and withdraw the others aimed at it), and the launch clock
     * releases new ones while there is room. Returns what happened, in order.
     * A finished game ignores time.
     */
    fun advance(seconds: Double): List<DefenderEvent> {
        if (isOver || seconds <= 0) return emptyList()
        val events = ArrayList<DefenderEvent>()
        elapsed += seconds

        val moved = attackers.map { it.copy(progress = it.progress + seconds / it.travelTime) }
        val arrived = moved.filter { it.progress >= 1.0 }.sortedByDescending { it.progress }
        var remaining = moved.filter { it.progress < 1.0 }
        val withdrawn = HashSet<Int>()
        for (hit in arrived) {
            if (hit.id in withdrawn) continue
            val a = hit.copy(progress = 1.0)
            val idx = assets.indexOfFirst { it.id == a.targetId }
            if (idx < 0 || !assets[idx].isAlive) continue
            val destroyed = assets[idx].copy(isAlive = false)
            assets = assets.toMutableList().also { it[idx] = destroyed }
            misses += 1
            combo = 0
            currentWpm = max(config.startWpm, currentWpm - rampStep)
            // The asset is gone: everything else aimed at it has nothing to hit.
            arrived.filter { it.id != a.id && it.targetId == a.targetId }.forEach { withdrawn.add(it.id) }
            remaining = remaining.filter { it.targetId != a.targetId }
            events.add(DefenderEvent.Struck(a, destroyed))
        }
        attackers = remaining
        if (liveAssets == 0) {
            isOver = true
            attackers = emptyList()
            events.add(DefenderEvent.GameOver)
            return events
        }

        sinceSpawn += seconds
        while (sinceSpawn >= spawnInterval) {
            if (attackers.size >= concurrent) {
                // Full: hold the clock at the interval so one launches the
                // moment a slot frees.
                sinceSpawn = spawnInterval
                break
            }
            sinceSpawn -= spawnInterval
            // Born partway down when the step overshot its launch time, so a
            // long frame does not gift the attacker extra flight time.
            val attacker = launch(progress = sinceSpawn / travelTime)
            events.add(DefenderEvent.Launched(attacker))
        }
        return events
    }

    /**
     * Route a defence to the asset with [assetId]. Destroys the attacker
     * nearest that asset among those aimed at it and scores `pointsPerHit`
     * times the combo multiplier; with none aimed at it (or the asset already
     * destroyed, or unknown) it is a wasted shot that breaks the combo. A
     * finished game ignores routes.
     */
    fun route(assetId: Int): DefenderRoute {
        if (isOver) return DefenderRoute.miss
        val asset = assets.firstOrNull { it.id == assetId }
        val target = if (asset != null && asset.isAlive) {
            attackers.filter { it.targetId == assetId }.maxByOrNull { it.progress }
        } else null
        if (asset == null || !asset.isAlive || target == null) {
            combo = 0
            misses += 1
            return DefenderRoute(null, asset, 0, false, null)
        }
        attackers = attackers.filter { it.id != target.id }
        combo += 1
        bestCombo = max(bestCombo, combo)
        val points = pointsPerHit * multiplier(combo)
        score += points
        hits += 1
        if (hits % hitsPerRampStep == 0) {
            currentWpm = min(config.targetWpm, currentWpm + rampStep)
            bestWpm = max(bestWpm, currentWpm)
        }
        waveHits += 1
        var cleared = false
        var reinforced: DefenderAsset? = null
        if (waveHits >= max(1, config.hitsPerWave)) {
            wave += 1
            waveHits = 0
            cleared = true
            reinforced = reinforce()
        }
        return DefenderRoute(target, asset, points, cleared, reinforced)
    }

    /**
     * Route a defence by callsign, as typed: the standing asset with that
     * callsign (case-insensitive) takes it; no such asset is a wasted shot.
     */
    fun route(callsign: String): DefenderRoute {
        if (isOver) return DefenderRoute.miss
        val wanted = callsign.trim().uppercase()
        val asset = assets.firstOrNull { it.isAlive && it.callsign == wanted }
        if (asset == null) {
            combo = 0
            misses += 1
            return DefenderRoute.miss
        }
        return route(asset.id)
    }

    // ---- Private ----

    /**
     * A wave clear's reinforcement: a new asset while there is room, else the
     * first destroyed one rebuilt, else nothing.
     */
    private fun reinforce(): DefenderAsset? {
        if (assets.size < config.maxAssets) return placeAsset()
        val idx = assets.indexOfFirst { !it.isAlive }
        if (idx < 0) return null
        val rebuilt = DefenderAsset(nextAssetId, freshCallsign())
        nextAssetId += 1
        assets = assets.toMutableList().also { it[idx] = rebuilt }
        return rebuilt
    }

    private fun placeAsset(): DefenderAsset {
        val asset = DefenderAsset(nextAssetId, freshCallsign())
        nextAssetId += 1
        assets = assets + asset
        return asset
    }

    /**
     * A callsign no asset on the field (standing or destroyed) has carried
     * this game, so a copy is never ambiguous; a tiny alphabet may run out, in
     * which case the last draw stands.
     */
    private fun freshCallsign(): String {
        val taken = assets.map { it.callsign }.toSet()
        var call = ""
        repeat(40) {
            call = drawCallsign()
            if (call !in taken) return call
        }
        return call
    }

    private fun drawCallsign(): String = when (config.callsigns) {
        InvadersCharacterSet.ACTIVE -> syntheticCallsign(config.characters, rng)
        InvadersCharacterSet.FULL -> realCallsign(rng)
    }

    private fun launch(progress: Double): DefenderAttacker {
        // Prefer an asset nothing is aimed at, so each callsign in the air is
        // usually a different one; with every asset covered, any will do.
        val live = assets.filter { it.isAlive }
        val open = live.filter { !isTargeted(it.id) }
        val choices = open.ifEmpty { live }
        val target = choices[rng.nextInt(choices.size)]
        val columns = max(1, config.columns)
        val column = if (columns == 1) 0 else {
            val free = (0 until columns).filter { it != lastColumn }
            free[rng.nextInt(free.size)]
        }
        lastColumn = column
        val attacker = DefenderAttacker(nextAttackerId, target.id, target.callsign, column, progress, travelTime)
        nextAttackerId += 1
        attackers = attackers + attacker
        return attacker
    }

    companion object {
        const val pointsPerHit = 100
        const val baseSpawnInterval = 5.0
        const val minSpawnInterval = 2.0
        const val spawnDecay = 0.9
        const val baseTravelTime = 14.0
        const val minTravelTime = 6.0
        const val travelDecay = 0.92
        const val maxConcurrent = 3
        const val defaultColumns = 5
        const val defaultStartAssets = 4
        const val defaultMaxAssets = 8
        const val defaultHitsPerWave = 6

        /** Seconds between launches at a wave: 10% tighter per wave, floored, then scaled by difficulty. */
        fun spawnInterval(wave: Int, difficulty: InvadersDifficulty): Double =
            max(minSpawnInterval, baseSpawnInterval * spawnDecay.pow(max(0, wave - 1))) * difficulty.timeScale

        /** Seconds from launch to the target at a wave: 8% faster per wave, floored, then scaled by difficulty. */
        fun travelTime(wave: Int, difficulty: InvadersDifficulty): Double =
            max(minTravelTime, baseTravelTime * travelDecay.pow(max(0, wave - 1))) * difficulty.timeScale

        /** Attackers in flight at once: one, then one more every two waves, up to [maxConcurrent]. */
        fun concurrent(wave: Int): Int = min(maxConcurrent, 1 + max(0, wave - 1) / 2)

        /** Combo multiplier: ×1 for the first three hits in a row, ×2 for the next three, up to ×4. */
        fun multiplier(combo: Int): Int = min(4, 1 + max(0, combo - 1) / 3)

        // Speed ramp. A game opens [rampStartOffset] WPM under the learner's
        // character speed — never under [minWpm], the app-wide floor — and
        // climbs [rampStep] WPM every [hitsPerRampStep] hits in total up to
        // the character speed; a strike steps it back, never under the start.
        // Wasted shots leave it alone. Unlike Invaders, Farnsworth spacing IS
        // honoured — a callsign has gaps to stretch — see [sendTiming]. Its
        // own constants, gentler than Invaders': callsigns are heavier than
        // single characters.
        const val minWpm = 15.0
        const val rampStartOffset = 8.0
        const val rampStep = 2.0
        const val hitsPerRampStep = 4

        /**
         * The speed a game starts at for a character speed: 8 WPM under it,
         * floored at 15. At or under 15 there is no ramp.
         */
        fun rampStart(characterWpm: Double): Double = max(minWpm, characterWpm - rampStartOffset)

        /**
         * The timing a callsign is sent with at the ramp's [wpm]: standard when
         * the Farnsworth switch is off ([farnsworthWpm] null), otherwise
         * characters at [wpm] with the spacing stretched to the effective
         * speed, which can never exceed the character speed.
         */
        fun sendTiming(wpm: Double, farnsworthWpm: Double?): MorseTiming =
            if (farnsworthWpm == null) MorseTiming(wpm)
            else MorseTiming.farnsworth(wpm, min(wpm, farnsworthWpm))

        private val realFormats = CallsignFormat.commonDefaults

        /**
         * The letters then digits of [characters], upper-cased, without repeats
         * and without anything else (punctuation, prosigns); K and M when
         * nothing usable is left. What the synthetic callsigns are spelled from.
         */
        fun callsignAlphabet(characters: List<Char>): List<Char> {
            val upper = characters.map { it.uppercaseChar() }
                .filter { it in 'A'..'Z' || it in '0'..'9' }
                .distinct()
            val alphabet = upper.filter { it.isLetter() } + upper.filter { it.isDigit() }
            return alphabet.ifEmpty { MorseCode.kochOrder.take(2) }
        }

        /**
         * A four-character call-like token from the learner's own set:
         * position 1 is a digit when the set has any, the rest letters (or
         * digits when the set has no letters), so K5MR looks like a call and
         * KMRS like a group — and nothing is sent the learner has not met.
         */
        fun syntheticCallsign(characters: List<Char>, rng: Random): String {
            val alphabet = callsignAlphabet(characters)
            val letters = alphabet.filter { it.isLetter() }
            val digits = alphabet.filter { it.isDigit() }
            val body = letters.ifEmpty { digits }
            val middle = digits.ifEmpty { body }
            val s = StringBuilder()
            for (i in 0 until 4) {
                val pool = if (i == 1) middle else body
                s.append(pool[rng.nextInt(pool.size)])
            }
            return s.toString()
        }

        /** A real US-style callsign in the everyday shapes (1×2, 2×1, 1×3, 2×2). */
        fun realCallsign(rng: Random): String =
            CallsignGenerator.generate(realFormats, usOnly = true, rng = rng)
    }
}
