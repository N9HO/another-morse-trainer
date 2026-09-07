import Foundation

// Morse Invaders (#170): characters descend the play field in columns; the
// learner shoots each one by naming it — typing it after hearing it (ICR) or
// keying it after seeing it. Pure game rules — no clock, no audio, no UI. Time
// is modelled explicitly through `InvadersGame.advance(by:)`, so the view
// drives it from a frame clock and the harness from arithmetic; every random
// choice comes from the injected generator, so a seed pins the spawn sequence.
//
// Twin of the Kotlin `morsekit/Invaders.kt`; the two must stay in step.

/// How the learner names an invader.
public enum InvadersInput: String, Codable, CaseIterable, Identifiable, Sendable {
    case icr, keying

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .icr:    return "Hear it, type it"
        case .keying: return "See it, key it"
        }
    }

    public var blurb: String {
        switch self {
        case .icr:
            return "Invaders come down blank. Each one is sent in Morse when it appears (tap it to hear it again); type the character you heard to shoot the lowest one carrying it."
        case .keying:
            return "Each invader shows its character. Key it on the on-screen key or a hardware key; the decoded character shoots the lowest one carrying it."
        }
    }
}

/// Scales every spawn interval and fall time — the same rules, more or less
/// breathing room.
public enum InvadersDifficulty: String, Codable, CaseIterable, Identifiable, Sendable {
    case relaxed, normal, fast

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .relaxed: return "Relaxed"
        case .normal:  return "Normal"
        case .fast:    return "Fast"
        }
    }

    public var timeScale: Double {
        switch self {
        case .relaxed: return 1.35
        case .normal:  return 1.0
        case .fast:    return 0.75
        }
    }
}

/// Which characters the invaders carry.
public enum InvadersCharacterSet: String, Codable, CaseIterable, Identifiable, Sendable {
    case active, full

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .active: return "My active characters"
        case .full:   return "Full alphabet and digits"
        }
    }
}

/// One invader on the field. `progress` runs 0 at the top to 1 at the ground;
/// `fallTime` is how many seconds that takes, fixed at spawn so a wave change
/// mid-flight does not jolt the ones already falling.
public struct Invader: Identifiable, Equatable, Sendable {
    public let id: Int
    public let character: Character
    public let column: Int
    public var progress: Double
    public let fallTime: Double

    public init(id: Int, character: Character, column: Int, progress: Double, fallTime: Double) {
        self.id = id
        self.character = character
        self.column = column
        self.progress = progress
        self.fallTime = fallTime
    }
}

/// What one call to `InvadersGame.advance(by:)` did, in order.
public enum InvadersEvent: Equatable, Sendable {
    case spawned(Invader)
    case escaped(Invader)
    case gameOver
}

/// The outcome of one shot. `invader` is the one hit (nil on a miss), `points`
/// what the hit scored, and `waveCleared` whether that hit finished the wave.
public struct InvadersShot: Equatable, Sendable {
    public let invader: Invader?
    public let points: Int
    public let waveCleared: Bool

    public init(invader: Invader?, points: Int, waveCleared: Bool) {
        self.invader = invader
        self.points = points
        self.waveCleared = waveCleared
    }

    public var isHit: Bool { invader != nil }
}

public final class InvadersGame {

    public struct Config: Sendable, Equatable {
        /// The characters invaders carry; empty falls back to the first two Koch characters.
        public var characters: [Character]
        public var difficulty: InvadersDifficulty
        public var columns: Int
        public var lives: Int
        /// Hits that clear a wave and tighten the timings.
        public var hitsPerWave: Int
        /// The learner's character speed: where the speed ramp (#178) ends.
        public var characterWpm: Double

        public init(characters: [Character],
                    difficulty: InvadersDifficulty = .normal,
                    columns: Int = 5,
                    lives: Int = 3,
                    hitsPerWave: Int = 10,
                    characterWpm: Double = 20) {
            self.characters = characters
            self.difficulty = difficulty
            self.columns = columns
            self.lives = lives
            self.hitsPerWave = hitsPerWave
            self.characterWpm = characterWpm
        }

        /// Where a game's speed starts: `InvadersGame.rampStart(characterWpm:)`.
        public var startWpm: Double { InvadersGame.rampStart(characterWpm: characterWpm) }

        /// Where the ramp ends: the character speed, never under the floor.
        public var targetWpm: Double { max(InvadersGame.minWpm, characterWpm) }
    }

    public static let pointsPerHit = 100
    public static let baseSpawnInterval = 2.4
    public static let baseFallTime = 8.0
    public static let minSpawnInterval = 0.9
    public static let minFallTime = 3.0

    /// Spawn interval for a wave: 12% tighter per wave, floored, then scaled by difficulty.
    public static func spawnInterval(wave: Int, difficulty: InvadersDifficulty) -> Double {
        max(minSpawnInterval, baseSpawnInterval * pow(0.88, Double(max(0, wave - 1)))) * difficulty.timeScale
    }

    /// Fall time for a wave: 10% faster per wave, floored, then scaled by difficulty.
    public static func fallTime(wave: Int, difficulty: InvadersDifficulty) -> Double {
        max(minFallTime, baseFallTime * pow(0.9, Double(max(0, wave - 1)))) * difficulty.timeScale
    }

    /// Combo multiplier: ×1 for the first three hits in a row, ×2 for the next three, up to ×4.
    public static func multiplier(combo: Int) -> Int {
        min(4, 1 + max(0, combo - 1) / 3)
    }

    // Speed ramp (#178, retuned in #194). A game opens `rampStartOffset` WPM
    // under the learner's character speed — never under `minWpm`, the
    // app-wide character-speed floor — and climbs towards the character
    // speed only as the learner consolidates: `hitsPerRampStep` hits in a row
    // at the current speed step it up `rampStepUp` WPM. A wrong shot resets
    // that streak and leaves the speed alone; a landing resets it and steps
    // the speed down `rampStepDown`, never under the start. After every
    // change of speed the first `rampHold` hits settle — they count for
    // nothing, so a fumble while adjusting to the new speed costs nothing
    // either. Farnsworth is ignored: a single character has no gaps to
    // stretch. Pinned by fixtures/invaders-ramp.json on both ports.
    public static let minWpm = 15.0
    public static let rampStartOffset = 10.0
    public static let rampStepUp = 1.0
    public static let rampStepDown = 2.0
    public static let hitsPerRampStep = 6
    public static let rampHold = 3

    /// The speed a game starts at for a character speed: 10 WPM under it,
    /// floored at 15. At or under 15 there is no ramp.
    public static func rampStart(characterWpm: Double) -> Double {
        max(minWpm, characterWpm - rampStartOffset)
    }

    // Adaptive character weighting (#194). A character the learner misses —
    // an invader that lands, or a wrong shot made while it was on the lowest
    // invader — is owed `debtPerMiss` hits, up to `maxMissDebt`; every hit on
    // it pays one off. Its spawn weight is 1 + `missWeightBonus` × the debt,
    // so a missed character comes round more often until it has been hit
    // twice more than missed, then is back to normal. Per game; nothing
    // persists. Pinned by fixtures/invaders-ramp.json on both ports.
    public static let debtPerMiss = 2
    public static let maxMissDebt = 6
    public static let missWeightBonus = 0.5

    /// The spawn weight of a character owed `debt` hits: 1 when owed nothing.
    public static func spawnWeight(debt: Int) -> Double {
        1 + missWeightBonus * Double(max(0, debt))
    }

    /// The index a roll in 0..<1 lands on when `weights` are laid end to end:
    /// the first whose running total exceeds `roll` × the sum. A roll at or
    /// past 1 lands on the last. Pure, so the fixture can pin the decision
    /// without pinning either port's generator.
    public static func pick(weights: [Double], roll: Double) -> Int {
        let x = roll * weights.reduce(0, +)
        var total = 0.0
        for (i, w) in weights.enumerated() {
            total += w
            if x < total { return i }
        }
        return max(0, weights.count - 1)
    }

    public let config: Config
    private var rng: any RandomNumberGenerator
    private let pool: [Character]

    public private(set) var invaders: [Invader] = []
    public private(set) var score = 0
    public private(set) var wave = 1
    public private(set) var lives: Int
    /// Consecutive hits since the last miss or escape.
    public private(set) var combo = 0
    public private(set) var bestCombo = 0
    public private(set) var hits = 0
    /// Wrong shots plus invaders that reached the ground.
    public private(set) var misses = 0
    /// Game time in seconds, the sum of every `advance(by:)`.
    public private(set) var elapsed = 0.0
    public private(set) var isOver = false
    /// The speed invaders are sent at now (#178): starts at `config.startWpm`,
    /// climbs with hits and falls back with landings.
    public private(set) var currentWpm: Double
    /// The highest speed the ramp reached this game.
    public private(set) var bestWpm: Double
    /// Hits in a row at the current speed since the last change of speed,
    /// wrong shot or landing (#194); `hitsPerRampStep` of them step it up.
    public private(set) var rampStreak = 0
    /// Settling hits still to come after the last change of speed (#194);
    /// they count for nothing.
    public private(set) var rampSettling = 0

    /// Hits each character is owed after being missed (#194); absent is 0.
    private var missDebt: [Character: Int] = [:]

    private var waveHits = 0
    private var sinceSpawn = 0.0
    private var nextId = 1
    private var lastColumn = -1

    public init(config: Config,
                rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.config = config
        self.rng = rng
        self.lives = max(1, config.lives)
        self.currentWpm = config.startWpm
        self.bestWpm = config.startWpm
        var seen = Set<Character>()
        let upper = config.characters
            .map { Character(String($0).uppercased()) }
            .filter { seen.insert($0).inserted }
        self.pool = upper.isEmpty ? Array(MorseCode.kochOrder.prefix(2)) : upper
    }

    /// Seconds between spawns at the current wave.
    public var spawnInterval: Double { Self.spawnInterval(wave: wave, difficulty: config.difficulty) }

    /// Seconds a fresh invader takes to reach the ground at the current wave.
    public var fallTime: Double { Self.fallTime(wave: wave, difficulty: config.difficulty) }

    /// Hits over every shot and escape, 0…1.
    public var accuracy: Double {
        hits + misses == 0 ? 0 : Double(hits) / Double(hits + misses)
    }

    /// The invader nearest the ground, if any.
    public var lowest: Invader? { invaders.max { $0.progress < $1.progress } }

    /// The multiplier the next hit earns.
    public var multiplier: Int { Self.multiplier(combo: combo + 1) }

    /// Every pool character's spawn weight right now (#194): 1 unless it is
    /// owed hits.
    public var spawnWeights: [Character: Double] {
        Dictionary(uniqueKeysWithValues: pool.map { ($0, Self.spawnWeight(debt: missDebt[$0] ?? 0)) })
    }

    /// Move time forward by `seconds`: invaders fall, any that reach the ground
    /// cost a life, and the spawn clock releases new ones. Returns what
    /// happened, in order. A finished game ignores time.
    @discardableResult
    public func advance(by seconds: Double) -> [InvadersEvent] {
        guard !isOver, seconds > 0 else { return [] }
        var events: [InvadersEvent] = []
        elapsed += seconds

        var moved = invaders
        for i in moved.indices { moved[i].progress += seconds / moved[i].fallTime }
        let escaped = moved.filter { $0.progress >= 1.0 }
        invaders = moved.filter { $0.progress < 1.0 }
        for var e in escaped {
            lives = max(0, lives - 1)
            misses += 1
            combo = 0
            noteMissed(e.character)
            changeSpeed(to: max(config.startWpm, currentWpm - Self.rampStepDown))
            e.progress = 1.0
            events.append(.escaped(e))
        }
        if lives == 0 {
            isOver = true
            invaders = []
            events.append(.gameOver)
            return events
        }

        sinceSpawn += seconds
        while sinceSpawn >= spawnInterval {
            sinceSpawn -= spawnInterval
            // Born partway down when the step overshot its spawn time, so a
            // long frame does not gift the invader extra hang time.
            let invader = spawn(progress: sinceSpawn / fallTime)
            events.append(.spawned(invader))
        }
        return events
    }

    /// Name a character. Hits the lowest invader carrying it and scores
    /// `pointsPerHit` times the combo multiplier; with none on the field it is
    /// a miss that breaks the combo. A finished game ignores shots.
    @discardableResult
    public func shoot(_ character: Character) -> InvadersShot {
        guard !isOver else { return InvadersShot(invader: nil, points: 0, waveCleared: false) }
        let c = Character(String(character).uppercased())
        guard let target = invaders.filter({ $0.character == c }).max(by: { $0.progress < $1.progress }) else {
            combo = 0
            misses += 1
            rampStreak = 0
            // The character missed is the one nearest the ground — the one the
            // learner was presumably naming, and the one the views record
            // against the confusion matrix.
            if let lowest { noteMissed(lowest.character) }
            return InvadersShot(invader: nil, points: 0, waveCleared: false)
        }
        invaders.removeAll { $0.id == target.id }
        combo += 1
        bestCombo = max(bestCombo, combo)
        let points = Self.pointsPerHit * Self.multiplier(combo: combo)
        score += points
        hits += 1
        noteHit(c)
        if rampSettling > 0 {
            rampSettling -= 1
        } else {
            rampStreak += 1
            if rampStreak >= Self.hitsPerRampStep {
                changeSpeed(to: min(config.targetWpm, currentWpm + Self.rampStepUp))
            }
        }
        waveHits += 1
        var cleared = false
        if waveHits >= max(1, config.hitsPerWave) {
            wave += 1
            waveHits = 0
            cleared = true
        }
        return InvadersShot(invader: target, points: points, waveCleared: cleared)
    }

    /// Moves the ramp to `wpm`: the streak restarts either way, and a change
    /// of speed opens a hold of `rampHold` settling hits.
    private func changeSpeed(to wpm: Double) {
        rampStreak = 0
        guard wpm != currentWpm else { return }
        currentWpm = wpm
        bestWpm = max(bestWpm, wpm)
        rampSettling = Self.rampHold
    }

    private func noteMissed(_ c: Character) {
        missDebt[c] = min(Self.maxMissDebt, (missDebt[c] ?? 0) + Self.debtPerMiss)
    }

    private func noteHit(_ c: Character) {
        guard let debt = missDebt[c] else { return }
        missDebt[c] = debt > 1 ? debt - 1 : nil
    }

    private func spawn(progress: Double) -> Invader {
        // Prefer a character not already on the field, so "the lowest one
        // carrying it" is usually the only one; a two-character pool repeats.
        // Among those, a character the learner has been missing is weighted
        // heavier (#194): one roll, then `pick` over the weights.
        let onField = Set(invaders.map(\.character))
        let fresh = pool.filter { !onField.contains($0) }
        let choices = fresh.isEmpty ? pool : fresh
        let weights = choices.map { Self.spawnWeight(debt: missDebt[$0] ?? 0) }
        let character = choices[Self.pick(weights: weights, roll: Double.random(in: 0..<1, using: &rng))]
        let columns = max(1, config.columns)
        let column: Int
        if columns == 1 {
            column = 0
        } else {
            let open = (0..<columns).filter { $0 != lastColumn }
            column = open[Int.random(in: 0..<open.count, using: &rng)]
        }
        lastColumn = column
        let invader = Invader(id: nextId, character: character, column: column,
                              progress: progress, fallTime: fallTime)
        nextId += 1
        invaders.append(invader)
        return invader
    }
}

/// The hear-it/type-it on-screen keyboard (#178): a QWERTY layout, so the
/// reflex being trained is the one a real keyboard rewards. The digit row
/// leads when the pool has any digit, the three letter rows are always there,
/// and anything else in the pool (punctuation, prosigns) makes a final row in
/// pool order without repeats. A key outside the pool stays in its row — the
/// view shows it dimmed and dead — so the layout never shifts as the Koch set
/// grows. Pinned by fixtures/invaders-ramp.json; twin of the Kotlin
/// `InvadersKeyboard`.
public enum InvadersKeyboard {
    public static let digitRow: [Character] = Array("1234567890")
    public static let letterRows: [[Character]] = [
        Array("QWERTYUIOP"),
        Array("ASDFGHJKL"),
        Array("ZXCVBNM"),
    ]

    /// The rows to lay out for `pool`, top to bottom.
    public static func rows(for pool: [Character]) -> [[Character]] {
        let upper = pool.map { Character(String($0).uppercased()) }
        let standard = Set(digitRow + letterRows.flatMap { $0 })
        var rows: [[Character]] = []
        if upper.contains(where: { digitRow.contains($0) }) { rows.append(digitRow) }
        rows.append(contentsOf: letterRows)
        var seen = Set<Character>()
        let extras = upper.filter { !standard.contains($0) && seen.insert($0).inserted }
        if !extras.isEmpty { rows.append(extras) }
        return rows
    }
}
