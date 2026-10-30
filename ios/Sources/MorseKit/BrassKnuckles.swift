import Foundation

// Brass Knuckles Knock Out: a boxing game that teaches Morse. Created by Derick
// (KO6PBZ) for the HamCube's PocketCW firmware (Kristen, K5MME), sponsored by
// The Brass Knuckle Gang. GPL-3.0-or-later, ported here with the author's
// permission (see docs/brass-knuckles-design.md).
//
// The opponent telegraphs each punch by sending a character or two (the tell).
// Key it back before the punch lands and you dodge it; the opponent is then
// open, and every character you send is a punch: all dits jab, all dahs hook,
// a mix throws a combo. Clean timing hits harder. Clean counters earn stars,
// and a star buys an uppercut: send a whole word or callsign without an error.
//
// Pure game rules, like `AsteroidsGame`: no clock, no audio, no UI. Time enters
// as a millisecond tick count passed to every call (it wraps, as the firmware's
// does); the view calls `update(now:)` at `deadline` and reads the state to
// draw it. Translated from the game's Rust rules (`logic/src/boxing.rs`,
// `rng.rs`, `ticks.rs`) so that a seeded fight plays out identically.
//
// Twin of the Kotlin `morsekit/BrassKnuckles.kt`; the two must stay in step.
// Pinned by fixtures/brass-knuckles.json on every port.
//
// Differences from the original, on purpose:
//  - the keyer, decoder, timing quality and letter statistics are AMT's, so the
//    view hands in the keyed character and a `BoxingQuality`, and tells "lean on
//    weak characters" through `learn(weights:)`;
//  - no flash-record framing: AMT keeps progress in its own stores, so
//    `BoxingCareer` has no encode/decode;
//  - the drilled set is the 41 Koch characters; the original's three trailing
//    prosigns are never sent by any fighter (the last fighter's set ends at
//    character 41).

/// How well a keyed character was timed (the original's `Quality`).
public enum BoxingQuality: Sendable { case good, off, bad }

/// Which hand.
public enum BoxingSide: String, Sendable {
    case left = "Left", right = "Right"
    public var other: BoxingSide { self == .left ? .right : .left }
}

/// What a character you send throws.
public enum BoxingPunch: Equatable, Sendable {
    case jab
    case hook
    /// One punch per run of the same element, at most four.
    case combo(Int)
}

/// What the opponent is doing, for the view.
public enum BoxingFoe: Equatable, Sendable {
    case idle
    case windup(BoxingSide)
    case punch(BoxingSide)
    case whiff(BoxingSide)
    case open
    case hurt(BoxingSide)
    case block
    case stunned
    case down
    case out
    case rise
    case victory
}

/// What you are doing, for the view.
public enum BoxingMe: Equatable, Sendable {
    case idle
    case dodge(BoxingSide)
    case jab(BoxingSide)
    case hook(BoxingSide)
    case combo(Int)
    case uppercut
    case blocked(BoxingSide)
    /// A pattern no character uses: a swing at air.
    case miss
    case hurt
    case down
    case rise
    case victory
}

public enum BoxingOutcome: String, Sendable { case ko = "Ko", tko = "Tko", decision = "Decision" }

public enum BoxingPhase: Equatable, Sendable {
    /// The fighter's card, before the first bell.
    case intro
    case roundStart
    case fight
    /// The opponent is down; the count is `count`.
    case foeDown
    /// You are down: key `getUp` before the count reaches ten.
    case meDown
    case roundEnd
    /// The fight is over: you won or lost, and how.
    case over(won: Bool, how: BoxingOutcome)
}

/// A short message for the view.
public enum BoxingCallout: Equatable, Sendable {
    case dodge
    /// Your keying did not match the tell.
    case wrong(sent: Character)
    case tooSlow
    case clean
    case star
    case uppercut
    case blocked
    case missed
    case down
    case up
}

/// One opponent on the ladder.
public struct BoxingFighter: Sendable {
    public let name: String
    /// Shown on the intro card.
    public let from: String
    public let blurb: String
    /// Koch characters this fighter's tells use, counted from the start of `BoxingRules.koch`.
    public let koch: Int
    /// Added to the receive speed.
    public let speedBump: Int
    /// Farnsworth speed as a percent of the character speed.
    public let spacingPct: Int
    /// The tell shows on screen while it plays.
    public let hint: Bool
    /// Band noise and fading levels, on top of your own settings.
    public let noise: Int
    public let fading: Int
    /// Longest tell, in characters.
    public let maxTell: Int
    public let hp: Int
    /// Damage one landed punch does to you.
    public let power: Int
    /// Time after the tell ends to start keying it back, before your keying time is added.
    public let reactMs: Int
    /// Pause between attacks: shortest, then the random extra.
    public let restLeastMs: Int
    public let restExtraMs: Int
    /// How long the opponent stays open after you dodge.
    public let openMs: Int
    /// Offset of the opponent's tone from yours.
    public let pitchOffsetHz: Int
    /// How unevenly the tells are keyed, in percent.
    public let fist: Int

    /// The Koch characters this fighter sends.
    public var set: [Character] { Array(BoxingRules.koch[0..<koch]) }

    /// The characters this fighter adds to the one before.
    public func newChars(index: Int) -> [Character] {
        let from = index == 0 ? 0 : BoxingRules.ladder[index - 1].koch
        return Array(BoxingRules.koch[from..<koch])
    }

    /// Character and Farnsworth speed of the tells, from your receive speed: (wpm, effective).
    public func speeds(baseWpm: Int, farnsworth: Int) -> (Int, Int) {
        let wpm = min(baseWpm + speedBump, 60)
        var effective = max(wpm * spacingPct / 100, 5)
        if farnsworth > 0 { effective = min(effective, farnsworth) }
        return (wpm, min(effective, wpm))
    }
}

public enum BoxingRules {
    public static let fighters = 8
    public static let rounds = 3
    public static let roundMs = 90_000
    public static let maxStars = 3
    /// Clean counters that earn a star.
    public static let cleanPerStar = 3
    public static let playerHp = 100
    /// Knockdowns in one fight that end it.
    public static let knockdownsToKo = 3
    public static let maxTell = 3
    public static let maxWord = 10
    /// One count of the referee over a fighter on the canvas.
    public static let countMs = 1000
    public static let roundBannerMs = 1800
    public static let roundEndMs = 2600
    /// How long the punch takes to land once thrown, and how long the opponent stays off balance after a dodge.
    public static let punchMs = 260
    public static let whiffMs = 420
    /// The pause after you take a punch, before the next attack can start.
    public static let staggerMs = 700
    /// Time to send the uppercut word: this per character, after this much to start.
    public static let starStartMs = 2500
    public static let starCharMs = 1200
    public static let uppercutDamage = 34
    public static let getUpCount = 10

    /// The Koch order LCWO uses: every character a fighter can send.
    public static let koch: [Character] = Array("KMURESNAPTLWI.JZ=FOY,VG5/Q92H38B?47C1D60X")

    public static let ladder: [BoxingFighter] = [
        BoxingFighter(name: "LID LARRY", from: "THE BOTTOM OF THE BAND", blurb: "CALLS CQ ON TOP OF EVERYONE",
                      koch: 5, speedBump: 0, spacingPct: 40, hint: true, noise: 0, fading: 0, maxTell: 1, hp: 60, power: 12,
                      reactMs: 2600, restLeastMs: 1800, restExtraMs: 1400, openMs: 4800, pitchOffsetHz: -60, fist: 14),
        BoxingFighter(name: "QRM QUEEN", from: "RIGHT ON YOUR FREQUENCY", blurb: "SHE SPLATTERS AND SHE KNOWS IT",
                      koch: 10, speedBump: 0, spacingPct: 50, hint: true, noise: 1, fading: 0, maxTell: 1, hp: 75, power: 13,
                      reactMs: 2300, restLeastMs: 1600, restExtraMs: 1300, openMs: 4500, pitchOffsetHz: 90, fist: 4),
        BoxingFighter(name: "PILEUP PETE", from: "THE FRONT OF EVERY PILEUP", blurb: "NEVER WAITS HIS TURN",
                      koch: 15, speedBump: 1, spacingPct: 60, hint: true, noise: 2, fading: 0, maxTell: 1, hp: 85, power: 14,
                      reactMs: 2000, restLeastMs: 1400, restExtraMs: 1200, openMs: 4200, pitchOffsetHz: -40, fist: 6),
        BoxingFighter(name: "BAREFOOT BOB", from: "100 WATTS AND A WIRE", blurb: "NO AMP, NO HINTS, NO MERCY",
                      koch: 20, speedBump: 2, spacingPct: 70, hint: false, noise: 3, fading: 0, maxTell: 1, hp: 95, power: 15,
                      reactMs: 1800, restLeastMs: 1300, restExtraMs: 1100, openMs: 3900, pitchOffsetHz: 50, fist: 0),
        BoxingFighter(name: "CONTEST CARL", from: "A 48 HOUR CONTEST", blurb: "599 TU. NEXT!",
                      koch: 25, speedBump: 3, spacingPct: 80, hint: false, noise: 3, fading: 0, maxTell: 2, hp: 105, power: 17,
                      reactMs: 1600, restLeastMs: 1100, restExtraMs: 1000, openMs: 3600, pitchOffsetHz: -80, fist: 0),
        BoxingFighter(name: "SKIP SADIE", from: "THE F2 LAYER", blurb: "HERE ONE SECOND, GONE THE NEXT",
                      koch: 30, speedBump: 4, spacingPct: 90, hint: false, noise: 4, fading: 5, maxTell: 2, hp: 115, power: 18,
                      reactMs: 1450, restLeastMs: 1000, restExtraMs: 900, openMs: 3300, pitchOffsetHz: 110, fist: 3),
        BoxingFighter(name: "SIDESWIPER SID", from: "A KEY CUT FROM A HACKSAW BLADE", blurb: "WAGS BOTH WAYS, FAST",
                      koch: 35, speedBump: 5, spacingPct: 100, hint: false, noise: 5, fading: 6, maxTell: 3, hp: 125, power: 20,
                      reactMs: 1300, restLeastMs: 900, restExtraMs: 800, openMs: 3000, pitchOffsetHz: -100, fist: 10),
        BoxingFighter(name: "BARON VON KILOWATT", from: "A CASTLE FULL OF AMPLIFIERS", blurb: "THE LEGAL LIMIT IS A SUGGESTION",
                      koch: 41, speedBump: 6, spacingPct: 100, hint: false, noise: 6, fading: 7, maxTell: 3, hp: 140, power: 23,
                      reactMs: 1150, restLeastMs: 800, restExtraMs: 700, openMs: 2800, pitchOffsetHz: 0, fist: 0),
    ]

    /// Words for uppercuts. Each fighter uses the ones made only of its characters.
    public static let words: [String] = [
        "MURK", "EMU", "RUE", "SURE", "USER", "MUSE", "RUSE", "SUM", "SKA", "TREK", "MARK", "PARK", "SNAP", "STAMP", "MASTER",
        "PAUSE", "SPARK", "TRAMP", "ANTS", "RANK", "TUNE", "NAME", "UR", "RST", "TNX", "TU", "ES", "PSE", "QSL", "QRP", "QRM",
        "QRZ", "QTH", "SKED", "POTA", "SOTA", "CQ", "DE", "73", "5NN", "599", "WX", "RIG", "ANT", "KEY", "DIPOLE", "WATTS",
        "BAND", "WIRE", "LOOP", "YAGI", "BEAM", "FIST", "SPLIT", "PILEUP", "LID", "DX", "CW", "WPM", "FB", "OM", "YL", "GM",
        "HAM", "RADIO", "MORSE", "BOXING", "PUNCH", "JAB", "HOOK", "KNOCKOUT", "CHAMP",
    ]

    /// Dots and dashes for `c`, or nil for a character with no code.
    public static func code(_ c: Character) -> String? {
        let u = Character(c.uppercased())
        return MorseCode.table[u] ?? MorseCode.optionalPunctuation[u]
    }

    /// The punch character `c` throws, from its code.
    public static func punchOf(_ c: Character) -> BoxingPunch? {
        guard let code = code(c) else { return nil }
        let marks = Array(code)
        if marks.allSatisfy({ $0 == "." }) { return .jab }
        if marks.allSatisfy({ $0 == "-" }) { return .hook }
        var runs = 1
        for i in 1..<marks.count where marks[i] != marks[i - 1] { runs += 1 }
        return .combo(min(runs, 4))
    }

    private static func qualityPercent(_ q: BoxingQuality) -> Int {
        switch q {
        case .good: return 100
        case .off: return 65
        case .bad: return 35
        }
    }

    /// Damage of `c` thrown with `quality` timing.
    public static func damage(_ c: Character, _ quality: BoxingQuality) -> Int {
        guard let code = code(c) else { return 0 }
        let len = code.count
        let base: Int
        switch punchOf(c) {
        case .jab: base = 3 + len
        case .hook: base = 4 + len
        case .combo: base = 3 + 2 * len
        case nil: base = 0
        }
        return base * qualityPercent(quality) / 100
    }

    /// The ideal length of `c` in dots, with no gap after it.
    public static func units(_ c: Character) -> Int {
        guard let code = code(c) else { return 0 }
        let dahs = code.filter { $0 == "-" }.count
        return (code.count - dahs) + 3 * dahs + code.count - 1
    }

    /// Index of `c` in `koch`, or nil.
    public static func indexOf(_ c: Character) -> Int? { koch.firstIndex(of: c) }

    // Millisecond ticks wrap every 49 days; two times compare correctly while they are under 24 days apart.
    private static let half: UInt32 = UInt32.max / 2

    public static func reached(_ now: UInt32, _ at: UInt32) -> Bool { now &- at < half }
    public static func msUntil(_ now: UInt32, _ at: UInt32) -> UInt32 { reached(now, at) ? 0 : at &- now }
    public static func earlier(_ a: UInt32, _ b: UInt32) -> UInt32 { reached(b, a) ? a : b }
}

/// The game's xorshift32 generator, so a seed plays out the same on every port.
public struct BoxingRng: Sendable {
    private var s: UInt32

    public init(seed: UInt32) { s = seed | 1 }

    /// A number in 0..<n (n treated as at least 1).
    public mutating func below(_ n: UInt32) -> UInt32 {
        s ^= s << 13
        s ^= s >> 17
        s ^= s << 5
        return s % max(n, 1)
    }

    private mutating func pick(_ letters: [Character]) -> Character {
        letters[Int(below(UInt32(letters.count)))]
    }

    private static let mainlandSecond = Array("ABCDEFGIJKMNOQRSTUVWXYZ")
    private static let aSecond = Array("ABCDEFGHIJK")
    private static let w2x3Second = Array("ABDEFGIJNOQSUVWXYZ")
    private static let suffix2x3First = Array("ABCDEFGHIJKLMNOPQRSTUVWYZ")
    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    private static let shapes: [(Int, Int)] = [(1, 2), (1, 3), (1, 3), (2, 1), (2, 2), (2, 2), (2, 3), (2, 3), (2, 3)]

    /// A random US call in call area `digit`, as the game's `us_call` builds it.
    public mutating func usCall(digit: Int) -> String {
        let index = Int(pick(Array("012345678")).asciiValue! - Character("0").asciiValue!)
        let (prefixLen, suffixLen) = Self.shapes[index]
        var call = ""
        let first: Character
        if prefixLen == 1 {
            first = pick(Array("KNW"))
        } else if suffixLen == 3 {
            first = pick(Array("KW"))
        } else {
            first = pick(Array("KNWA"))
        }
        call.append(first)
        if prefixLen == 2 {
            if first == "A" {
                call.append(pick(Self.aSecond))
            } else if first == "W" && suffixLen == 3 {
                call.append(pick(Self.w2x3Second))
            } else {
                call.append(pick(Self.mainlandSecond))
            }
        }
        call.append(Character(String(digit)))
        for k in 0..<suffixLen {
            let twoByThree = prefixLen == 2 && suffixLen == 3
            call.append(pick(k == 0 && twoByThree ? Self.suffix2x3First : Self.alphabet))
        }
        return call
    }
}

/// Counts for the results screen.
public struct BoxingStats: Sendable {
    public var tells = 0
    public var dodged = 0
    public var punches = 0
    public var clean = 0
    public var damage = 0
    public var taken = 0
    public var uppercuts = 0
    public var knockdowns = 0
    public var knocked = 0
    /// Tries and right per character of `BoxingRules.koch`, this fight only.
    public var tries = [Int](repeating: 0, count: BoxingRules.koch.count)
    public var right = [Int](repeating: 0, count: BoxingRules.koch.count)

    public init() {}

    public var accuracy: Int? { tells > 0 ? dodged * 100 / tells : nil }
    public var cleanPercent: Int? { punches > 0 ? clean * 100 / punches : nil }

    public mutating func count(_ c: Character, right wasRight: Bool) {
        guard let i = BoxingRules.indexOf(c) else { return }
        tries[i] = min(tries[i] + 1, 255)
        right[i] = min(right[i] + (wasRight ? 1 : 0), 255)
    }

    /// Characters sent this fight, worst first: (character, right, tries). Misses first, then the lowest share right, then the most tries.
    public func worst() -> [(Character, Int, Int)] {
        BoxingRules.koch.indices.filter { tries[$0] > 0 }
            .sorted { a, b in
                let ka = right[a] * 1000 / tries[a], kb = right[b] * 1000 / tries[b]
                if ka != kb { return ka < kb }
                if tries[a] != tries[b] { return tries[a] > tries[b] }
                return a < b
            }
            .map { (BoxingRules.koch[$0], right[$0], tries[$0]) }
    }
}

/// Saved progress: how far up the ladder you are, the hint setting, and your best score and wins against each fighter.
public struct BoxingCareer: Equatable, Sendable {
    /// Fighters you can pick: the first is always open, and each win opens the next.
    public var unlocked = 1
    public var hints = true
    /// Best scores in tens of points, so a long fight still fits 16 bits.
    public var best = [Int](repeating: 0, count: BoxingRules.fighters)
    public var wins = [Int](repeating: 0, count: BoxingRules.fighters)

    public static let scoreUnit = 10

    public init(unlocked: Int = 1) { self.unlocked = unlocked }

    /// Records a finished fight; true when it opened the next fighter.
    @discardableResult
    public mutating func finished(fighter: Int, won: Bool, score: Int) -> Bool {
        guard best.indices.contains(fighter) else { return false }
        best[fighter] = max(best[fighter], min(score / Self.scoreUnit, 65535))
        if !won { return false }
        wins[fighter] = min(wins[fighter] + 1, 255)
        let opens = fighter + 1 == unlocked && fighter + 1 < BoxingRules.fighters
        if opens { unlocked += 1 }
        return opens
    }

    public func bestScore(_ fighter: Int) -> Int { best[fighter] * Self.scoreUnit }

    /// Every fighter beaten at least once.
    public var champion: Bool { wins[BoxingRules.fighters - 1] > 0 }
}

/// What happens between the bells.
private enum Duel: Equatable, Sendable {
    /// The next attack starts at the deadline.
    case neutral
    /// The tell is playing; you can already key it back.
    case tell
    /// The tell ended; the punch lands at the deadline.
    case window
    /// The punch is on its way: it lands, or misses you after a dodge, at the deadline.
    case punch(dodged: Bool)
    /// Your counters land until the deadline.
    case open
    /// You armed a star: send the word before the deadline.
    case star
    /// The pause after a punch landed on you.
    case stagger
}

/// One fight against a ladder fighter. Every call that moves time takes the
/// current tick count `now` in milliseconds.
public struct BoxingFight: Sendable {
    public let fighter: Int
    public private(set) var phase: BoxingPhase = .intro
    private var duel: Duel = .neutral
    /// When the current phase or duel step started, and when it ends.
    private var since: UInt32 = 0
    private var due: UInt32?
    public private(set) var round = 1
    private var roundEnds: UInt32 = 0
    /// Round time left while the clock is stopped: before the bell, on the canvas.
    private var roundLeft = UInt32(BoxingRules.roundMs)
    public var foeHp: Int
    public var myHp = BoxingRules.playerHp
    public var stars = 0
    /// Clean counters toward the next star.
    public private(set) var cleanRun = 0
    public private(set) var score = 0
    public private(set) var tell = ""
    /// Your keying of the tell so far.
    public private(set) var answer = ""
    /// The tell is shown: while it plays with hints, and once it is over.
    public private(set) var tellShown = false
    /// The uppercut word, while a star is armed, and what you have sent of it.
    public private(set) var word = ""
    public private(set) var typed = ""
    /// The referee's count over a fighter on the canvas.
    public private(set) var count = 0
    /// The count the opponent gets up at; getUpCount stays down.
    private var getUpAt = 0
    /// What you key to get up.
    public private(set) var getUp: Character = BoxingRules.koch[0]
    private var foeEvent: (BoxingFoe, UInt32)?
    private var meEvent: (BoxingMe, UInt32)?
    public private(set) var callout: (BoxingCallout, UInt32)?
    /// The punching hand of the attack.
    public private(set) var side = BoxingSide.right
    /// Your last punching hand, so jabs alternate.
    private var mySide = BoxingSide.left
    public private(set) var stats = BoxingStats()
    private var rng: BoxingRng
    /// A tell waiting for the view to play it.
    private var play: String?
    /// The view should stop the tell now.
    public var stop = false
    private var hints: Bool
    private var myDitMs: Int
    private var pausedAt: UInt32?
    private let callsign: String
    /// Drill weight per Koch character, from your letter statistics.
    private var weights = [Int](repeating: 8, count: BoxingRules.koch.count)
    /// Characters of the uppercut word keyed with timing better than bad.
    private var starClean = 0
    private var copies: [(Character, Character?)] = []

    /// A fight against ladder fighter `fighter`. `myDitMs` is your keyer's dot length.
    public init(fighter: Int, seed: UInt32, hints: Bool, myDitMs: Int, callsign: String) {
        self.fighter = min(max(fighter, 0), BoxingRules.fighters - 1)
        self.foeHp = BoxingRules.ladder[self.fighter].hp
        self.rng = BoxingRng(seed: seed)
        self.hints = hints
        self.myDitMs = myDitMs
        self.callsign = String(callsign.prefix(BoxingRules.maxWord))
    }

    public var foe: BoxingFighter { BoxingRules.ladder[fighter] }

    /// The duel step's name, for the fixture's snapshots.
    public var duelName: String {
        switch duel {
        case .neutral: return "Neutral"
        case .tell: return "Tell"
        case .window: return "Window"
        case .punch(let dodged): return "Punch:\(dodged)"
        case .open: return "Open"
        case .star: return "Star"
        case .stagger: return "Stagger"
        }
    }

    /// Takes the drill weight of each Koch character, so tells lean on the characters you copy worst.
    public mutating func learn(weights: [Int]) {
        precondition(weights.count == BoxingRules.koch.count)
        self.weights = weights
    }

    public mutating func setHints(_ on: Bool) { hints = on }
    public mutating func setDitMs(_ ms: Int) { myDitMs = ms }

    /// The tell shows while it plays.
    public var hinting: Bool { hints && foe.hint }
    public var paused: Bool { pausedAt != nil }
    /// The opponent is open to counters.
    public var isOpen: Bool { phase == .fight && duel == .open }
    /// A star is armed and its word is on screen.
    public var starring: Bool { phase == .fight && duel == .star }
    /// The tell is playing or waiting for your answer.
    public var telling: Bool { phase == .fight && (duel == .tell || duel == .window) }
    /// The tell is sounding now.
    public var sounding: Bool { phase == .fight && duel == .tell }

    /// Time left to answer the tell once it has played, or to send the uppercut word: (left, whole window).
    public func window(now: UInt32) -> (UInt32, UInt32)? {
        guard let d = due, phase == .fight, duel == .window || duel == .star else { return nil }
        return (BoxingRules.msUntil(pausedAt ?? now, d), d &- since)
    }

    /// Round time left.
    public func clockMs(now: UInt32) -> UInt32 {
        if phase != .fight { return roundLeft }
        return BoxingRules.msUntil(pausedAt ?? now, roundEnds)
    }

    private static func foeEventMs(_ pose: BoxingFoe) -> UInt32 {
        switch pose {
        case .hurt: return 380
        case .block: return 320
        case .rise: return 700
        default: return 0
        }
    }

    private static func meEventMs(_ pose: BoxingMe) -> UInt32 {
        switch pose {
        case .jab: return 240
        case .hook: return 320
        case .combo(let n): return 150 * UInt32(n) + 120
        case .uppercut: return 760
        case .blocked: return 280
        case .miss: return 300
        case .hurt: return 520
        case .rise: return 700
        default: return 0
        }
    }

    /// The opponent's pose at `now`, and when it started.
    public func foePose(now: UInt32) -> (BoxingFoe, UInt32) {
        if let e = foeEvent, now &- e.1 < Self.foeEventMs(e.0) { return e }
        let pose: BoxingFoe
        switch phase {
        case .fight:
            switch duel {
            case .tell, .window: pose = .windup(side)
            case .punch(let dodged): pose = dodged ? .whiff(side) : .punch(side)
            case .open: pose = .open
            case .star: pose = .stunned
            case .neutral, .stagger: pose = .idle
            }
        case .foeDown: pose = getUpAt >= BoxingRules.getUpCount ? .out : .down
        case .meDown: pose = .victory
        case .over(let won, let how):
            if won && how == .decision { pose = .idle } else if won { pose = .out } else { pose = .victory }
        case .intro, .roundStart, .roundEnd: pose = .idle
        }
        return (pose, since)
    }

    /// Your pose at `now`, and when it started.
    public func mePose(now: UInt32) -> (BoxingMe, UInt32) {
        if let e = meEvent, now &- e.1 < Self.meEventMs(e.0) { return e }
        let pose: BoxingMe
        switch phase {
        case .fight:
            if case .punch(let dodged) = duel, dodged { pose = .dodge(side.other) } else { pose = .idle }
        case .meDown: pose = .down
        case .over(let won, let how):
            if won { pose = .victory } else if how == .decision { pose = .idle } else { pose = .down }
        default: pose = .idle
        }
        return (pose, since)
    }

    /// The tell to start playing now.
    public mutating func takePlay() -> String? {
        let p = play
        play = nil
        return p
    }

    /// When `update` should next run.
    public var deadline: UInt32? {
        if pausedAt != nil { return nil }
        let round: UInt32? = phase == .fight ? roundEnds : nil
        if let a = due, let r = round { return BoxingRules.earlier(a, r) }
        return due ?? round
    }

    private mutating func step(_ next: Duel, _ now: UInt32, _ dueAt: UInt32?) {
        duel = next
        since = now
        due = dueAt
    }

    private mutating func enter(_ next: BoxingPhase, _ now: UInt32, _ dueAt: UInt32?) {
        phase = next
        since = now
        due = dueAt
    }

    /// Rings the bell: from the intro card, or between rounds. False when it is not time.
    @discardableResult
    public mutating func start(now: UInt32) -> Bool {
        if phase == .intro {
        } else if phase == .roundEnd && due == nil {
            nextRound()
        } else {
            return false
        }
        enter(.roundStart, now, now &+ UInt32(BoxingRules.roundBannerMs))
        foeEvent = nil
        meEvent = nil
        return true
    }

    public mutating func pause(now: UInt32) {
        if pausedAt != nil { return }
        if case .over = phase { return }
        if phase == .intro { return }
        pausedAt = now
        stop = true
        // A tell cut short is taken back, and a new one starts after the pause.
        if phase == .fight && duel == .tell {
            stats.tells -= 1
            tell = ""
            answer = ""
            tellShown = false
            step(.neutral, now, now &+ 800)
        }
    }

    public mutating func resume(now: UInt32) {
        guard let at = pausedAt else { return }
        pausedAt = nil
        let gap = now &- at
        due = due.map { $0 &+ gap }
        roundEnds = roundEnds &+ gap
        since = since &+ gap
        if let e = foeEvent { foeEvent = (e.0, e.1 &+ gap) }
        if let e = meEvent { meEvent = (e.0, e.1 &+ gap) }
        if let c = callout { callout = (c.0, c.1 &+ gap) }
    }

    private mutating func nextRound() {
        round += 1
        roundLeft = UInt32(BoxingRules.roundMs)
        // The corners patch both fighters up a little.
        let f = foe
        foeHp = min(foeHp + (f.hp - foeHp) / 4, f.hp)
        myHp = min(myHp + (BoxingRules.playerHp - myHp) / 3, BoxingRules.playerHp)
    }

    /// Runs whatever is due at `now`.
    public mutating func update(now: UInt32) {
        if pausedAt != nil { return }
        let isDue = due.map { BoxingRules.reached(now, $0) } ?? false
        switch phase {
        case .roundStart:
            if isDue {
                enter(.fight, now, nil)
                roundEnds = now &+ roundLeft
                step(.neutral, now, now &+ 900)
            }
        case .fight:
            // A punch on its way still lands after the bell.
            var punching = false
            if case .punch = duel { punching = true }
            if BoxingRules.reached(now, roundEnds) && !punching { return bell(now) }
            if isDue { duelDue(now) }
        case .foeDown:
            if isDue { foeCount(now) }
        case .meDown:
            if isDue { myCount(now) }
        case .roundEnd:
            if isDue { due = nil }
        default:
            break
        }
    }

    private mutating func bell(_ now: UInt32) {
        stop = true
        roundLeft = 0
        clearTexts()
        if round >= BoxingRules.rounds {
            let won = points > foePoints
            if won { score += 2000 }
            return finish(won: won, how: .decision, now)
        }
        enter(.roundEnd, now, now &+ UInt32(BoxingRules.roundEndMs))
        duel = .neutral
    }

    private mutating func clearTexts() {
        tell = ""
        answer = ""
        tellShown = false
        word = ""
        typed = ""
    }

    /// The judges' points for you: damage dealt, dodges and knockdowns.
    public var points: Int { stats.damage + 2 * stats.dodged + 10 * stats.knockdowns }

    /// The judges' points for the opponent.
    public var foePoints: Int { stats.taken + 10 * stats.knocked }

    private mutating func duelDue(_ now: UInt32) {
        switch duel {
        case .neutral, .stagger:
            attack(now)
        case .tell:
            break
        case .window:
            let t = Array(tell)
            for c in t.dropFirst(answer.count) { judge(c, nil) }
            callout = (.tooSlow, now)
            throwPunch(now, dodged: false)
        case .punch(let dodged):
            if !dodged { land(now) } else { step(.open, now, now &+ UInt32(foe.openMs)) }
        case .open, .star:
            rest(now)
        }
    }

    private mutating func rest(_ now: UInt32) {
        let wait = foe.restLeastMs + Int(rng.below(UInt32(foe.restExtraMs + 1)))
        word = ""
        typed = ""
        step(.neutral, now, now &+ UInt32(wait))
    }

    /// Starts a tell.
    private mutating func attack(_ now: UInt32) {
        tell = pickTell()
        answer = ""
        tellShown = hinting
        side = rng.below(2) == 0 ? .left : .right
        stats.tells += 1
        play = tell
        step(.tell, now, nil)
    }

    /// The tell finished playing at `now`: the reply window opens.
    public mutating func tellPlayed(now: UInt32) {
        if phase != .fight || duel != .tell || pausedAt != nil { return }
        let keying = tell.reduce(0) { $0 + BoxingRules.units($1) + 3 } * myDitMs
        step(.window, now, now &+ UInt32(foe.reactMs + keying))
    }

    /// A tell for this fighter: its length and characters drawn from the generator.
    public mutating func pickTell() -> String {
        let most = UInt32(foe.maxTell)
        var len = 1 + Int(rng.below(most))
        // Long tells come up less often than short ones.
        if len > 1 && rng.below(3) == 0 { len -= 1 }
        var out = ""
        for _ in 0..<len { out.append(pickChar()) }
        return out
    }

    /// A character of this fighter's set, weighted toward weak ones and the fighter's own.
    public mutating func pickChar() -> Character {
        let f = foe
        let n = f.koch
        let ownFrom = n - f.newChars(index: fighter).count
        let w = weights
        func weight(_ i: Int) -> Int { w[i] * (i >= ownFrom ? 3 : 1) }
        let total = (0..<n).reduce(0) { $0 + weight($1) }
        var r = Int(rng.below(UInt32(total)))
        for i in 0..<n {
            if r < weight(i) { return BoxingRules.koch[i] }
            r -= weight(i)
        }
        return BoxingRules.koch[n - 1]
    }

    /// The opponent throws: you dodged, or it lands at the end of the swing.
    private mutating func throwPunch(_ now: UInt32, dodged: Bool) {
        stop = true
        tellShown = true
        let swing = dodged ? BoxingRules.whiffMs : BoxingRules.punchMs
        step(.punch(dodged: dodged), now, now &+ UInt32(swing))
    }

    private mutating func land(_ now: UInt32) {
        let power = foe.power
        myHp = max(myHp - power, 0)
        stats.taken += power
        cleanRun = 0
        meEvent = (.hurt, now)
        if myHp == 0 { return knockedDown(now) }
        let wait = BoxingRules.staggerMs + foe.restLeastMs / 2
        step(.stagger, now, now &+ UInt32(wait))
    }

    /// A character you keyed, with the worst timing quality in it.
    public mutating func key(_ c: Character, quality: BoxingQuality, now: UInt32) {
        if pausedAt != nil { return }
        switch phase {
        case .fight: break
        case .meDown: return tryGetUp(c, now)
        default: return
        }
        switch duel {
        case .tell, .window:
            answerTell(c, now)
        case .open:
            counter(c, quality, now)
        case .star:
            starChar(c, quality, now)
        case .neutral, .stagger:
            // Into a raised guard.
            mySide = mySide.other
            meEvent = (.blocked(mySide), now)
            foeEvent = (.block, now)
            callout = (.blocked, now)
        case .punch:
            break
        }
    }

    /// You keyed a pattern no character uses.
    public mutating func keyUnknown(now: UInt32) {
        if pausedAt != nil { return }
        if phase == .meDown { return tryGetUp("*", now) }
        guard phase == .fight else { return }
        switch duel {
        case .tell, .window:
            answerTell("*", now)
        case .open:
            meEvent = (.miss, now)
            callout = (.missed, now)
        case .star:
            starChar("*", .bad, now)
        default:
            break
        }
    }

    private mutating func answerTell(_ c: Character, _ now: UInt32) {
        let at = answer.count
        let t = Array(tell)
        guard at < t.count else { return }
        let want = t[at]
        answer.append(c)
        if c != want {
            judge(want, c)
            for rest in t.dropFirst(at + 1) { judge(rest, nil) }
            callout = (.wrong(sent: c), now)
            return throwPunch(now, dodged: false)
        }
        judge(want, want)
        if answer.count == tell.count {
            stats.dodged += 1
            // A dodge before the tell even ends scores more.
            score += duel == .tell ? 80 : 50
            callout = (.dodge, now)
            throwPunch(now, dodged: true)
        }
    }

    private mutating func counter(_ c: Character, _ quality: BoxingQuality, _ now: UInt32) {
        guard let punch = BoxingRules.punchOf(c) else { return }
        let hit = BoxingRules.damage(c, quality)
        stats.punches += 1
        mySide = mySide.other
        let pose: BoxingMe
        switch punch {
        case .jab: pose = .jab(mySide)
        case .hook: pose = .hook(mySide)
        case .combo(let n): pose = .combo(n)
        }
        meEvent = (pose, now)
        // A hook from your left hand snaps the head to your right.
        foeEvent = (.hurt(mySide.other), now)
        score += hit * 10
        if quality == .good {
            stats.clean += 1
            score += 20
            cleanRun += 1
            callout = (.clean, now)
            if cleanRun >= BoxingRules.cleanPerStar {
                cleanRun = 0
                if stars < BoxingRules.maxStars {
                    stars += 1
                    callout = (.star, now)
                }
            }
        } else {
            callout = nil
        }
        hitFoe(hit, uppercut: false, now)
    }

    private mutating func hitFoe(_ hit: Int, uppercut: Bool, _ now: UInt32) {
        foeHp = max(foeHp - hit, 0)
        stats.damage += hit
        if foeHp == 0 { foeKnockedDown(uppercut: uppercut, now) }
    }

    /// Arms a star, if you have one: the opponent is stunned while you send the word. False when no star can be armed now.
    @discardableResult
    public mutating func star(now: UInt32) -> Bool {
        var calm = false
        switch duel {
        case .neutral, .open, .stagger: calm = true
        default: break
        }
        guard phase == .fight, stars > 0, pausedAt == nil, calm else { return false }
        stars -= 1
        word = pickWord()
        typed = ""
        starClean = 0
        callout = nil
        foeEvent = nil
        let time = BoxingRules.starStartMs + BoxingRules.starCharMs * word.count
        step(.star, now, now &+ UInt32(time))
        return true
    }

    /// A word of this fighter's characters, your callsign, or a callsign.
    public mutating func pickWord() -> String {
        let set = Set(foe.set)
        let fits = BoxingRules.words.filter { $0.allSatisfy { set.contains($0) } }
        let count = fits.count
        let call = count == 0 || rng.below(4) == 0
        if !call {
            let i = Int(rng.below(UInt32(count)))
            return fits.indices.contains(i) ? fits[i] : ""
        }
        // Early fighters know too few characters for most calls: send your own.
        let yours = !callsign.isEmpty && (fighter < 4 || rng.below(2) == 0)
        if yours { return callsign }
        let digit = Int(rng.below(10))
        return String(rng.usCall(digit: digit).prefix(BoxingRules.maxWord))
    }

    private mutating func starChar(_ c: Character, _ quality: BoxingQuality, _ now: UInt32) {
        let at = typed.count
        let w = Array(word)
        guard at < w.count else { return }
        let want = w[at]
        typed.append(c)
        if c != want {
            meEvent = (.miss, now)
            callout = (.missed, now)
            return rest(now)
        }
        if quality != .bad { starClean += 1 }
        if typed.count < word.count { return }
        let clean = starClean * 100 / word.count
        let full = BoxingRules.uppercutDamage + 2 * word.count
        let hit = clean >= 80 ? full : full * 3 / 4
        stats.uppercuts += 1
        score += 500
        meEvent = (.uppercut, now)
        foeEvent = (.hurt(.left), now)
        callout = (.uppercut, now)
        rest(now)
        hitFoe(hit, uppercut: true, now)
    }

    private mutating func foeKnockedDown(uppercut: Bool, _ now: UInt32) {
        stop = true
        stats.knockdowns += 1
        score += 1000
        roundLeft = BoxingRules.msUntil(now, roundEnds)
        count = 0
        callout = (.down, now)
        clearTexts()
        let kd = stats.knockdowns
        // Each knockdown keeps the opponent down longer. An uppercut that empties the health bar,
        // or the third knockdown, keeps him down for good.
        getUpAt = (uppercut || kd >= BoxingRules.knockdownsToKo) ? BoxingRules.getUpCount : 2 + 2 * kd
        foeEvent = nil
        duel = .neutral
        enter(.foeDown, now, now &+ UInt32(BoxingRules.countMs))
    }

    private mutating func foeCount(_ now: UInt32) {
        count += 1
        if count >= BoxingRules.getUpCount {
            score += 5000 + Int(roundLeft) / 1000 * 10
            let how: BoxingOutcome = stats.knockdowns >= BoxingRules.knockdownsToKo ? .tko : .ko
            return finish(won: true, how: how, now)
        }
        if count < getUpAt {
            due = now &+ UInt32(BoxingRules.countMs)
            return
        }
        let share = 60 - 15 * stats.knockdowns
        foeHp = max(foe.hp * share / 100, 10)
        foeEvent = (.rise, now)
        callout = (.up, now)
        enter(.fight, now, nil)
        roundEnds = now &+ roundLeft
        rest(now)
    }

    private mutating func knockedDown(_ now: UInt32) {
        stop = true
        stats.knocked += 1
        meEvent = nil
        roundLeft = BoxingRules.msUntil(now, roundEnds)
        count = 0
        callout = (.down, now)
        clearTexts()
        duel = .neutral
        if stats.knocked >= BoxingRules.knockdownsToKo { return finish(won: false, how: .tko, now) }
        getUp = weakestKnown()
        enter(.meDown, now, now &+ UInt32(BoxingRules.countMs))
    }

    /// The character you copy worst among the ones this fighter sends (the last of equals, as the original picks).
    private mutating func weakestKnown() -> Character {
        let n = foe.koch
        var worst = 0
        var bestW = Int.min
        var bestR = Int.min
        for i in 0..<n {
            let r = Int(rng.below(4))
            if weights[i] > bestW || (weights[i] == bestW && r >= bestR) {
                bestW = weights[i]
                bestR = r
                worst = i
            }
        }
        return BoxingRules.koch[worst]
    }

    private mutating func myCount(_ now: UInt32) {
        count += 1
        if count >= BoxingRules.getUpCount { return finish(won: false, how: .ko, now) }
        due = now &+ UInt32(BoxingRules.countMs)
    }

    private mutating func tryGetUp(_ c: Character, _ now: UInt32) {
        judge(getUp, c)
        if c != getUp {
            callout = (.wrong(sent: c), now)
            return
        }
        let share = 70 - 20 * stats.knocked
        myHp = max(BoxingRules.playerHp * share / 100, 15)
        meEvent = (.rise, now)
        callout = (.up, now)
        enter(.fight, now, nil)
        roundEnds = now &+ roundLeft
        rest(now)
    }

    private mutating func finish(won: Bool, how: BoxingOutcome, _ now: UInt32) {
        stop = true
        foeEvent = nil
        meEvent = nil
        enter(.over(won: won, how: how), now, nil)
    }

    /// One judged character: `copied` is nil when it was missed.
    private mutating func judge(_ sent: Character, _ copied: Character?) {
        stats.count(sent, right: copied == sent)
        // An unknown pattern is a miss, not a mixup.
        let known = copied.flatMap { BoxingRules.indexOf($0) != nil ? $0 : nil }
        if copies.count == 8 { copies.removeFirst() }
        copies.append((sent, known))
    }

    /// Takes the characters judged since the last call, for the letter statistics: (sent, copied), copied nil when missed.
    public mutating func takeCopies(_ each: (Character, Character?) -> Void) {
        for (sent, copied) in copies { each(sent, copied) }
        copies.removeAll()
    }
}
