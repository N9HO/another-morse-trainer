import Foundation

// CW Frogger (#190, part of #170): a frog crosses three lanes of traffic, a
// median and three lanes of river to the far bank. Movement is classic
// Frogger — a hop at a time in four directions — and Morse decides what is
// safe: every vehicle and log carries a character from the learner's set, and
// each lane is cued by sending one of them in Morse. On the road the cued
// vehicle is harmless and every other one is fatal; on the river only the cued
// log floats. Pure game rules — no clock, no audio, no UI. Time enters through
// `FroggerGame.advance(by:)`, randomness through the injected generator (which
// picks labels and cues only — object positions are laid out evenly, so a
// scripted crossing is reproducible on both ports).
//
// Twin of the Kotlin `morsekit/Frogger.kt`; the two must stay in step, and
// fixtures/frogger.json pins both.

/// A hop. Up is toward the goal bank.
public enum FroggerDirection: String, CaseIterable, Sendable {
    case up, down, left, right
}

/// What a lane carries.
public enum FroggerLaneKind: String, Sendable {
    case road, river
}

/// How much the labels help, by wave: every label shown; a lane's labels
/// hidden once it has been cued (cross from memory); no labels at all, the
/// objects announcing themselves in Morse as they enter.
public enum FroggerLabelStage: String, Sendable {
    case visible, memory, hidden
}

/// One lane's layout: where it is, which way it flows, and how its objects
/// are sized and spaced. `baseSpeed` is board widths per second at wave 1 on
/// Normal.
public struct FroggerLane: Equatable, Sendable {
    public let row: Int
    public let kind: FroggerLaneKind
    /// +1 flows right, -1 flows left.
    public let direction: Int
    public let count: Int
    public let width: Double
    public let baseSpeed: Double

    public init(row: Int, kind: FroggerLaneKind, direction: Int, count: Int, width: Double, baseSpeed: Double) {
        self.row = row
        self.kind = kind
        self.direction = direction
        self.count = count
        self.width = width
        self.baseSpeed = baseSpeed
    }
}

/// A vehicle or a log. `x` is its centre across the board, 0…1 wrapping;
/// `width` its span in the same units.
public struct FroggerObject: Identifiable, Equatable, Sendable {
    public let id: Int
    public let row: Int
    public let kind: FroggerLaneKind
    public let character: Character
    public var x: Double
    public let width: Double

    public init(id: Int, row: Int, kind: FroggerLaneKind, character: Character, x: Double, width: Double) {
        self.id = id
        self.row = row
        self.kind = kind
        self.character = character
        self.x = x
        self.width = width
    }
}

/// Where the frog is: its row (0 the start bank) and centre across the board.
public struct FroggerFrog: Equatable, Sendable {
    public var row: Int
    public var x: Double

    public init(row: Int, x: Double) {
        self.row = row
        self.x = x
    }
}

/// What one call to `advance(by:)` or `move(_:)` did, in order.
public enum FroggerEvent: Equatable, Sendable {
    /// The frog moved.
    case hopped
    /// A lane got its cue: the character to send for `row`.
    case cue(row: Int, character: Character)
    /// A cued vehicle passed through the frog — a correct decision.
    case passed(FroggerObject, points: Int)
    /// The frog landed on a cued log — a correct decision.
    case landed(FroggerObject, points: Int)
    /// A vehicle not carrying the cue hit the frog.
    case squashed(FroggerObject, cue: Character)
    /// The frog landed on a log not carrying the cue.
    case sank(FroggerObject, cue: Character)
    /// The frog landed in the water.
    case drowned(cue: Character)
    /// The frog reached the goal bank; the next wave has begun.
    case crossed(points: Int)
    /// An object wrapped round and entered the board again.
    case entered(FroggerObject)
    case gameOver
}

public final class FroggerGame {

    public struct Config: Sendable, Equatable {
        /// The characters the objects carry; empty falls back to the first two Koch characters.
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

        public var startWpm: Double { FroggerGame.rampStart(characterWpm: characterWpm) }
        public var targetWpm: Double { max(FroggerGame.minWpm, characterWpm) }
    }

    // The board. Rows count up from the start bank; the lanes are laid out so
    // each section gets faster toward the middle of the crossing.
    public static let rows = 9
    public static let startRow = 0
    public static let medianRow = 4
    public static let goalRow = 8
    /// Sideways hops across the board.
    public static let columns = 7
    public static var hop: Double { 1.0 / Double(columns) }
    /// Half the frog's width for road contact, in board widths.
    public static let frogHalfWidth = 0.04
    public static let lanes: [FroggerLane] = [
        FroggerLane(row: 1, kind: .road,  direction:  1, count: 3, width: 0.12, baseSpeed: 0.16),
        FroggerLane(row: 2, kind: .road,  direction: -1, count: 3, width: 0.12, baseSpeed: 0.20),
        FroggerLane(row: 3, kind: .road,  direction:  1, count: 3, width: 0.12, baseSpeed: 0.24),
        FroggerLane(row: 5, kind: .river, direction: -1, count: 3, width: 0.18, baseSpeed: 0.14),
        FroggerLane(row: 6, kind: .river, direction:  1, count: 3, width: 0.18, baseSpeed: 0.18),
        FroggerLane(row: 7, kind: .river, direction: -1, count: 3, width: 0.18, baseSpeed: 0.22),
    ]

    // Speeds tighten 8% a wave up to a cap, then difficulty stretches or
    // compresses the whole board.
    public static let waveSpeedGrowth = 1.08
    public static let maxLaneSpeed = 0.5

    public static let pointsPerHop = 10
    public static let pointsPerDecision = 50
    public static let pointsPerCrossing = 200

    /// The difficulty ladder: labels shown through wave 2, hidden once cued
    /// from wave 3, gone (objects announce themselves) from wave 5.
    public static let memoryFromWave = 3
    public static let hiddenFromWave = 5

    // Speed ramp, the game's own constants (Invaders has its own): open 8 WPM
    // under the character speed, never under the app-wide floor, step up 2
    // every four correct decisions in total, step back 2 on a death, never
    // under the start.
    public static let minWpm = 15.0
    public static let rampStartOffset = 8.0
    public static let rampStep = 2.0
    public static let decisionsPerRampStep = 4

    /// A lane's speed in board widths per second for a wave and difficulty.
    public static func laneSpeed(baseSpeed: Double, wave: Int, difficulty: InvadersDifficulty) -> Double {
        min(maxLaneSpeed, baseSpeed * pow(waveSpeedGrowth, Double(max(0, wave - 1)))) / difficulty.timeScale
    }

    /// Combo multiplier: ×1 for the first three decisions in a row, ×2 for the next three, up to ×4.
    public static func multiplier(combo: Int) -> Int {
        min(4, 1 + max(0, combo - 1) / 3)
    }

    public static func labelStage(wave: Int) -> FroggerLabelStage {
        if wave >= hiddenFromWave { return .hidden }
        if wave >= memoryFromWave { return .memory }
        return .visible
    }

    public static func rampStart(characterWpm: Double) -> Double {
        max(minWpm, characterWpm - rampStartOffset)
    }

    public static func lane(at row: Int) -> FroggerLane? {
        lanes.first { $0.row == row }
    }

    /// The shorter way round between two positions on the wrapping board.
    public static func wrappedDistance(_ a: Double, _ b: Double) -> Double {
        let d = abs(a - b)
        return min(d, 1 - d)
    }

    public let config: Config
    private var rng: any RandomNumberGenerator
    private let pool: [Character]

    public private(set) var objects: [FroggerObject] = []
    public private(set) var frog = FroggerFrog(row: FroggerGame.startRow, x: 0.5)
    /// The log the frog is riding, if any.
    public private(set) var ridingId: Int?
    /// The cue for each cued lane this crossing, by row.
    public private(set) var cues: [Int: Character] = [:]
    public private(set) var score = 0
    public private(set) var wave = 1
    public private(set) var lives: Int
    /// Consecutive correct decisions since the last death.
    public private(set) var combo = 0
    public private(set) var bestCombo = 0
    /// Correct decisions: cued vehicles passed through, cued logs landed on.
    public private(set) var decisions = 0
    /// Deaths: wrong vehicle, wrong log, or water.
    public private(set) var misses = 0
    public private(set) var crossings = 0
    /// Game time in seconds, the sum of every `advance(by:)`.
    public private(set) var elapsed = 0.0
    public private(set) var isOver = false
    public private(set) var currentWpm: Double
    public private(set) var bestWpm: Double

    private var maxRowThisCrossing = 0
    /// Cued vehicles currently overlapping the frog, already credited.
    private var passingIds: Set<Int> = []
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
        layOutLanes()
        assignCue(row: Self.startRow + 1)
    }

    public var labelStage: FroggerLabelStage { Self.labelStage(wave: wave) }

    /// Whether `row`'s labels are drawn at this wave.
    public func isLabelVisible(row: Int) -> Bool {
        switch labelStage {
        case .visible: return true
        case .memory:  return cues[row] == nil
        case .hidden:  return false
        }
    }

    /// The cue for the lane above the frog, if that is a lane.
    public var nextCue: Character? { cues[frog.row + 1] }

    /// The row the next cue is for, if the frog has a lane above it.
    public var nextCueRow: Int? { Self.lane(at: frog.row + 1) == nil ? nil : frog.row + 1 }

    public func speed(row: Int) -> Double {
        guard let lane = Self.lane(at: row) else { return 0 }
        return Self.laneSpeed(baseSpeed: lane.baseSpeed, wave: wave, difficulty: config.difficulty)
    }

    /// Correct decisions over decisions and deaths, 0…1.
    public var accuracy: Double {
        decisions + misses == 0 ? 0 : Double(decisions) / Double(decisions + misses)
    }

    /// The multiplier the next correct decision earns.
    public var multiplier: Int { Self.multiplier(combo: combo + 1) }

    /// Move time forward by `seconds`: the lanes flow, a ridden log carries
    /// the frog, and road contact is checked. Returns what happened, in
    /// order. A finished game ignores time.
    @discardableResult
    public func advance(by seconds: Double) -> [FroggerEvent] {
        guard !isOver, seconds > 0 else { return [] }
        var events: [FroggerEvent] = []
        elapsed += seconds
        for i in objects.indices {
            let lane = Self.lane(at: objects[i].row)
            guard let lane else { continue }
            let delta = Double(lane.direction) * speed(row: lane.row) * seconds
            var x = objects[i].x + delta
            if ridingId == objects[i].id {
                frog.x = Self.wrap(frog.x + delta)
            }
            if x >= 1 || x < 0 {
                x = Self.wrap(x)
                objects[i].x = x
                events.append(.entered(objects[i]))
            } else {
                objects[i].x = x
            }
        }
        if Self.lane(at: frog.row)?.kind == .road {
            checkRoad(&events)
        }
        return events
    }

    /// Hop one row or one column. Returns what happened; a hop that would not
    /// move the frog (down off the start bank, sideways at the edge) returns
    /// nothing. A finished game ignores hops.
    @discardableResult
    public func move(_ direction: FroggerDirection) -> [FroggerEvent] {
        guard !isOver else { return [] }
        var row = frog.row
        var x = frog.x
        switch direction {
        case .up:    row += 1
        case .down:  row = max(Self.startRow, row - 1)
        case .left:  x = max(Self.hop / 2, x - Self.hop)
        case .right: x = min(1 - Self.hop / 2, x + Self.hop)
        }
        guard row != frog.row || abs(x - frog.x) >= 1e-9 else { return [] }
        var events: [FroggerEvent] = [.hopped]
        frog = FroggerFrog(row: row, x: x)
        ridingId = nil
        passingIds = []
        if row > maxRowThisCrossing {
            maxRowThisCrossing = row
            score += Self.pointsPerHop
        }
        if row == Self.goalRow {
            score += Self.pointsPerCrossing
            crossings += 1
            wave += 1
            layOutLanes()
            resetFrog()
            events.append(.crossed(points: Self.pointsPerCrossing))
            assignCue(row: Self.startRow + 1, into: &events)
            return events
        }
        var dead = false
        if let lane = Self.lane(at: row) {
            switch lane.kind {
            case .river: dead = checkRiver(&events)
            case .road:  dead = checkRoad(&events)
            }
        }
        if !dead, !isOver {
            assignCue(row: row + 1, into: &events)
        }
        return events
    }

    // MARK: - Rules

    /// Road contact for the frog's current row. Any vehicle not carrying the
    /// cue kills; each cued vehicle is credited once for as long as it
    /// overlaps. Returns whether the frog died.
    @discardableResult
    private func checkRoad(_ events: inout [FroggerEvent]) -> Bool {
        guard let lane = Self.lane(at: frog.row), lane.kind == .road else { return false }
        let cue = cues[frog.row]
        let overlapping = objects.filter {
            $0.row == frog.row && Self.wrappedDistance($0.x, frog.x) < $0.width / 2 + Self.frogHalfWidth
        }
        if let wrong = overlapping.first(where: { $0.character != cue }) {
            die(.squashed(wrong, cue: cue ?? wrong.character), &events)
            return true
        }
        for object in overlapping where !passingIds.contains(object.id) {
            let points = credit()
            events.append(.passed(object, points: points))
        }
        passingIds = Set(overlapping.map(\.id))
        return false
    }

    /// The landing on a river row: a cued log under the frog's centre is a
    /// correct decision and carries it; another log sinks; water drowns.
    private func checkRiver(_ events: inout [FroggerEvent]) -> Bool {
        let cue = cues[frog.row]
        let under = objects
            .filter { $0.row == frog.row && Self.wrappedDistance($0.x, frog.x) < $0.width / 2 }
            .min { Self.wrappedDistance($0.x, frog.x) < Self.wrappedDistance($1.x, frog.x) }
        guard let log = under else {
            die(.drowned(cue: cue ?? " "), &events)
            return true
        }
        if log.character == cue {
            ridingId = log.id
            let points = credit()
            events.append(.landed(log, points: points))
            return false
        }
        die(.sank(log, cue: cue ?? log.character), &events)
        return true
    }

    private func credit() -> Int {
        combo += 1
        bestCombo = max(bestCombo, combo)
        let points = Self.pointsPerDecision * Self.multiplier(combo: combo)
        score += points
        decisions += 1
        if decisions % Self.decisionsPerRampStep == 0 {
            currentWpm = min(config.targetWpm, currentWpm + Self.rampStep)
            bestWpm = max(bestWpm, currentWpm)
        }
        return points
    }

    private func die(_ event: FroggerEvent, _ events: inout [FroggerEvent]) {
        lives = max(0, lives - 1)
        misses += 1
        combo = 0
        currentWpm = max(config.startWpm, currentWpm - Self.rampStep)
        events.append(event)
        resetFrog()
        if lives == 0 {
            isOver = true
            events.append(.gameOver)
        } else {
            assignCue(row: Self.startRow + 1, into: &events)
        }
    }

    private func resetFrog() {
        frog = FroggerFrog(row: Self.startRow, x: 0.5)
        ridingId = nil
        passingIds = []
        maxRowThisCrossing = 0
        cues = [:]
    }

    /// Give `row` its cue if it is a lane without one: one of the characters
    /// its objects carry, so there is always something safe to aim for.
    private func assignCue(row: Int, into events: inout [FroggerEvent]) {
        guard let character = assignCue(row: row) else { return }
        events.append(.cue(row: row, character: character))
    }

    @discardableResult
    private func assignCue(row: Int) -> Character? {
        guard Self.lane(at: row) != nil, cues[row] == nil else { return nil }
        let labels = objects.filter { $0.row == row }.map(\.character)
        guard !labels.isEmpty else { return nil }
        let character = labels[Int.random(in: 0..<labels.count, using: &rng)]
        cues[row] = character
        return character
    }

    /// Fresh objects in every lane, evenly spaced, each with a random label.
    private func layOutLanes() {
        objects = []
        for lane in Self.lanes {
            for i in 0..<lane.count {
                let character = pool[Int.random(in: 0..<pool.count, using: &rng)]
                objects.append(FroggerObject(id: nextId, row: lane.row, kind: lane.kind, character: character,
                                             x: (Double(i) + 0.5) / Double(lane.count), width: lane.width))
                nextId += 1
            }
        }
    }

    private static func wrap(_ x: Double) -> Double {
        x - floor(x)
    }
}
