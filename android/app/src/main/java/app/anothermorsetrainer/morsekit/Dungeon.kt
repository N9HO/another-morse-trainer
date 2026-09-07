package app.anothermorsetrainer.morsekit

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * CW Dungeon (#186, #170): a small top-down roguelike. A run is a sequence of
 * rooms; each room holds one or two monsters. A monster "casts" by sending a
 * spell word in Morse, and the learner copies it and KEYS the counter word
 * (the spell book on screen says which) before the attack window closes. A
 * counter keyed in time takes a hit off the monster; a wrong or late one costs
 * a life. Copy practice on the way in, sending practice on the way out.
 *
 * Pure game rules — no clock, no audio, no UI. Time enters through
 * [DungeonGame.advance], so the screen drives it from a frame clock and the
 * tests from arithmetic; every random choice comes from the injected [Random],
 * so a seed pins the spell sequence. Pinned by fixtures/dungeon.json on both
 * ports.
 *
 * Translated from MorseKit/Dungeon.swift; the two must stay twins.
 */

/** A monster's spell and the word that counters it. */
data class DungeonSpell(
    val spell: String,
    val counter: String,
    /** A countered heal spell restores one life as well as landing the hit. */
    val heals: Boolean = false
) {
    /** Every character the pair needs, so an active set can be tested against it. */
    val characters: Set<Char> get() = (spell + counter).toSet()

    /** "FIRE → WATER", for the spell book. */
    val label: String get() = "$spell → $counter"
}

/**
 * The spells a game draws from and whether the learner's set was too small to
 * spell enough of them, so the screen can say the starters are in use.
 */
data class DungeonPool(val spells: List<DungeonSpell>, val fallback: Boolean)

/**
 * The spell book: a fixed thematic list, ordered by the latest Koch character
 * each pair needs, so a learner partway up the ladder meets the early ones
 * first. The same words are in the Swift `DungeonSpells`.
 */
object DungeonSpells {
    val all: List<DungeonSpell> = listOf(
        DungeonSpell("TRAP", "MAP"),
        DungeonSpell("ROT", "SALT", heals = true),
        DungeonSpell("STORM", "PORT"),
        DungeonSpell("MAUL", "ARMOR"),
        DungeonSpell("SWARM", "SWAT"),
        DungeonSpell("MIST", "LAMP"),
        DungeonSpell("SNOW", "SUN"),
        DungeonSpell("STUN", "SLAP"),
        DungeonSpell("WILT", "POTION", heals = true),
        DungeonSpell("SLEEP", "WAKE"),
        DungeonSpell("OMEN", "TOTEM"),
        DungeonSpell("FIRE", "WATER"),
        DungeonSpell("FROST", "FLAME"),
        DungeonSpell("SMOKE", "FAN"),
        DungeonSpell("KNOT", "KNIFE"),
        DungeonSpell("GLOOM", "GLOW"),
        DungeonSpell("QUAKE", "LEAP"),
        DungeonSpell("GAZE", "VEIL"),
        DungeonSpell("VENOM", "HEAL", heals = true),
        DungeonSpell("CURSE", "BLESS"),
        DungeonSpell("SHADOW", "LIGHT"),
        DungeonSpell("BLAZE", "DOUSE"),
        DungeonSpell("DECAY", "MEND", heals = true),
        DungeonSpell("HEX", "CHARM")
    )

    /**
     * The first spells of the list — spelled from the first ten Koch
     * characters — used when the active set spells fewer than [minPool].
     */
    const val starterCount = 4
    const val minPool = 3

    val starters: List<DungeonSpell> get() = all.take(starterCount)

    /** Every spell whose pair is spelled entirely from [characters] (case-insensitive). */
    fun available(characters: List<Char>): List<DungeonSpell> {
        val active = characters.map { it.uppercaseChar() }.toSet()
        return all.filter { active.containsAll(it.characters) }
    }

    /** The pool for a set: what it spells, or the starters when that is too few. */
    fun pool(characters: List<Char>): DungeonPool {
        val spells = available(characters)
        if (spells.size < minPool) return DungeonPool(starters, fallback = true)
        return DungeonPool(spells, fallback = false)
    }
}

/** What a monster looks like; the screens draw each kind from its own bitmap. */
enum class DungeonMonsterKind(val label: String) {
    SLIME("Slime"),
    BAT("Bat"),
    SKELETON("Skeleton"),
    GHOST("Ghost"),
    DRAGON("Dragon");

    /** The fixture's lower-case name. */
    val key: String get() = name.lowercase()
}

/** One monster in the room. [hits] is what it has left; at zero it is down. */
data class DungeonMonster(
    val id: Int,
    val kind: DungeonMonsterKind,
    val hits: Int,
    val maxHits: Int
) {
    val isDown: Boolean get() = hits <= 0
}

/**
 * A spell in flight. The word sounds for [sendSeconds] (at [timing]), then the
 * attack window of [window] seconds runs; the attack lands at
 * `sendSeconds + window` after the cast unless the counter is keyed first.
 */
data class DungeonCast(
    val monsterId: Int,
    val spell: DungeonSpell,
    val timing: MorseTiming,
    val sendSeconds: Double,
    val window: Double
)

/** What one call to [DungeonGame.advance] did, in order. */
sealed class DungeonEvent {
    /** A monster began a spell: sound it. */
    data class Cast(val cast: DungeonCast) : DungeonEvent()
    /** The window closed with no counter: the attack landed. */
    data class Attacked(val cast: DungeonCast) : DungeonEvent()
    object GameOver : DungeonEvent()
}

enum class DungeonOutcome { COUNTERED, WRONG }

/** The outcome of keying a word at the pending spell. */
data class DungeonCastResult(
    val outcome: DungeonOutcome,
    val spell: DungeonSpell,
    /** What was keyed, upper-cased and trimmed. */
    val keyed: String,
    val points: Int,
    val monsterDown: Boolean,
    val roomCleared: Boolean,
    val healed: Boolean,
    val gameOver: Boolean
) {
    val isCountered: Boolean get() = outcome == DungeonOutcome.COUNTERED

    /** The learner echoed the spell instead of countering it. */
    val isEcho: Boolean get() = outcome == DungeonOutcome.WRONG && keyed == spell.spell
}

/**
 * One expected character of a counter word against what was keyed at the same
 * position — what the confusion matrix and character stats take. [chosen] is
 * null when nothing was keyed at that position (short or late).
 */
data class DungeonCharacterOutcome(val target: Char, val chosen: Char?) {
    val isCorrect: Boolean get() = chosen == target
}

class DungeonGame(
    val config: Config,
    private val rng: Random = Random.Default
) {
    data class Config(
        /** The spell book for this run; empty falls back to the starters. */
        val spells: List<DungeonSpell>,
        val difficulty: InvadersDifficulty = InvadersDifficulty.NORMAL,
        val lives: Int = 3,
        /** The learner's character speed: where the speed ramp ends. */
        val characterWpm: Double = 20.0,
        /**
         * Farnsworth effective speed for the sent spell, or null for standard
         * spacing. A spell is a word, so unlike an invader it has gaps to
         * stretch; the ramp moves the character speed only.
         */
        val effectiveWpm: Double? = null
    ) {
        /** Where a game's speed starts: [DungeonGame.rampStart]. */
        val startWpm: Double get() = rampStart(characterWpm)

        /** Where the ramp ends: the character speed, never under the floor. */
        val targetWpm: Double get() = max(minWpm, characterWpm)
    }

    private val pool: List<DungeonSpell> = config.spells.ifEmpty { DungeonSpells.starters }

    var room = 1
        private set
    var monsters: List<DungeonMonster> = emptyList()
        private set
    var score = 0
        private set
    /** The lives a run starts with; a heal never goes above it. */
    val maxLives: Int = max(1, config.lives)
    var lives = max(1, config.lives)
        private set
    /** Consecutive counters since the last hit taken. */
    var combo = 0
        private set
    var bestCombo = 0
        private set
    var hits = 0
        private set
    /** Wrong counters plus attacks that landed. */
    var misses = 0
        private set
    var roomsCleared = 0
        private set
    /** Game time in seconds, the sum of every [advance]. */
    var elapsed = 0.0
        private set
    var isOver = false
        private set
    /**
     * The speed spells are sent at now: starts at [Config.startWpm], climbs
     * with counters and falls back with hits taken.
     */
    var currentWpm: Double = config.startWpm
        private set
    var bestWpm: Double = config.startWpm
        private set
    /** The spell in flight, if any. */
    var pendingCast: DungeonCast? = null
        private set
    /** Seconds since the pending cast began. */
    var castElapsed = 0.0
        private set

    private var nextCastIn = castDelay
    private var nextId = 1
    private var lastSpell: DungeonSpell? = null

    init {
        monsters = makeMonsters(1)
    }

    /** The attack window at the current room. */
    val window: Double get() = window(room, config.difficulty)

    /** The timing a spell is sent at now. */
    val timing: MorseTiming
        get() {
            val eff = config.effectiveWpm
            return if (eff != null) MorseTiming.farnsworth(currentWpm, min(eff, currentWpm)) else MorseTiming(currentWpm)
        }

    /** Counters over every counter and attack, 0…1. */
    val accuracy: Double get() = if (hits + misses == 0) 0.0 else hits.toDouble() / (hits + misses)

    /** The multiplier the next counter earns. */
    val multiplier: Int get() = multiplier(combo + 1)

    /** The monster casting now, or the next to cast: the first still standing. */
    val activeMonster: DungeonMonster? get() = monsters.firstOrNull { !it.isDown }

    /** True while the pending spell is still sounding. */
    val isSending: Boolean
        get() {
            val cast = pendingCast ?: return false
            return castElapsed < cast.sendSeconds
        }

    /**
     * Seconds left in the attack window, or null with no spell pending. While
     * the spell is still sounding this is the whole window.
     */
    val windowRemaining: Double?
        get() {
            val cast = pendingCast ?: return null
            return max(0.0, cast.sendSeconds + cast.window - castElapsed)
        }

    /**
     * Move time forward by [seconds]: a pending spell's clock runs and its
     * attack lands when the window closes; otherwise the cast clock counts
     * down to the next spell. Returns what happened, in order. A finished game
     * ignores time.
     */
    fun advance(seconds: Double): List<DungeonEvent> {
        if (isOver || seconds <= 0) return emptyList()
        val events = ArrayList<DungeonEvent>()
        elapsed += seconds

        val cast = pendingCast
        if (cast != null) {
            castElapsed += seconds
            if (castElapsed >= cast.sendSeconds + cast.window) {
                pendingCast = null
                takeHit()
                nextCastIn = castDelay
                events.add(DungeonEvent.Attacked(cast))
                if (lives == 0) {
                    isOver = true
                    events.add(DungeonEvent.GameOver)
                }
            }
            return events
        }

        nextCastIn -= seconds
        val monster = activeMonster
        if (nextCastIn <= 0 && monster != null) {
            val spell = pickSpell()
            val t = timing
            val next = DungeonCast(monster.id, spell, t, sendSeconds(spell.spell, t), window)
            pendingCast = next
            castElapsed = 0.0
            events.add(DungeonEvent.Cast(next))
        }
        return events
    }

    /**
     * Key a word at the pending spell. The counter takes a hit off the casting
     * monster and scores [pointsPerHit] times the combo multiplier (a heal
     * spell also restores a life); anything else costs a life. Null when no
     * spell is pending or the game is over.
     */
    fun cast(word: String): DungeonCastResult? {
        if (isOver) return null
        val pending = pendingCast ?: return null
        val keyed = word.trim().uppercase()
        pendingCast = null
        if (keyed != pending.spell.counter) {
            takeHit()
            nextCastIn = castDelay
            if (lives == 0) isOver = true
            return DungeonCastResult(
                DungeonOutcome.WRONG, pending.spell, keyed, 0,
                monsterDown = false, roomCleared = false, healed = false, gameOver = isOver
            )
        }
        combo += 1
        bestCombo = max(bestCombo, combo)
        val points = pointsPerHit * multiplier(combo)
        score += points
        hits += 1
        if (hits % hitsPerRampStep == 0) {
            currentWpm = min(config.targetWpm, currentWpm + rampStep)
            bestWpm = max(bestWpm, currentWpm)
        }
        var healed = false
        if (pending.spell.heals && lives < maxLives) {
            lives += 1
            healed = true
        }
        var monsterDown = false
        monsters = monsters.map { m ->
            if (m.id == pending.monsterId) {
                val struck = m.copy(hits = max(0, m.hits - 1))
                monsterDown = struck.isDown
                struck
            } else m
        }
        var roomCleared = false
        if (monsters.all { it.isDown }) {
            roomCleared = true
            score += roomClearBonus
            roomsCleared += 1
            room += 1
            monsters = makeMonsters(room)
            nextCastIn = roomDelay
        } else {
            nextCastIn = castDelay
        }
        return DungeonCastResult(
            DungeonOutcome.COUNTERED, pending.spell, keyed, points,
            monsterDown = monsterDown, roomCleared = roomCleared, healed = healed, gameOver = false
        )
    }

    private fun takeHit() {
        lives = max(0, lives - 1)
        misses += 1
        combo = 0
        currentWpm = max(config.startWpm, currentWpm - rampStep)
    }

    private fun makeMonsters(room: Int): List<DungeonMonster> =
        layout(room).map { (kind, hits) ->
            val id = nextId
            nextId += 1
            DungeonMonster(id, kind, hits, hits)
        }

    private fun pickSpell(): DungeonSpell {
        // Not the same spell twice running when there is a choice.
        val choices = if (pool.size > 1) pool.filter { it != lastSpell } else pool
        val spell = choices[rng.nextInt(choices.size)]
        lastSpell = spell
        return spell
    }

    companion object {
        const val pointsPerHit = 100
        const val roomClearBonus = 250
        const val hitsPerMonster = 2
        const val bossEvery = 5
        const val bossHits = 4

        // Attack windows, in seconds after the spell has finished sounding: 8%
        // shorter each room, floored, then scaled by difficulty.
        const val baseWindow = 6.0
        const val windowDecay = 0.92
        const val minWindow = 2.5
        /** Seconds from the start (or a resolution) to the next cast. */
        const val castDelay = 1.0
        /** Seconds a cleared room waits before its first cast. */
        const val roomDelay = 2.0

        /** The attack window for a room. */
        fun window(room: Int, difficulty: InvadersDifficulty): Double =
            max(minWindow, baseWindow * windowDecay.pow(max(0, room - 1))) * difficulty.timeScale

        /**
         * Combo multiplier, the Invaders rule: ×1 for the first three hits in a
         * row, ×2 for the next three, up to ×4.
         */
        fun multiplier(combo: Int): Int = min(4, 1 + max(0, combo - 1) / 3)

        /**
         * The monsters a room opens with: one in rooms 1–2, two after that, a
         * lone dragon every fifth room. Regular kinds cycle by room.
         */
        fun layout(room: Int): List<Pair<DungeonMonsterKind, Int>> {
            if (room % bossEvery == 0) return listOf(DungeonMonsterKind.DRAGON to bossHits)
            val kinds = listOf(DungeonMonsterKind.SLIME, DungeonMonsterKind.BAT, DungeonMonsterKind.SKELETON, DungeonMonsterKind.GHOST)
            val kind = kinds[max(0, room - 1) % kinds.size]
            val count = if (room <= 2) 1 else 2
            return List(count) { kind to hitsPerMonster }
        }

        /**
         * How long a word takes to send at [timing]: its characters plus the
         * character gaps between them, no trailing gap.
         */
        fun sendSeconds(word: String, timing: MorseTiming): Double {
            if (word.isEmpty()) return 0.0
            val tones = word.fold(0.0) { acc, ch -> acc + timing.duration(ch) }
            return tones + (word.length - 1) * timing.characterGap
        }

        /**
         * Each expected character against the keyed one at its position; extra
         * keyed characters are ignored. Both are upper-cased.
         */
        fun characterOutcomes(expected: String, keyed: String): List<DungeonCharacterOutcome> {
            val e = expected.uppercase()
            val k = keyed.uppercase()
            return e.mapIndexed { i, target -> DungeonCharacterOutcome(target, k.getOrNull(i)) }
        }

        // Speed ramp. A game opens [rampStartOffset] WPM under the learner's
        // character speed — never under [minWpm] — and climbs [rampStep] WPM
        // every [hitsPerRampStep] hits in total up to the character speed; a
        // hit taken steps it back, never under the start. Gentler than
        // Invaders': a word is longer than a character.
        const val minWpm = 15.0
        const val rampStartOffset = 8.0
        const val rampStep = 1.0
        const val hitsPerRampStep = 4

        fun rampStart(characterWpm: Double): Double = max(minWpm, characterWpm - rampStartOffset)
    }
}
