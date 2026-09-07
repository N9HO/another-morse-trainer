import Foundation

// CW Dungeon (#186, #170): a small top-down roguelike. A run is a sequence
// of rooms; each room holds one or two monsters. A monster "casts" by sending
// a spell word in Morse, and the learner copies it and KEYS the counter word
// (the spell book on screen says which) before the attack window closes. A
// counter keyed in time takes a hit off the monster; a wrong or late one costs
// a life. Copy practice on the way in, sending practice on the way out.
//
// Pure game rules — no clock, no audio, no UI. Time enters through
// `DungeonGame.advance(by:)`, so the view drives it from a frame clock and the
// harness from arithmetic; every random choice comes from the injected
// generator, so a seed pins the spell sequence. Pinned by fixtures/dungeon.json
// on both ports.
//
// Twin of the Kotlin `morsekit/Dungeon.kt`; the two must stay in step.

/// A monster's spell and the word that counters it.
public struct DungeonSpell: Equatable, Hashable, Sendable {
    public let spell: String
    public let counter: String
    /// A countered heal spell restores one life as well as landing the hit.
    public let heals: Bool

    public init(spell: String, counter: String, heals: Bool = false) {
        self.spell = spell
        self.counter = counter
        self.heals = heals
    }

    /// Every character the pair needs, so an active set can be tested against it.
    public var characters: Set<Character> { Set(spell + counter) }

    /// "FIRE → WATER", for the spell book.
    public var label: String { "\(spell) → \(counter)" }
}

/// The spells a game draws from and whether the learner's set was too small
/// to spell enough of them, so the screen can say the starters are in use.
public struct DungeonPool: Equatable, Sendable {
    public let spells: [DungeonSpell]
    public let fallback: Bool

    public init(spells: [DungeonSpell], fallback: Bool) {
        self.spells = spells
        self.fallback = fallback
    }
}

/// The spell book: a fixed thematic list, ordered by the latest Koch
/// character each pair needs, so a learner partway up the ladder meets the
/// early ones first. The same words are in the Kotlin `DungeonSpells`.
public enum DungeonSpells {
    public static let all: [DungeonSpell] = [
        DungeonSpell(spell: "TRAP", counter: "MAP"),
        DungeonSpell(spell: "ROT", counter: "SALT", heals: true),
        DungeonSpell(spell: "STORM", counter: "PORT"),
        DungeonSpell(spell: "MAUL", counter: "ARMOR"),
        DungeonSpell(spell: "SWARM", counter: "SWAT"),
        DungeonSpell(spell: "MIST", counter: "LAMP"),
        DungeonSpell(spell: "SNOW", counter: "SUN"),
        DungeonSpell(spell: "STUN", counter: "SLAP"),
        DungeonSpell(spell: "WILT", counter: "POTION", heals: true),
        DungeonSpell(spell: "SLEEP", counter: "WAKE"),
        DungeonSpell(spell: "OMEN", counter: "TOTEM"),
        DungeonSpell(spell: "FIRE", counter: "WATER"),
        DungeonSpell(spell: "FROST", counter: "FLAME"),
        DungeonSpell(spell: "SMOKE", counter: "FAN"),
        DungeonSpell(spell: "KNOT", counter: "KNIFE"),
        DungeonSpell(spell: "GLOOM", counter: "GLOW"),
        DungeonSpell(spell: "QUAKE", counter: "LEAP"),
        DungeonSpell(spell: "GAZE", counter: "VEIL"),
        DungeonSpell(spell: "VENOM", counter: "HEAL", heals: true),
        DungeonSpell(spell: "CURSE", counter: "BLESS"),
        DungeonSpell(spell: "SHADOW", counter: "LIGHT"),
        DungeonSpell(spell: "BLAZE", counter: "DOUSE"),
        DungeonSpell(spell: "DECAY", counter: "MEND", heals: true),
        DungeonSpell(spell: "HEX", counter: "CHARM"),
    ]

    /// The first spells of the list — spelled from the first ten Koch
    /// characters — used when the active set spells fewer than `minPool`.
    public static let starterCount = 4
    public static let minPool = 3

    public static var starters: [DungeonSpell] { Array(all.prefix(starterCount)) }

    /// Every spell whose pair is spelled entirely from `characters` (case-insensitive).
    public static func available(for characters: [Character]) -> [DungeonSpell] {
        let active = Set(characters.map { Character(String($0).uppercased()) })
        return all.filter { $0.characters.isSubset(of: active) }
    }

    /// The pool for a set: what it spells, or the starters when that is too few.
    public static func pool(for characters: [Character]) -> DungeonPool {
        let spells = available(for: characters)
        if spells.count < minPool { return DungeonPool(spells: starters, fallback: true) }
        return DungeonPool(spells: spells, fallback: false)
    }
}

/// What a monster looks like; the views draw each kind from its own bitmap.
public enum DungeonMonsterKind: String, Codable, CaseIterable, Sendable {
    case slime, bat, skeleton, ghost, dragon

    public var label: String {
        switch self {
        case .slime:    return "Slime"
        case .bat:      return "Bat"
        case .skeleton: return "Skeleton"
        case .ghost:    return "Ghost"
        case .dragon:   return "Dragon"
        }
    }
}

/// One monster in the room. `hits` is what it has left; at zero it is down.
public struct DungeonMonster: Identifiable, Equatable, Sendable {
    public let id: Int
    public let kind: DungeonMonsterKind
    public var hits: Int
    public let maxHits: Int

    public init(id: Int, kind: DungeonMonsterKind, hits: Int, maxHits: Int) {
        self.id = id
        self.kind = kind
        self.hits = hits
        self.maxHits = maxHits
    }

    public var isDown: Bool { hits <= 0 }
}

/// A spell in flight. The word sounds for `sendSeconds` (at `timing`), then
/// the attack window of `window` seconds runs; the attack lands at
/// `sendSeconds + window` after the cast unless the counter is keyed first.
public struct DungeonCast: Equatable, Sendable {
    public let monsterId: Int
    public let spell: DungeonSpell
    public let timing: MorseTiming
    public let sendSeconds: Double
    public let window: Double

    public init(monsterId: Int, spell: DungeonSpell, timing: MorseTiming, sendSeconds: Double, window: Double) {
        self.monsterId = monsterId
        self.spell = spell
        self.timing = timing
        self.sendSeconds = sendSeconds
        self.window = window
    }
}

/// What one call to `DungeonGame.advance(by:)` did, in order.
public enum DungeonEvent: Equatable, Sendable {
    /// A monster began a spell: sound it.
    case cast(DungeonCast)
    /// The window closed with no counter: the attack landed.
    case attacked(DungeonCast)
    case gameOver
}

public enum DungeonOutcome: Equatable, Sendable {
    case countered, wrong
}

/// The outcome of keying a word at the pending spell.
public struct DungeonCastResult: Equatable, Sendable {
    public let outcome: DungeonOutcome
    public let spell: DungeonSpell
    /// What was keyed, upper-cased and trimmed.
    public let keyed: String
    public let points: Int
    public let monsterDown: Bool
    public let roomCleared: Bool
    public let healed: Bool
    public let gameOver: Bool

    public init(outcome: DungeonOutcome, spell: DungeonSpell, keyed: String, points: Int,
                monsterDown: Bool, roomCleared: Bool, healed: Bool, gameOver: Bool) {
        self.outcome = outcome
        self.spell = spell
        self.keyed = keyed
        self.points = points
        self.monsterDown = monsterDown
        self.roomCleared = roomCleared
        self.healed = healed
        self.gameOver = gameOver
    }

    public var isCountered: Bool { outcome == .countered }
    /// The learner echoed the spell instead of countering it.
    public var isEcho: Bool { outcome == .wrong && keyed == spell.spell }
}

/// One expected character of a counter word against what was keyed at the
/// same position — what the confusion matrix and character stats take.
public struct DungeonCharacterOutcome: Equatable, Sendable {
    public let target: Character
    /// nil when nothing was keyed at that position (short or late).
    public let chosen: Character?

    public init(target: Character, chosen: Character?) {
        self.target = target
        self.chosen = chosen
    }

    public var isCorrect: Bool { chosen == target }
}

public final class DungeonGame {

    public struct Config: Sendable, Equatable {
        /// The spell book for this run; empty falls back to the starters.
        public var spells: [DungeonSpell]
        public var difficulty: InvadersDifficulty
        public var lives: Int
        /// The learner's character speed: where the speed ramp ends.
        public var characterWpm: Double
        /// Farnsworth effective speed for the sent spell, or nil for standard
        /// spacing. A spell is a word, so unlike an invader it has gaps to
        /// stretch; the ramp moves the character speed only.
        public var effectiveWpm: Double?

        public init(spells: [DungeonSpell],
                    difficulty: InvadersDifficulty = .normal,
                    lives: Int = 3,
                    characterWpm: Double = 20,
                    effectiveWpm: Double? = nil) {
            self.spells = spells
            self.difficulty = difficulty
            self.lives = lives
            self.characterWpm = characterWpm
            self.effectiveWpm = effectiveWpm
        }

        /// Where a game's speed starts: `DungeonGame.rampStart(characterWpm:)`.
        public var startWpm: Double { DungeonGame.rampStart(characterWpm: characterWpm) }

        /// Where the ramp ends: the character speed, never under the floor.
        public var targetWpm: Double { max(DungeonGame.minWpm, characterWpm) }
    }

    public static let pointsPerHit = 100
    public static let roomClearBonus = 250
    public static let hitsPerMonster = 2
    public static let bossEvery = 5
    public static let bossHits = 4

    // Attack windows, in seconds after the spell has finished sounding: 8%
    // shorter each room, floored, then scaled by difficulty.
    public static let baseWindow = 6.0
    public static let windowDecay = 0.92
    public static let minWindow = 2.5
    /// Seconds from the start (or a resolution) to the next cast.
    public static let castDelay = 1.0
    /// Seconds a cleared room waits before its first cast.
    public static let roomDelay = 2.0

    /// The attack window for a room.
    public static func window(room: Int, difficulty: InvadersDifficulty) -> Double {
        max(minWindow, baseWindow * pow(windowDecay, Double(max(0, room - 1)))) * difficulty.timeScale
    }

    /// Combo multiplier, the Invaders rule: ×1 for the first three hits in a
    /// row, ×2 for the next three, up to ×4.
    public static func multiplier(combo: Int) -> Int {
        min(4, 1 + max(0, combo - 1) / 3)
    }

    /// The monsters a room opens with: one in rooms 1–2, two after that, a
    /// lone dragon every fifth room. Regular kinds cycle by room.
    public static func layout(room: Int) -> [(kind: DungeonMonsterKind, hits: Int)] {
        if room % bossEvery == 0 { return [(.dragon, bossHits)] }
        let kinds: [DungeonMonsterKind] = [.slime, .bat, .skeleton, .ghost]
        let kind = kinds[max(0, room - 1) % kinds.count]
        let count = room <= 2 ? 1 : 2
        return Array(repeating: (kind, hitsPerMonster), count: count)
    }

    /// How long a word takes to send at `timing`: its characters plus the
    /// character gaps between them, no trailing gap.
    public static func sendSeconds(_ word: String, timing: MorseTiming) -> Double {
        let chars = Array(word)
        guard !chars.isEmpty else { return 0 }
        let tones = chars.reduce(0.0) { $0 + timing.duration(of: $1) }
        return tones + Double(chars.count - 1) * timing.characterGap
    }

    /// Each expected character against the keyed one at its position; extra
    /// keyed characters are ignored. Both are upper-cased.
    public static func characterOutcomes(expected: String, keyed: String) -> [DungeonCharacterOutcome] {
        let e = Array(expected.uppercased())
        let k = Array(keyed.uppercased())
        return e.enumerated().map { i, target in
            DungeonCharacterOutcome(target: target, chosen: i < k.count ? k[i] : nil)
        }
    }

    // Speed ramp. A game opens `rampStartOffset` WPM under the learner's
    // character speed — never under `minWpm` — and climbs `rampStep` WPM
    // every `hitsPerRampStep` hits in total up to the character speed; a hit
    // taken steps it back, never under the start. Gentler than Invaders': a
    // word is longer than a character.
    public static let minWpm = 15.0
    public static let rampStartOffset = 8.0
    public static let rampStep = 1.0
    public static let hitsPerRampStep = 4

    public static func rampStart(characterWpm: Double) -> Double {
        max(minWpm, characterWpm - rampStartOffset)
    }

    public let config: Config
    private var rng: any RandomNumberGenerator
    private let pool: [DungeonSpell]

    public private(set) var room = 1
    public private(set) var monsters: [DungeonMonster] = []
    public private(set) var score = 0
    public private(set) var lives: Int
    /// The lives a run starts with; a heal never goes above it.
    public let maxLives: Int
    /// Consecutive counters since the last hit taken.
    public private(set) var combo = 0
    public private(set) var bestCombo = 0
    public private(set) var hits = 0
    /// Wrong counters plus attacks that landed.
    public private(set) var misses = 0
    public private(set) var roomsCleared = 0
    /// Game time in seconds, the sum of every `advance(by:)`.
    public private(set) var elapsed = 0.0
    public private(set) var isOver = false
    /// The speed spells are sent at now: starts at `config.startWpm`, climbs
    /// with counters and falls back with hits taken.
    public private(set) var currentWpm: Double
    public private(set) var bestWpm: Double
    /// The spell in flight, if any.
    public private(set) var pendingCast: DungeonCast?
    /// Seconds since the pending cast began.
    public private(set) var castElapsed = 0.0

    private var nextCastIn: Double
    private var nextId = 1
    private var lastSpell: DungeonSpell?

    public init(config: Config,
                rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.config = config
        self.rng = rng
        self.maxLives = max(1, config.lives)
        self.lives = max(1, config.lives)
        self.currentWpm = config.startWpm
        self.bestWpm = config.startWpm
        self.pool = config.spells.isEmpty ? DungeonSpells.starters : config.spells
        self.nextCastIn = Self.castDelay
        self.monsters = makeMonsters(room: 1)
    }

    /// The attack window at the current room.
    public var window: Double { Self.window(room: room, difficulty: config.difficulty) }

    /// The timing a spell is sent at now.
    public var timing: MorseTiming {
        if let eff = config.effectiveWpm {
            return MorseTiming(characterWpm: currentWpm, effectiveWpm: min(eff, currentWpm))
        }
        return MorseTiming(wpm: currentWpm)
    }

    /// Counters over every counter and attack, 0…1.
    public var accuracy: Double {
        hits + misses == 0 ? 0 : Double(hits) / Double(hits + misses)
    }

    /// The multiplier the next counter earns.
    public var multiplier: Int { Self.multiplier(combo: combo + 1) }

    /// The monster casting now, or the next to cast: the first still standing.
    public var activeMonster: DungeonMonster? { monsters.first { !$0.isDown } }

    /// True while the pending spell is still sounding.
    public var isSending: Bool {
        guard let cast = pendingCast else { return false }
        return castElapsed < cast.sendSeconds
    }

    /// Seconds left in the attack window, or nil with no spell pending. While
    /// the spell is still sounding this is the whole window.
    public var windowRemaining: Double? {
        guard let cast = pendingCast else { return nil }
        return max(0, cast.sendSeconds + cast.window - castElapsed)
    }

    /// Move time forward by `seconds`: a pending spell's clock runs and its
    /// attack lands when the window closes; otherwise the cast clock counts
    /// down to the next spell. Returns what happened, in order. A finished
    /// game ignores time.
    @discardableResult
    public func advance(by seconds: Double) -> [DungeonEvent] {
        guard !isOver, seconds > 0 else { return [] }
        var events: [DungeonEvent] = []
        elapsed += seconds

        if let cast = pendingCast {
            castElapsed += seconds
            if castElapsed >= cast.sendSeconds + cast.window {
                pendingCast = nil
                takeHit()
                nextCastIn = Self.castDelay
                events.append(.attacked(cast))
                if lives == 0 {
                    isOver = true
                    events.append(.gameOver)
                }
            }
            return events
        }

        nextCastIn -= seconds
        if nextCastIn <= 0, let monster = activeMonster {
            let spell = pickSpell()
            let t = timing
            let cast = DungeonCast(monsterId: monster.id, spell: spell, timing: t,
                                   sendSeconds: Self.sendSeconds(spell.spell, timing: t),
                                   window: window)
            pendingCast = cast
            castElapsed = 0
            events.append(.cast(cast))
        }
        return events
    }

    /// Key a word at the pending spell. The counter takes a hit off the
    /// casting monster and scores `pointsPerHit` times the combo multiplier
    /// (a heal spell also restores a life); anything else costs a life. Nil
    /// when no spell is pending or the game is over.
    @discardableResult
    public func cast(_ word: String) -> DungeonCastResult? {
        guard !isOver, let pending = pendingCast else { return nil }
        let keyed = word.trimmingCharacters(in: .whitespaces).uppercased()
        pendingCast = nil
        guard keyed == pending.spell.counter else {
            takeHit()
            nextCastIn = Self.castDelay
            if lives == 0 { isOver = true }
            return DungeonCastResult(outcome: .wrong, spell: pending.spell, keyed: keyed, points: 0,
                                     monsterDown: false, roomCleared: false, healed: false, gameOver: isOver)
        }
        combo += 1
        bestCombo = max(bestCombo, combo)
        let points = Self.pointsPerHit * Self.multiplier(combo: combo)
        score += points
        hits += 1
        if hits % Self.hitsPerRampStep == 0 {
            currentWpm = min(config.targetWpm, currentWpm + Self.rampStep)
            bestWpm = max(bestWpm, currentWpm)
        }
        var healed = false
        if pending.spell.heals, lives < maxLives {
            lives += 1
            healed = true
        }
        var monsterDown = false
        if let i = monsters.firstIndex(where: { $0.id == pending.monsterId }) {
            monsters[i].hits = max(0, monsters[i].hits - 1)
            monsterDown = monsters[i].isDown
        }
        var roomCleared = false
        if monsters.allSatisfy(\.isDown) {
            roomCleared = true
            score += Self.roomClearBonus
            roomsCleared += 1
            room += 1
            monsters = makeMonsters(room: room)
            nextCastIn = Self.roomDelay
        } else {
            nextCastIn = Self.castDelay
        }
        return DungeonCastResult(outcome: .countered, spell: pending.spell, keyed: keyed, points: points,
                                 monsterDown: monsterDown, roomCleared: roomCleared, healed: healed, gameOver: false)
    }

    private func takeHit() {
        lives = max(0, lives - 1)
        misses += 1
        combo = 0
        currentWpm = max(config.startWpm, currentWpm - Self.rampStep)
    }

    private func makeMonsters(room: Int) -> [DungeonMonster] {
        Self.layout(room: room).map { entry in
            defer { nextId += 1 }
            return DungeonMonster(id: nextId, kind: entry.kind, hits: entry.hits, maxHits: entry.hits)
        }
    }

    private func pickSpell() -> DungeonSpell {
        // Not the same spell twice running when there is a choice.
        let choices = pool.count > 1 ? pool.filter { $0 != lastSpell } : pool
        let spell = choices[Int.random(in: 0..<choices.count, using: &rng)]
        lastSpell = spell
        return spell
    }
}
