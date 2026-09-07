import Foundation

// CW Asteroids (#189, part of #170): asteroids drift in from the rim of the
// field toward the ship at the centre, each carrying a label — a character in
// the early waves, a short word or callsign later — and the learner destroys
// one by sending its label (see it, send it) or by tapping the one whose label
// the game just sent (hear it, tap it). Pure game rules — no clock, no audio,
// no UI. Time enters through `AsteroidsGame.advance(by:)`, randomness through
// the injected generator; positions are normalised 0…1 so the view only draws.
//
// Twin of the Kotlin `morsekit/Asteroids.kt`; the two must stay in step.
// Pinned by fixtures/asteroids.json on both ports.

/// How the learner destroys an asteroid.
public enum AsteroidsInput: String, Codable, CaseIterable, Identifiable, Sendable {
    case send, copy

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .send: return "See it, send it"
        case .copy: return "Hear it, tap it"
        }
    }

    public var blurb: String {
        switch self {
        case .send:
            return "Each asteroid shows its label. Key it on the on-screen key or a hardware key; the decoded characters destroy the asteroid whose label they spell. A word splits into its characters when hit."
        case .copy:
            return "The game sends one asteroid's label in Morse (tap the ship to hear it again). Tap the asteroid carrying what you heard; a wrong asteroid is a miss."
        }
    }
}

/// One asteroid on the field. `angle` is where it sits around the ship, in
/// radians; `progress` runs 0 at the rim to 1 at the ship and takes
/// `approachTime` seconds, fixed at spawn so a wave change mid-flight does
/// not jolt the ones already on their way.
public struct Asteroid: Identifiable, Equatable, Sendable {
    public let id: Int
    public let label: String
    public let angle: Double
    public var progress: Double
    public let approachTime: Double
    /// Split off a word that was hit: smaller, single-character.
    public let isFragment: Bool

    public init(id: Int, label: String, angle: Double, progress: Double,
                approachTime: Double, isFragment: Bool = false) {
        self.id = id
        self.label = label
        self.angle = angle
        self.progress = progress
        self.approachTime = approachTime
        self.isFragment = isFragment
    }

    /// Normalised position, 0…1 on both axes, the ship at (0.5, 0.5).
    public var x: Double { 0.5 + 0.5 * (1 - progress) * cos(angle) }
    public var y: Double { 0.5 + 0.5 * (1 - progress) * sin(angle) }

    /// The direction it travels: straight at the ship.
    public var heading: Double { angle + .pi }
}

/// What one call to `AsteroidsGame.advance(by:)` did, in order.
public enum AsteroidsEvent: Equatable, Sendable {
    case spawned(Asteroid)
    /// An asteroid reached the ship.
    case struck(Asteroid)
    /// Copy mode: this asteroid is armed; the view sends its label.
    case cued(Asteroid)
    case gameOver
}

public enum AsteroidsOutcome: String, Equatable, Sendable {
    case hit, partial, miss, ignored
}

/// The outcome of one send or tap. `asteroid` is the one destroyed on a hit
/// and `fragments` what it split into; on a miss `expected` is the character
/// that should have come and `chosen` the one that did. `cued` is the next
/// armed asteroid when the hit freed the field (copy mode).
public struct AsteroidsShot: Equatable, Sendable {
    public let outcome: AsteroidsOutcome
    public let asteroid: Asteroid?
    public let points: Int
    public let waveCleared: Bool
    public let fragments: [Asteroid]
    public let expected: Character?
    public let chosen: Character?
    public let cued: Asteroid?

    public init(outcome: AsteroidsOutcome, asteroid: Asteroid? = nil, points: Int = 0,
                waveCleared: Bool = false, fragments: [Asteroid] = [],
                expected: Character? = nil, chosen: Character? = nil, cued: Asteroid? = nil) {
        self.outcome = outcome
        self.asteroid = asteroid
        self.points = points
        self.waveCleared = waveCleared
        self.fragments = fragments
        self.expected = expected
        self.chosen = chosen
        self.cued = cued
    }

    public var isHit: Bool { outcome == .hit }
}

public final class AsteroidsGame {

    public struct Config: Sendable, Equatable {
        /// The characters single asteroids carry; empty falls back to the first two Koch characters.
        public var characters: [Character]
        /// Candidate word labels, most useful first; filtered to the character set.
        public var words: [String]
        public var input: AsteroidsInput
        public var difficulty: InvadersDifficulty
        public var lives: Int
        /// Hits that clear a wave and tighten the timings.
        public var hitsPerWave: Int
        /// The learner's character speed: where the copy-mode speed ramp ends.
        public var characterWpm: Double
        /// Whether word spawns may be generated callsigns.
        public var callsigns: Bool

        public init(characters: [Character],
                    words: [String] = [],
                    input: AsteroidsInput = .send,
                    difficulty: InvadersDifficulty = .normal,
                    lives: Int = AsteroidsGame.defaultLives,
                    hitsPerWave: Int = AsteroidsGame.defaultHitsPerWave,
                    characterWpm: Double = 20,
                    callsigns: Bool = true) {
            self.characters = characters
            self.words = words
            self.input = input
            self.difficulty = difficulty
            self.lives = lives
            self.hitsPerWave = hitsPerWave
            self.characterWpm = characterWpm
            self.callsigns = callsigns
        }

        public var startWpm: Double { AsteroidsGame.rampStart(characterWpm: characterWpm) }
        public var targetWpm: Double { max(AsteroidsGame.minWpm, characterWpm) }
    }

    public static let pointsPerCharacter = 100
    public static let defaultLives = 3
    public static let defaultHitsPerWave = 8
    public static let maxOnField = 5
    public static let sectors = 12
    public static let baseSpawnInterval = 3.0
    public static let minSpawnInterval = 1.2
    public static let spawnTightening = 0.9
    public static let baseApproachTime = 12.0
    public static let minApproachTime = 5.0
    public static let approachTightening = 0.92
    /// Radians between neighbouring fragments of a split word.
    public static let splitSpread = 0.18
    public static let wordWaveStart = 3
    public static let wordChancePerWave = 0.15
    public static let maxWordChance = 0.5
    public static let wordRank = 300
    public static let minWordLength = 2
    public static let maxWordLength = 5
    /// Seconds of silence after which a partial send is dropped.
    public static let sendBufferTimeout = 2.5

    /// Spawn interval for a wave: 10% tighter per wave, floored, then scaled by difficulty.
    public static func spawnInterval(wave: Int, difficulty: InvadersDifficulty) -> Double {
        max(minSpawnInterval, baseSpawnInterval * pow(spawnTightening, Double(max(0, wave - 1)))) * difficulty.timeScale
    }

    /// Approach time for a wave: 8% faster per wave, floored, then scaled by difficulty.
    public static func approachTime(wave: Int, difficulty: InvadersDifficulty) -> Double {
        max(minApproachTime, baseApproachTime * pow(approachTightening, Double(max(0, wave - 1)))) * difficulty.timeScale
    }

    /// Combo multiplier: ×1 for the first three hits in a row, ×2 for the next three, up to ×4.
    public static func multiplier(combo: Int) -> Int {
        min(4, 1 + max(0, combo - 1) / 3)
    }

    /// The share of spawns that are words at a wave: none before
    /// `wordWaveStart`, then 15% more each wave up to a half.
    public static func wordChance(wave: Int) -> Double {
        guard wave >= wordWaveStart else { return 0 }
        return min(maxWordChance, wordChancePerWave * Double(wave - wordWaveStart + 1))
    }

    /// The word pool for a character set: the first `wordRank` of `words`,
    /// upper-cased, `minWordLength`…`maxWordLength` long, spelt entirely from
    /// the set, without repeats, in list order.
    public static func wordPool(words: [String], characters: [Character]) -> [String] {
        let set = Set(characters.map { Character(String($0).uppercased()) })
        var seen = Set<String>()
        return words.prefix(wordRank)
            .map { $0.uppercased() }
            .filter { $0.count >= minWordLength && $0.count <= maxWordLength }
            .filter { $0.allSatisfy { set.contains($0) } }
            .filter { seen.insert($0).inserted }
    }

    // Speed ramp, copy mode. Gentler than Invaders' (#194): a game opens
    // `rampStartOffset` WPM under the learner's character speed — never under
    // `minWpm`, the app-wide floor — and climbs `rampStep` WPM every
    // `hitsPerRampStep` hits in total up to the character speed; a strike on
    // the ship steps it back, never under the start. Misses leave it alone.
    public static let minWpm = 15.0
    public static let rampStartOffset = 8.0
    public static let rampStep = 1.0
    public static let hitsPerRampStep = 4

    public static func rampStart(characterWpm: Double) -> Double {
        max(minWpm, characterWpm - rampStartOffset)
    }

    public let config: Config
    private var rng: any RandomNumberGenerator
    private let pool: [Character]
    /// The words this game can spawn, after `wordPool`'s filter.
    public let wordPool: [String]

    public private(set) var asteroids: [Asteroid] = []
    public private(set) var score = 0
    public private(set) var wave = 1
    public private(set) var lives: Int
    /// Consecutive hits since the last miss or strike.
    public private(set) var combo = 0
    public private(set) var bestCombo = 0
    public private(set) var hits = 0
    /// Misses plus strikes on the ship.
    public private(set) var misses = 0
    /// Game time in seconds, the sum of every `advance(by:)`.
    public private(set) var elapsed = 0.0
    public private(set) var isOver = false
    /// The speed labels are sent at now (copy mode).
    public private(set) var currentWpm: Double
    public private(set) var bestWpm: Double
    /// Send mode: the characters sent since the buffer was last resolved.
    public private(set) var sendBuffer = ""
    /// Copy mode: the asteroid whose label was sent, if any.
    public private(set) var armedId: Int?

    private var waveHits = 0
    private var sinceSpawn = 0.0
    private var sinceKey = 0.0
    private var nextId = 1
    private var lastSector = -1

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
        self.wordPool = Self.wordPool(words: config.words, characters: self.pool)
    }

    public var spawnInterval: Double { Self.spawnInterval(wave: wave, difficulty: config.difficulty) }
    public var approachTime: Double { Self.approachTime(wave: wave, difficulty: config.difficulty) }

    /// Hits over every hit, miss and strike, 0…1.
    public var accuracy: Double {
        hits + misses == 0 ? 0 : Double(hits) / Double(hits + misses)
    }

    /// The asteroid nearest the ship: highest progress, then lowest id.
    public var nearest: Asteroid? { Self.nearest(of: asteroids) }

    /// Copy mode: the armed asteroid, if it is still on the field.
    public var armed: Asteroid? { asteroids.first { $0.id == armedId } }

    /// The multiplier the next hit earns.
    public var multiplier: Int { Self.multiplier(combo: combo + 1) }

    private static func nearest(of list: [Asteroid]) -> Asteroid? {
        list.min { a, b in a.progress != b.progress ? a.progress > b.progress : a.id < b.id }
    }

    /// Move time forward by `seconds`: asteroids close in, any that reach the
    /// ship cost a life, a stale partial send is dropped, the spawn clock
    /// releases new ones, and copy mode arms the next target. Returns what
    /// happened, in order. A finished game ignores time.
    @discardableResult
    public func advance(by seconds: Double) -> [AsteroidsEvent] {
        guard !isOver, seconds > 0 else { return [] }
        var events: [AsteroidsEvent] = []
        elapsed += seconds

        var moved = asteroids
        for i in moved.indices { moved[i].progress += seconds / moved[i].approachTime }
        let struck = moved.filter { $0.progress >= 1.0 }
        asteroids = moved.filter { $0.progress < 1.0 }
        for var a in struck {
            lives = max(0, lives - 1)
            misses += 1
            combo = 0
            currentWpm = max(config.startWpm, currentWpm - Self.rampStep)
            if a.id == armedId { armedId = nil }
            a.progress = 1.0
            events.append(.struck(a))
        }
        if lives == 0 {
            isOver = true
            asteroids = []
            armedId = nil
            sendBuffer = ""
            events.append(.gameOver)
            return events
        }

        sinceKey += seconds
        if !sendBuffer.isEmpty, sinceKey >= Self.sendBufferTimeout { sendBuffer = "" }

        sinceSpawn += seconds
        while sinceSpawn >= spawnInterval {
            sinceSpawn -= spawnInterval
            // A full field skips the spawn; the clock still resets. Born
            // partway in when the step overshot its spawn time.
            if asteroids.count < Self.maxOnField {
                events.append(.spawned(spawn(progress: sinceSpawn / approachTime)))
            }
        }
        if let cue = recue() { events.append(.cued(cue)) }
        return events
    }

    /// Send mode: one decoded character. The buffer grows until it spells a
    /// label (a hit on the nearest asteroid carrying it), can still start one
    /// (a partial), or cannot (a miss, confused with the character that
    /// should have come next). A finished game ignores it.
    @discardableResult
    public func send(_ character: Character) -> AsteroidsShot {
        guard !isOver else { return AsteroidsShot(outcome: .ignored) }
        let c = Character(String(character).uppercased())
        guard c != " " else { return AsteroidsShot(outcome: .ignored) }
        let previous = sendBuffer
        sendBuffer.append(c)
        sinceKey = 0
        if let target = Self.nearest(of: asteroids.filter { $0.label == sendBuffer }) {
            sendBuffer = ""
            return destroy(target)
        }
        if asteroids.contains(where: { $0.label.hasPrefix(sendBuffer) }) {
            return AsteroidsShot(outcome: .partial)
        }
        // The nearest asteroid the buffer was still spelling names the
        // character that was expected in place of this one.
        let candidates = asteroids.filter { $0.label.hasPrefix(previous) }
        var expected: Character?
        if let nearest = Self.nearest(of: candidates), previous.count < nearest.label.count {
            expected = Array(nearest.label)[previous.count]
        }
        sendBuffer = ""
        return miss(expected: expected, chosen: c)
    }

    /// Copy mode: a tap on the asteroid with `id`. A hit if it carries the
    /// armed label, a miss if it carries another, ignored if nothing is
    /// there or nothing is armed.
    @discardableResult
    public func tap(_ id: Int) -> AsteroidsShot {
        guard !isOver, let tapped = asteroids.first(where: { $0.id == id }),
              let armed else { return AsteroidsShot(outcome: .ignored) }
        if tapped.label == armed.label {
            armedId = nil
            return destroy(tapped)
        }
        return miss(expected: armed.label.first, chosen: tapped.label.first)
    }

    /// Put an asteroid on the field directly — for the harness, tests and
    /// previews; the game's own spawns go through the clock. Copy mode arms
    /// it if nothing else is armed.
    @discardableResult
    public func place(label: String, angle: Double, progress: Double) -> Asteroid {
        let a = Asteroid(id: nextId, label: label.uppercased(), angle: angle,
                         progress: progress, approachTime: approachTime)
        nextId += 1
        asteroids.append(a)
        _ = recue()
        return a
    }

    private func destroy(_ target: Asteroid) -> AsteroidsShot {
        asteroids.removeAll { $0.id == target.id }
        combo += 1
        bestCombo = max(bestCombo, combo)
        let points = Self.pointsPerCharacter * target.label.count * Self.multiplier(combo: combo)
        score += points
        hits += 1
        if hits % Self.hitsPerRampStep == 0 {
            currentWpm = min(config.targetWpm, currentWpm + Self.rampStep)
            bestWpm = max(bestWpm, currentWpm)
        }
        waveHits += 1
        var cleared = false
        if waveHits >= max(1, config.hitsPerWave) {
            wave += 1
            waveHits = 0
            cleared = true
        }
        var fragments: [Asteroid] = []
        let chars = Array(target.label)
        if chars.count > 1 {
            let n = Double(chars.count)
            for (k, ch) in chars.enumerated() {
                let angle = target.angle + (Double(k) - (n - 1) / 2) * Self.splitSpread
                let f = Asteroid(id: nextId, label: String(ch), angle: angle, progress: target.progress,
                                 approachTime: approachTime, isFragment: true)
                nextId += 1
                fragments.append(f)
            }
            asteroids.append(contentsOf: fragments)
        }
        return AsteroidsShot(outcome: .hit, asteroid: target, points: points, waveCleared: cleared,
                             fragments: fragments, cued: recue())
    }

    private func miss(expected: Character?, chosen: Character?) -> AsteroidsShot {
        combo = 0
        misses += 1
        return AsteroidsShot(outcome: .miss, expected: expected, chosen: chosen)
    }

    /// Copy mode: arm a random asteroid when none is armed. Returns it.
    private func recue() -> Asteroid? {
        guard config.input == .copy, armed == nil, !asteroids.isEmpty else { return nil }
        let a = asteroids[Int.random(in: 0..<asteroids.count, using: &rng)]
        armedId = a.id
        return a
    }

    private func spawn(progress: Double) -> Asteroid {
        let onField = Set(asteroids.map(\.label))
        var label: String?
        let chance = Self.wordChance(wave: wave)
        if chance > 0, Double.random(in: 0..<1, using: &rng) < chance {
            if config.callsigns, Int.random(in: 0..<3, using: &rng) == 0 {
                label = callsign()
            }
            if label == nil, !wordPool.isEmpty {
                let fresh = wordPool.filter { !onField.contains($0) }
                let choices = fresh.isEmpty ? wordPool : fresh
                label = choices[Int.random(in: 0..<choices.count, using: &rng)]
            }
        }
        if label == nil {
            // Prefer a character not already on the field, so a label is
            // usually carried by one asteroid; a two-character set repeats.
            let fresh = pool.filter { !onField.contains(String($0)) }
            let choices = fresh.isEmpty ? pool : fresh
            label = String(choices[Int.random(in: 0..<choices.count, using: &rng)])
        }
        let sectors = Self.sectors
        let open = (0..<sectors).filter { $0 != lastSector }
        let sector = open[Int.random(in: 0..<open.count, using: &rng)]
        lastSector = sector
        let angle = (Double(sector) + 0.5) * 2 * .pi / Double(sectors)
        let a = Asteroid(id: nextId, label: label ?? "K", angle: angle, progress: progress, approachTime: approachTime)
        nextId += 1
        asteroids.append(a)
        return a
    }

    /// A generated callsign the character set can spell, or nil after a few tries.
    private func callsign() -> String? {
        let set = Set(pool)
        for _ in 0..<8 {
            let call = CallsignGenerator.generate(formats: CallsignFormat.commonDefaults, usOnly: false, using: &rng)
            if call.count <= Self.maxWordLength, call.allSatisfy({ set.contains($0) }) { return call }
        }
        return nil
    }
}
