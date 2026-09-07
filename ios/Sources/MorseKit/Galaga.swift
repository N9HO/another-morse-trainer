import Foundation

// CW Galaga (#187): enemies fly in along curved entry paths and settle into a
// formation at the top of the field; from there they dive at the player one
// at a time and climb back if not shot. The learner shoots an enemy by naming
// the character it carries — typing it after hearing it (copy) or keying it
// after seeing it (send) — and consecutive hits build a combo multiplier.
// Pure game rules — no clock, no audio, no UI. Time enters through
// `GalagaGame.advance(by:)`, so the view drives it from a frame clock and the
// harness from arithmetic; every random choice comes from the injected
// generator, so a seed pins the sequence. Paths are unit-space Béziers in
// `GalagaPath`, so both views draw the same swoop from the same `progress`.
//
// Pinned by fixtures/galaga.json on both ports. Twin of the Kotlin
// `morsekit/Galaga.kt`; the two must stay in step. The input, difficulty and
// character-set choices are `InvadersInput`, `InvadersDifficulty` and
// `InvadersCharacterSet` (Invaders.swift), shared across the arcade games.

/// Where an enemy is in its flight.
public enum GalagaEnemyState: String, Equatable, Sendable {
    /// Flying its entry path; `progress` 0 at the field edge, 1 at its slot.
    case entering
    /// Holding its formation slot.
    case formed
    /// Diving at the player; `progress` 0 at the slot, 1 at the bottom.
    case diving
    /// Climbing back to its slot after a dive that landed; `progress` 0 at the bottom, 1 at the slot.
    case returning
}

/// One enemy on the field.
public struct GalagaEnemy: Identifiable, Equatable, Sendable {
    public let id: Int
    public let character: Character
    /// Its formation slot; row 0 is the top row.
    public let row: Int
    public let column: Int
    /// Which side of the field its entry path starts from.
    public let fromLeft: Bool
    public var state: GalagaEnemyState
    /// 0…1 along the current leg (entry, dive or return); meaningless when formed.
    public var progress: Double
    /// Seconds the current leg takes, fixed when the leg starts so a wave
    /// change mid-flight does not jolt the ones already flying.
    public var legTime: Double

    public init(id: Int, character: Character, row: Int, column: Int, fromLeft: Bool,
                state: GalagaEnemyState, progress: Double, legTime: Double) {
        self.id = id
        self.character = character
        self.row = row
        self.column = column
        self.fromLeft = fromLeft
        self.state = state
        self.progress = progress
        self.legTime = legTime
    }

    /// Off its slot: diving or returning.
    public var isOffSlot: Bool { state == .diving || state == .returning }
}

/// A wave's formation grid.
public struct GalagaFormation: Equatable, Sendable {
    public let rows: Int
    public let columns: Int

    public init(rows: Int, columns: Int) {
        self.rows = rows
        self.columns = columns
    }

    public var size: Int { rows * columns }
}

/// What one call to `GalagaGame.advance(by:)` did, in order.
public enum GalagaEvent: Equatable, Sendable {
    /// Released onto its entry path: the moment to send its character.
    case entered(GalagaEnemy)
    /// Peeled off its slot into a dive: sent again, since it is the threat now.
    case dived(GalagaEnemy)
    /// The dive reached the bottom: a life lost; the enemy is returning.
    case landed(GalagaEnemy)
    case gameOver
}

/// The outcome of one shot. `enemy` is the one hit (nil on a miss), `points`
/// what the hit scored (the wave bonus not included), and `waveCleared`
/// whether that hit finished the wave.
public struct GalagaShot: Equatable, Sendable {
    public let enemy: GalagaEnemy?
    public let points: Int
    public let waveCleared: Bool

    public init(enemy: GalagaEnemy?, points: Int, waveCleared: Bool) {
        self.enemy = enemy
        self.points = points
        self.waveCleared = waveCleared
    }

    public var isHit: Bool { enemy != nil }
}

public final class GalagaGame {

    public struct Config: Sendable, Equatable {
        /// The characters enemies carry; empty falls back to the first two Koch characters.
        public var characters: [Character]
        public var difficulty: InvadersDifficulty
        public var lives: Int
        /// The learner's character speed: where the speed ramp ends.
        public var characterWpm: Double

        public init(characters: [Character],
                    difficulty: InvadersDifficulty = .normal,
                    lives: Int = 3,
                    characterWpm: Double = 20) {
            self.characters = characters
            self.difficulty = difficulty
            self.lives = lives
            self.characterWpm = characterWpm
        }

        /// Where a game's speed starts: `GalagaGame.rampStart(characterWpm:)`.
        public var startWpm: Double { GalagaGame.rampStart(characterWpm: characterWpm) }

        /// Where the ramp ends: the character speed, never under the floor.
        public var targetWpm: Double { max(GalagaGame.minWpm, characterWpm) }
    }

    // Scoring.
    public static let pointsPerHit = 100
    /// Added to `pointsPerHit` when the enemy hit was diving.
    public static let diveBonus = 50
    /// Added to the score when the last enemy of a wave goes down.
    public static let waveBonus = 500
    /// Consecutive hits per multiplier step.
    public static let comboStep = 3
    public static let maxMultiplier = 8

    /// Combo multiplier: ×1 for the first three hits in a row, ×2 for the
    /// next three, and so on up to ×8.
    public static func multiplier(combo: Int) -> Int {
        min(maxMultiplier, 1 + max(0, combo - 1) / comboStep)
    }

    // Formation. Grows with the wave to a 4×6 block.
    public static let maxRows = 4
    public static let maxColumns = 6

    public static func formation(wave: Int) -> GalagaFormation {
        let w = max(1, wave)
        return GalagaFormation(rows: min(maxRows, 1 + (w + 1) / 2),
                               columns: min(maxColumns, 3 + w))
    }

    /// How many enemies may be off their slot (diving or returning) at once.
    public static func maxDivers(wave: Int) -> Int {
        min(3, 1 + (max(1, wave) - 1) / 2)
    }

    // Timings, in seconds on Normal: each is base × decay^(wave − 1), floored,
    // then scaled by the difficulty. The wave gap is not scaled.
    public static let baseEntryInterval = 1.4
    public static let entryIntervalDecay = 0.92
    public static let minEntryInterval = 0.5
    public static let baseEntryTime = 2.4
    public static let entryTimeDecay = 0.94
    public static let minEntryTime = 1.2
    public static let baseDiveInterval = 3.0
    public static let diveIntervalDecay = 0.90
    public static let minDiveInterval = 1.0
    public static let baseDiveTime = 4.0
    public static let diveTimeDecay = 0.92
    public static let minDiveTime = 1.8
    /// The return leg takes this fraction of the dive.
    public static let returnFactor = 0.5
    /// Seconds between a wave's last hit and the next wave's first entry.
    public static let waveGap = 1.5

    private static func decayed(_ base: Double, _ decay: Double, floor: Double,
                                wave: Int, difficulty: InvadersDifficulty) -> Double {
        max(floor, base * pow(decay, Double(max(0, wave - 1)))) * difficulty.timeScale
    }

    /// Seconds between entries.
    public static func entryInterval(wave: Int, difficulty: InvadersDifficulty) -> Double {
        decayed(baseEntryInterval, entryIntervalDecay, floor: minEntryInterval, wave: wave, difficulty: difficulty)
    }

    /// Seconds an entry path takes.
    public static func entryTime(wave: Int, difficulty: InvadersDifficulty) -> Double {
        decayed(baseEntryTime, entryTimeDecay, floor: minEntryTime, wave: wave, difficulty: difficulty)
    }

    /// Seconds between dives while the dive clock runs.
    public static func diveInterval(wave: Int, difficulty: InvadersDifficulty) -> Double {
        decayed(baseDiveInterval, diveIntervalDecay, floor: minDiveInterval, wave: wave, difficulty: difficulty)
    }

    /// Seconds a dive takes from the slot to the bottom.
    public static func diveTime(wave: Int, difficulty: InvadersDifficulty) -> Double {
        decayed(baseDiveTime, diveTimeDecay, floor: minDiveTime, wave: wave, difficulty: difficulty)
    }

    /// Seconds the climb back to the slot takes after a dive lands.
    public static func returnTime(wave: Int, difficulty: InvadersDifficulty) -> Double {
        diveTime(wave: wave, difficulty: difficulty) * returnFactor
    }

    // Speed ramp — gentler than Invaders' (#194): a game opens
    // `rampStartOffset` WPM under the learner's character speed, never under
    // `minWpm`, and climbs `rampStep` WPM every `hitsPerRampStep` hits in
    // total up to the character speed; a landed dive steps it back, never
    // under the start. Wrong shots leave it alone. Farnsworth is ignored: a
    // single character has no gaps to stretch.
    public static let minWpm = 15.0
    public static let rampStartOffset = 8.0
    public static let rampStep = 1.0
    public static let hitsPerRampStep = 6

    /// The speed a game starts at for a character speed: 8 WPM under it,
    /// floored at 15. At or under 15 there is no ramp.
    public static func rampStart(characterWpm: Double) -> Double {
        max(minWpm, characterWpm - rampStartOffset)
    }

    public let config: Config
    private var rng: any RandomNumberGenerator
    private let pool: [Character]

    public private(set) var enemies: [GalagaEnemy] = []
    public private(set) var score = 0
    public private(set) var wave = 1
    public private(set) var lives: Int
    /// Consecutive hits since the last miss or landed dive.
    public private(set) var combo = 0
    public private(set) var bestCombo = 0
    public private(set) var hits = 0
    /// Wrong shots plus dives that landed.
    public private(set) var misses = 0
    /// Game time in seconds, the sum of every `advance(by:)`.
    public private(set) var elapsed = 0.0
    public private(set) var isOver = false
    /// The speed enemies are sent at now: starts at `config.startWpm`,
    /// climbs with hits and falls back with landed dives.
    public private(set) var currentWpm: Double
    /// The highest speed the ramp reached this game.
    public private(set) var bestWpm: Double
    /// How many of this wave's formation have been released so far.
    public private(set) var released = 0

    private var sinceEntry: Double
    private var sinceDive = 0.0
    private var nextId = 1

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
        // The first entry comes one interval in, as every later one does.
        self.sinceEntry = 0
    }

    /// This wave's formation grid.
    public var formation: GalagaFormation { Self.formation(wave: wave) }

    public var entryInterval: Double { Self.entryInterval(wave: wave, difficulty: config.difficulty) }
    public var entryTime: Double { Self.entryTime(wave: wave, difficulty: config.difficulty) }
    public var diveInterval: Double { Self.diveInterval(wave: wave, difficulty: config.difficulty) }
    public var diveTime: Double { Self.diveTime(wave: wave, difficulty: config.difficulty) }
    public var returnTime: Double { Self.returnTime(wave: wave, difficulty: config.difficulty) }
    public var maxDivers: Int { Self.maxDivers(wave: wave) }

    /// Hits over every shot and landed dive, 0…1.
    public var accuracy: Double {
        hits + misses == 0 ? 0 : Double(hits) / Double(hits + misses)
    }

    /// The multiplier the next hit earns.
    public var multiplier: Int { Self.multiplier(combo: combo + 1) }

    /// How urgently an enemy needs shooting: a diver (the further down the
    /// better), then a returning one (the lower the better), then a formed
    /// one on the lowest row, then one still entering.
    public static func threat(of enemy: GalagaEnemy) -> Double {
        switch enemy.state {
        case .diving:    return 3 + enemy.progress
        case .returning: return 2 + (1 - enemy.progress)
        case .formed:    return 1 + Double(enemy.row) / 100
        case .entering:  return enemy.progress
        }
    }

    /// The enemy most in need of shooting, if any; ties go to the earlier release.
    public var mostThreatening: GalagaEnemy? {
        enemies.max { Self.threat(of: $0) < Self.threat(of: $1) }
    }

    /// Move time forward by `seconds`: enemies fly their legs, a dive that
    /// reaches the bottom costs a life, the entry clock releases the next of
    /// the formation and the dive clock sends one down. Returns what
    /// happened, in order. A finished game ignores time.
    @discardableResult
    public func advance(by seconds: Double) -> [GalagaEvent] {
        guard !isOver, seconds > 0 else { return [] }
        var events: [GalagaEvent] = []
        elapsed += seconds

        for i in enemies.indices where enemies[i].state != .formed {
            enemies[i].progress += seconds / enemies[i].legTime
            guard enemies[i].progress >= 1.0 else { continue }
            switch enemies[i].state {
            case .entering, .returning:
                enemies[i].state = .formed
                enemies[i].progress = 0
            case .diving:
                lives = max(0, lives - 1)
                misses += 1
                combo = 0
                currentWpm = max(config.startWpm, currentWpm - Self.rampStep)
                var landed = enemies[i]
                landed.progress = 1.0
                events.append(.landed(landed))
                enemies[i].state = .returning
                enemies[i].progress = 0
                enemies[i].legTime = returnTime
            case .formed:
                break
            }
        }
        if lives == 0 {
            isOver = true
            enemies = []
            events.append(.gameOver)
            return events
        }

        if released < formation.size {
            sinceEntry += seconds
            while sinceEntry >= entryInterval && released < formation.size {
                sinceEntry -= entryInterval
                // Born partway along when the step overshot its release
                // time, so a long frame does not gift it extra hang time.
                events.append(.entered(release(progress: sinceEntry / entryTime)))
            }
        }

        // The dive clock runs only while a dive is possible, so the first
        // dive comes one interval after the first enemy settles.
        let offSlot = enemies.filter(\.isOffSlot).count
        let formed = enemies.filter { $0.state == .formed }
        if !formed.isEmpty && offSlot < maxDivers {
            sinceDive += seconds
            if sinceDive >= diveInterval {
                sinceDive = 0
                let pick = formed[Int.random(in: 0..<formed.count, using: &rng)]
                if let i = enemies.firstIndex(where: { $0.id == pick.id }) {
                    enemies[i].state = .diving
                    enemies[i].progress = 0
                    enemies[i].legTime = diveTime
                    events.append(.dived(enemies[i]))
                }
            }
        }
        return events
    }

    /// Name a character. Hits the most threatening enemy carrying it and
    /// scores `pointsPerHit` (plus `diveBonus` for a diver) times the combo
    /// multiplier; with none on the field it is a miss that breaks the combo.
    /// The last enemy of a fully released formation clears the wave and adds
    /// `waveBonus`. A finished game ignores shots.
    @discardableResult
    public func shoot(_ character: Character) -> GalagaShot {
        guard !isOver else { return GalagaShot(enemy: nil, points: 0, waveCleared: false) }
        let c = Character(String(character).uppercased())
        guard let target = enemies.filter({ $0.character == c })
            .max(by: { Self.threat(of: $0) < Self.threat(of: $1) }) else {
            combo = 0
            misses += 1
            return GalagaShot(enemy: nil, points: 0, waveCleared: false)
        }
        enemies.removeAll { $0.id == target.id }
        combo += 1
        bestCombo = max(bestCombo, combo)
        let base = Self.pointsPerHit + (target.state == .diving ? Self.diveBonus : 0)
        let points = base * Self.multiplier(combo: combo)
        score += points
        hits += 1
        if hits % Self.hitsPerRampStep == 0 {
            currentWpm = min(config.targetWpm, currentWpm + Self.rampStep)
            bestWpm = max(bestWpm, currentWpm)
        }
        var cleared = false
        if enemies.isEmpty && released >= formation.size {
            score += Self.waveBonus
            wave += 1
            released = 0
            sinceEntry = -Self.waveGap
            sinceDive = 0
            cleared = true
        }
        return GalagaShot(enemy: target, points: points, waveCleared: cleared)
    }

    private func release(progress: Double) -> GalagaEnemy {
        // Prefer a character not already on the field, so "the most
        // threatening one carrying it" is usually the only one; a
        // two-character pool repeats.
        let onField = Set(enemies.map(\.character))
        let fresh = pool.filter { !onField.contains($0) }
        let choices = fresh.isEmpty ? pool : fresh
        let character = choices[Int.random(in: 0..<choices.count, using: &rng)]
        let slot = released
        let enemy = GalagaEnemy(id: nextId, character: character,
                                row: slot / formation.columns, column: slot % formation.columns,
                                fromLeft: slot % 2 == 0,
                                state: .entering, progress: progress, legTime: entryTime)
        nextId += 1
        released += 1
        enemies.append(enemy)
        return enemy
    }
}

/// The flight paths, in unit field coordinates: x 0…1 left to right, y 0 at
/// the top and 1 at the bottom where the player sits. Both views scale these
/// to their canvas, so the swoop is the same on either app. Pinned by
/// fixtures/galaga.json; twin of the Kotlin `GalagaPath`.
public enum GalagaPath {
    public struct Point: Equatable, Sendable {
        public let x: Double
        public let y: Double
        public init(x: Double, y: Double) { self.x = x; self.y = y }
    }

    /// Where the top row sits, and the spacing between rows.
    public static let formationTop = 0.10
    public static let rowPitch = 0.11

    /// The centre of a formation slot.
    public static func slot(row: Int, column: Int, columns: Int) -> Point {
        Point(x: (Double(column) + 0.5) / Double(max(1, columns)),
              y: formationTop + Double(row) * rowPitch)
    }

    /// The entry swoop: in from the side at mid-height, down towards the
    /// bottom, up past the far top corner and into the slot.
    public static func entry(t: Double, fromLeft: Bool, slot: Point) -> Point {
        cubic(t,
              Point(x: fromLeft ? -0.08 : 1.08, y: 0.5),
              Point(x: fromLeft ? 0.25 : 0.75, y: 1.05),
              Point(x: fromLeft ? 1.0 : 0.0, y: 0.0),
              slot)
    }

    /// The dive: down from the slot, drifting across to land on the mirrored x.
    public static func dive(t: Double, slot: Point) -> Point {
        cubic(t,
              slot,
              Point(x: slot.x, y: slot.y + 0.4),
              Point(x: 1 - slot.x, y: 0.6),
              Point(x: 1 - slot.x, y: 1.0))
    }

    /// Where an enemy is right now, from its state and progress.
    public static func position(of enemy: GalagaEnemy, columns: Int) -> Point {
        let s = slot(row: enemy.row, column: enemy.column, columns: columns)
        let t = min(1, max(0, enemy.progress))
        switch enemy.state {
        case .entering:  return entry(t: t, fromLeft: enemy.fromLeft, slot: s)
        case .formed:    return s
        case .diving:    return dive(t: t, slot: s)
        case .returning: return dive(t: 1 - t, slot: s)
        }
    }

    static func cubic(_ t: Double, _ p0: Point, _ p1: Point, _ p2: Point, _ p3: Point) -> Point {
        let u = 1 - t
        let a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, d = t * t * t
        return Point(x: a * p0.x + b * p1.x + c * p2.x + d * p3.x,
                     y: a * p0.y + b * p1.y + c * p2.y + d * p3.y)
    }
}
