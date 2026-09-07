import Foundation

// Morse Defender (#188, part of #170): assets — cities and ships, each with a
// callsign — sit along the bottom of the field. Attackers launch at the top,
// and each announces its target's callsign in Morse; the learner copies the
// callsign and routes a defence to that asset before the attacker arrives.
// Pure game rules — no clock, no audio, no UI. Time enters through
// `DefenderGame.advance(by:)`, so the view drives it from a frame clock and the
// harness from arithmetic; every random choice comes from the injected
// generator, so a seed pins the callsigns and the launch sequence. The
// difficulty table, scoring, ramp and a scripted scenario are pinned by
// fixtures/defender.json on both ports.
//
// Twin of the Kotlin `morsekit/Defender.kt`; the two must stay in step.

/// How the learner routes a defence.
public enum DefenderInput: String, Codable, CaseIterable, Identifiable, Sendable {
    case tap, typed

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .tap:   return "Tap the target"
        case .typed: return "Type the callsign"
        }
    }

    public var blurb: String {
        switch self {
        case .tap:
            return "Each attacker sends the callsign of the asset it is heading for (tap the attacker to hear it again). Copy it and tap that asset to route the defence."
        case .typed:
            return "Each attacker sends its target's callsign (tap the attacker to hear it again). Type the callsign on the keyboard; the defence routes itself the moment it matches an asset."
        }
    }
}

/// One asset under defence. `isAlive` goes false when an attacker reaches it;
/// the rubble stays in the row so the layout does not shift mid-game.
public struct DefenderAsset: Identifiable, Equatable, Sendable {
    public let id: Int
    public let callsign: String
    public var isAlive: Bool

    public init(id: Int, callsign: String, isAlive: Bool = true) {
        self.id = id
        self.callsign = callsign
        self.isAlive = isAlive
    }
}

/// One attacker in flight. `progress` runs 0 at launch to 1 at its target;
/// `travelTime` is how many seconds that takes, fixed at launch so a wave
/// change mid-flight does not jolt the ones already flying. `column` is where
/// it descends; the target is known only by the callsign it sends.
public struct DefenderAttacker: Identifiable, Equatable, Sendable {
    public let id: Int
    public let targetId: Int
    public let callsign: String
    public let column: Int
    public var progress: Double
    public let travelTime: Double

    public init(id: Int, targetId: Int, callsign: String, column: Int, progress: Double, travelTime: Double) {
        self.id = id
        self.targetId = targetId
        self.callsign = callsign
        self.column = column
        self.progress = progress
        self.travelTime = travelTime
    }
}

/// What one call to `DefenderGame.advance(by:)` did, in order.
public enum DefenderEvent: Equatable, Sendable {
    case launched(DefenderAttacker)
    /// An attacker reached its asset; the asset is passed after destruction.
    case struck(DefenderAttacker, DefenderAsset)
    case gameOver
}

/// The outcome of one routed defence. `attacker` is the one destroyed (nil on
/// a wasted shot), `asset` the one routed to (nil when a typed callsign
/// matched nothing), `points` what the hit scored, `waveCleared` whether it
/// finished the wave, and `reinforced` the asset that wave clear added or
/// rebuilt, if any.
public struct DefenderRoute: Equatable, Sendable {
    public let attacker: DefenderAttacker?
    public let asset: DefenderAsset?
    public let points: Int
    public let waveCleared: Bool
    public let reinforced: DefenderAsset?

    public init(attacker: DefenderAttacker?, asset: DefenderAsset?, points: Int,
                waveCleared: Bool, reinforced: DefenderAsset?) {
        self.attacker = attacker
        self.asset = asset
        self.points = points
        self.waveCleared = waveCleared
        self.reinforced = reinforced
    }

    public var isHit: Bool { attacker != nil }

    static let miss = DefenderRoute(attacker: nil, asset: nil, points: 0, waveCleared: false, reinforced: nil)
}

public final class DefenderGame {

    public struct Config: Sendable, Equatable {
        /// The learner's characters, for the synthetic callsigns of `.active`.
        public var characters: [Character]
        /// `.active`: call-like tokens spelled from `characters`; `.full`: real callsigns.
        public var callsigns: InvadersCharacterSet
        public var difficulty: InvadersDifficulty
        public var columns: Int
        public var startAssets: Int
        public var maxAssets: Int
        /// Hits that clear a wave, tighten the timings and reinforce the defence.
        public var hitsPerWave: Int
        /// The learner's character speed: where the speed ramp ends.
        public var characterWpm: Double

        public init(characters: [Character],
                    callsigns: InvadersCharacterSet = .active,
                    difficulty: InvadersDifficulty = .normal,
                    columns: Int = DefenderGame.defaultColumns,
                    startAssets: Int = DefenderGame.defaultStartAssets,
                    maxAssets: Int = DefenderGame.defaultMaxAssets,
                    hitsPerWave: Int = DefenderGame.defaultHitsPerWave,
                    characterWpm: Double = 20) {
            self.characters = characters
            self.callsigns = callsigns
            self.difficulty = difficulty
            self.columns = columns
            self.startAssets = startAssets
            self.maxAssets = maxAssets
            self.hitsPerWave = hitsPerWave
            self.characterWpm = characterWpm
        }

        /// Where a game's speed starts: `DefenderGame.rampStart(characterWpm:)`.
        public var startWpm: Double { DefenderGame.rampStart(characterWpm: characterWpm) }

        /// Where the ramp ends: the character speed, never under the floor.
        public var targetWpm: Double { max(DefenderGame.minWpm, characterWpm) }
    }

    public static let pointsPerHit = 100
    public static let baseSpawnInterval = 5.0
    public static let minSpawnInterval = 2.0
    public static let spawnDecay = 0.9
    public static let baseTravelTime = 14.0
    public static let minTravelTime = 6.0
    public static let travelDecay = 0.92
    public static let maxConcurrent = 3
    public static let defaultColumns = 5
    public static let defaultStartAssets = 4
    public static let defaultMaxAssets = 8
    public static let defaultHitsPerWave = 6

    /// Seconds between launches at a wave: 10% tighter per wave, floored, then scaled by difficulty.
    public static func spawnInterval(wave: Int, difficulty: InvadersDifficulty) -> Double {
        max(minSpawnInterval, baseSpawnInterval * pow(spawnDecay, Double(max(0, wave - 1)))) * difficulty.timeScale
    }

    /// Seconds from launch to the target at a wave: 8% faster per wave, floored, then scaled by difficulty.
    public static func travelTime(wave: Int, difficulty: InvadersDifficulty) -> Double {
        max(minTravelTime, baseTravelTime * pow(travelDecay, Double(max(0, wave - 1)))) * difficulty.timeScale
    }

    /// Attackers in flight at once: one, then one more every two waves, up to `maxConcurrent`.
    public static func concurrent(wave: Int) -> Int {
        min(maxConcurrent, 1 + max(0, wave - 1) / 2)
    }

    /// Combo multiplier: ×1 for the first three hits in a row, ×2 for the next three, up to ×4.
    public static func multiplier(combo: Int) -> Int {
        min(4, 1 + max(0, combo - 1) / 3)
    }

    // Speed ramp. A game opens `rampStartOffset` WPM under the learner's
    // character speed — never under `minWpm`, the app-wide floor — and climbs
    // `rampStep` WPM every `hitsPerRampStep` hits in total up to the character
    // speed; a strike steps it back, never under the start. Wasted shots leave
    // it alone. Unlike Invaders, Farnsworth spacing IS honoured — a callsign
    // has gaps to stretch — see `sendTiming`. Its own constants, gentler than
    // Invaders': callsigns are heavier than single characters.
    public static let minWpm = 15.0
    public static let rampStartOffset = 8.0
    public static let rampStep = 2.0
    public static let hitsPerRampStep = 4

    /// The speed a game starts at for a character speed: 8 WPM under it,
    /// floored at 15. At or under 15 there is no ramp.
    public static func rampStart(characterWpm: Double) -> Double {
        max(minWpm, characterWpm - rampStartOffset)
    }

    /// The timing a callsign is sent with at the ramp's `wpm`: standard when
    /// the Farnsworth switch is off (`farnsworthWpm` nil), otherwise
    /// characters at `wpm` with the spacing stretched to the effective speed,
    /// which can never exceed the character speed.
    public static func sendTiming(wpm: Double, farnsworthWpm: Double?) -> MorseTiming {
        guard let farnsworthWpm else { return MorseTiming(wpm: wpm) }
        return MorseTiming(characterWpm: wpm, effectiveWpm: min(wpm, farnsworthWpm))
    }

    // MARK: - Callsigns

    private static let realFormats = CallsignFormat.commonDefaults

    /// The letters then digits of `characters`, upper-cased, without repeats
    /// and without anything else (punctuation, prosigns); K and M when
    /// nothing usable is left. What the synthetic callsigns are spelled from.
    public static func callsignAlphabet(from characters: [Character]) -> [Character] {
        var seen = Set<Character>()
        let upper = characters
            .map { Character(String($0).uppercased()) }
            .filter { ($0.isLetter || $0.isNumber) && $0.isASCII && seen.insert($0).inserted }
        let letters = upper.filter(\.isLetter)
        let digits = upper.filter(\.isNumber)
        let alphabet = letters + digits
        return alphabet.isEmpty ? Array(MorseCode.kochOrder.prefix(2)) : alphabet
    }

    /// A four-character call-like token from the learner's own set: position
    /// 1 is a digit when the set has any, the rest letters (or digits when
    /// the set has no letters), so K5MR looks like a call and KMRS like a
    /// group — and nothing is sent the learner has not met.
    public static func syntheticCallsign<R: RandomNumberGenerator>(
        from characters: [Character], using rng: inout R
    ) -> String {
        let alphabet = callsignAlphabet(from: characters)
        let letters = alphabet.filter(\.isLetter)
        let digits = alphabet.filter(\.isNumber)
        let body = letters.isEmpty ? digits : letters
        let middle = digits.isEmpty ? body : digits
        var s = ""
        for i in 0..<4 {
            let pool = i == 1 ? middle : body
            s.append(pool[Int.random(in: 0..<pool.count, using: &rng)])
        }
        return s
    }

    /// A real US-style callsign in the everyday shapes (1×2, 2×1, 1×3, 2×2).
    public static func realCallsign<R: RandomNumberGenerator>(using rng: inout R) -> String {
        CallsignGenerator.generate(formats: realFormats, usOnly: true, using: &rng)
    }

    public let config: Config
    private var rng: any RandomNumberGenerator

    public private(set) var assets: [DefenderAsset] = []
    public private(set) var attackers: [DefenderAttacker] = []
    public private(set) var score = 0
    public private(set) var wave = 1
    /// Consecutive hits since the last wasted shot or strike.
    public private(set) var combo = 0
    public private(set) var bestCombo = 0
    public private(set) var hits = 0
    /// Wasted shots plus attackers that reached their asset.
    public private(set) var misses = 0
    /// Game time in seconds, the sum of every `advance(by:)`.
    public private(set) var elapsed = 0.0
    public private(set) var isOver = false
    /// The speed callsigns are sent at now: starts at `config.startWpm`,
    /// climbs with hits and falls back with strikes.
    public private(set) var currentWpm: Double
    /// The highest speed the ramp reached this game.
    public private(set) var bestWpm: Double

    private var waveHits = 0
    private var sinceSpawn = 0.0
    private var nextAssetId = 1
    private var nextAttackerId = 1
    private var lastColumn = -1

    public init(config: Config,
                rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.config = config
        self.rng = rng
        self.currentWpm = config.startWpm
        self.bestWpm = config.startWpm
        for _ in 0..<max(1, min(config.startAssets, max(1, config.maxAssets))) { placeAsset() }
    }

    /// The characters a typed callsign can be spelled from: the synthetic
    /// alphabet, or every letter and digit for real callsigns.
    public var keyboardPool: [Character] {
        switch config.callsigns {
        case .active: return Self.callsignAlphabet(from: config.characters)
        case .full:   return Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
        }
    }

    /// Seconds between launches at the current wave.
    public var spawnInterval: Double { Self.spawnInterval(wave: wave, difficulty: config.difficulty) }

    /// Seconds a fresh attacker takes to reach its asset at the current wave.
    public var travelTime: Double { Self.travelTime(wave: wave, difficulty: config.difficulty) }

    /// Attackers allowed in flight at the current wave.
    public var concurrent: Int { Self.concurrent(wave: wave) }

    /// Assets still standing.
    public var liveAssets: Int { assets.filter(\.isAlive).count }

    /// Hits over every shot and strike, 0…1.
    public var accuracy: Double {
        hits + misses == 0 ? 0 : Double(hits) / Double(hits + misses)
    }

    /// The attacker nearest its target, if any.
    public var nearestArrival: DefenderAttacker? { attackers.max { $0.progress < $1.progress } }

    /// The multiplier the next hit earns.
    public var multiplier: Int { Self.multiplier(combo: combo + 1) }

    /// The longest callsign among the standing assets — how many characters a
    /// typed copy can run to before it is surely wrong.
    public var longestLiveCallsign: Int {
        assets.filter(\.isAlive).map(\.callsign.count).max() ?? 0
    }

    /// Whether any attacker in flight is aimed at `assetId`.
    public func isTargeted(_ assetId: Int) -> Bool {
        attackers.contains { $0.targetId == assetId }
    }

    /// Move time forward by `seconds`: attackers fly, any that arrive destroy
    /// their asset (and withdraw the others aimed at it), and the launch clock
    /// releases new ones while there is room. Returns what happened, in order.
    /// A finished game ignores time.
    @discardableResult
    public func advance(by seconds: Double) -> [DefenderEvent] {
        guard !isOver, seconds > 0 else { return [] }
        var events: [DefenderEvent] = []
        elapsed += seconds

        var moved = attackers
        for i in moved.indices { moved[i].progress += seconds / moved[i].travelTime }
        let arrived = moved.filter { $0.progress >= 1.0 }.sorted { $0.progress > $1.progress }
        var remaining = moved.filter { $0.progress < 1.0 }
        var withdrawn = Set<Int>()
        for var a in arrived where !withdrawn.contains(a.id) {
            a.progress = 1.0
            guard let idx = assets.firstIndex(where: { $0.id == a.targetId }), assets[idx].isAlive else { continue }
            assets[idx].isAlive = false
            misses += 1
            combo = 0
            currentWpm = max(config.startWpm, currentWpm - Self.rampStep)
            // The asset is gone: everything else aimed at it has nothing to hit.
            for other in arrived where other.id != a.id && other.targetId == a.targetId { withdrawn.insert(other.id) }
            remaining.removeAll { $0.targetId == a.targetId }
            events.append(.struck(a, assets[idx]))
        }
        attackers = remaining
        if liveAssets == 0 {
            isOver = true
            attackers = []
            events.append(.gameOver)
            return events
        }

        sinceSpawn += seconds
        while sinceSpawn >= spawnInterval {
            if attackers.count >= concurrent {
                // Full: hold the clock at the interval so one launches the
                // moment a slot frees.
                sinceSpawn = spawnInterval
                break
            }
            sinceSpawn -= spawnInterval
            // Born partway down when the step overshot its launch time, so a
            // long frame does not gift the attacker extra flight time.
            let attacker = launch(progress: sinceSpawn / travelTime)
            events.append(.launched(attacker))
        }
        return events
    }

    /// Route a defence to the asset with `assetId`. Destroys the attacker
    /// nearest that asset among those aimed at it and scores `pointsPerHit`
    /// times the combo multiplier; with none aimed at it (or the asset
    /// already destroyed, or unknown) it is a wasted shot that breaks the
    /// combo. A finished game ignores routes.
    @discardableResult
    public func route(to assetId: Int) -> DefenderRoute {
        guard !isOver else { return .miss }
        let asset = assets.first { $0.id == assetId }
        guard let asset, asset.isAlive,
              let target = attackers.filter({ $0.targetId == assetId }).max(by: { $0.progress < $1.progress }) else {
            combo = 0
            misses += 1
            return DefenderRoute(attacker: nil, asset: asset, points: 0, waveCleared: false, reinforced: nil)
        }
        attackers.removeAll { $0.id == target.id }
        combo += 1
        bestCombo = max(bestCombo, combo)
        let points = Self.pointsPerHit * Self.multiplier(combo: combo)
        score += points
        hits += 1
        if hits % Self.hitsPerRampStep == 0 {
            currentWpm = min(config.targetWpm, currentWpm + Self.rampStep)
            bestWpm = max(bestWpm, currentWpm)
        }
        waveHits += 1
        var cleared = false
        var reinforced: DefenderAsset?
        if waveHits >= max(1, config.hitsPerWave) {
            wave += 1
            waveHits = 0
            cleared = true
            reinforced = reinforce()
        }
        return DefenderRoute(attacker: target, asset: asset, points: points,
                             waveCleared: cleared, reinforced: reinforced)
    }

    /// Route a defence by callsign, as typed: the standing asset with that
    /// callsign (case-insensitive) takes it; no such asset is a wasted shot.
    @discardableResult
    public func route(callsign: String) -> DefenderRoute {
        guard !isOver else { return .miss }
        let wanted = callsign.trimmingCharacters(in: .whitespaces).uppercased()
        guard let asset = assets.first(where: { $0.isAlive && $0.callsign == wanted }) else {
            combo = 0
            misses += 1
            return .miss
        }
        return route(to: asset.id)
    }

    // MARK: - Private

    /// A wave clear's reinforcement: a new asset while there is room, else
    /// the first destroyed one rebuilt, else nothing.
    private func reinforce() -> DefenderAsset? {
        if assets.count < config.maxAssets {
            return placeAsset()
        }
        guard let idx = assets.firstIndex(where: { !$0.isAlive }) else { return nil }
        let rebuilt = DefenderAsset(id: nextAssetId, callsign: freshCallsign())
        nextAssetId += 1
        assets[idx] = rebuilt
        return rebuilt
    }

    @discardableResult
    private func placeAsset() -> DefenderAsset {
        let asset = DefenderAsset(id: nextAssetId, callsign: freshCallsign())
        nextAssetId += 1
        assets.append(asset)
        return asset
    }

    /// A callsign no asset on the field (standing or destroyed) has carried
    /// this game, so a copy is never ambiguous; a tiny alphabet may run out,
    /// in which case the last draw stands.
    private func freshCallsign() -> String {
        let taken = Set(assets.map(\.callsign))
        var call = ""
        for _ in 0..<40 {
            call = drawCallsign()
            if !taken.contains(call) { break }
        }
        return call
    }

    private func drawCallsign() -> String {
        switch config.callsigns {
        case .active: return Self.syntheticCallsign(from: config.characters, using: &rng)
        case .full:   return Self.realCallsign(using: &rng)
        }
    }

    private func launch(progress: Double) -> DefenderAttacker {
        // Prefer an asset nothing is aimed at, so each callsign in the air is
        // usually a different one; with every asset covered, any will do.
        let live = assets.filter(\.isAlive)
        let open = live.filter { !isTargeted($0.id) }
        let choices = open.isEmpty ? live : open
        let target = choices[Int.random(in: 0..<choices.count, using: &rng)]
        let columns = max(1, config.columns)
        let column: Int
        if columns == 1 {
            column = 0
        } else {
            let free = (0..<columns).filter { $0 != lastColumn }
            column = free[Int.random(in: 0..<free.count, using: &rng)]
        }
        lastColumn = column
        let attacker = DefenderAttacker(id: nextAttackerId, targetId: target.id, callsign: target.callsign,
                                        column: column, progress: progress, travelTime: travelTime)
        nextAttackerId += 1
        attackers.append(attacker)
        return attacker
    }
}
