import Foundation

// The hunt simulator (#313), the pure logic: a seeded random source, the copy
// model (per-character decode odds from level over noise, time overlap and
// pitch offset), the hunters, the activator's state machine and the grader.
// docs/operating-procedure-design.md ("The hunt simulator") is the spec; every
// number below is pinned by fixtures/hunt-model.json and fixtures/hunt-sessions.json,
// which the Kotlin HuntSimTest in android/ and desktop/ reads too. Twin of
// android/…/morsekit/HuntSim.kt and desktop/…/morsekit/HuntSim.kt. Not ranked
// and no leaderboard entry (maintainer, 2026-10-03): the grading is about
// procedure, not speed.
//
// Operations are kept to + - * / min max on doubles, in a fixed order, so the
// three trees agree to the last bit, not just to a tolerance.

/// SplitMix64: the same sequence in all three trees for one seed.
public struct HuntRandom: Sendable {
    private var state: UInt64

    public init(seed: UInt64) { state = seed }

    public mutating func next64() -> UInt64 {
        state = state &+ 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    /// In [0, 1): the top 53 bits over 2^53.
    public mutating func nextDouble() -> Double {
        Double(next64() >> 11) / 9007199254740992.0
    }

    /// Inclusive on both ends.
    public mutating func nextInt(_ lo: Int, _ hi: Int) -> Int {
        lo + Int(nextDouble() * Double(hi - lo + 1))
    }

    public mutating func range(_ a: Double, _ b: Double) -> Double {
        a + (b - a) * nextDouble()
    }
}

public enum HuntType: String, CaseIterable, Sendable {
    case good, repeater, tailEnder, zeroBeater, questionSender, wrongCall, tuneUp, qrp

    /// Out of 100, in this order.
    public var weight: Int {
        switch self {
        case .good: return 40
        case .repeater: return 12
        case .tailEnder: return 10
        case .zeroBeater: return 10
        case .questionSender: return 6
        case .wrongCall: return 8
        case .tuneUp: return 4
        case .qrp: return 10
        }
    }
}

/// What kind of window the activator has just opened.
public enum HuntKind: String, CaseIterable, Sendable {
    /// After CQ, QRZ?, SRI QRZ? or a finished contact: everyone may call.
    case calling
    /// After a partial or a doubtful call with "?": those it fits answer.
    case query
    /// The picked station answers with its exchange (or the activator asked AGN?).
    case exchange
    /// The activator's TU 73 E E to you: your sign-off.
    case close
    /// The activator sent <AS>: wait.
    case `as`
}

/// The difficulty settings.
public struct HuntConfig: Equatable, Sendable {
    public var pileupSize: Int = 4
    public var wpmMin: Double = 18
    public var wpmMax: Double = 24
    public var activatorWpm: Double = 20
    public var qsb: Bool = false
    /// Band noise, 0 (none) to about 0.3.
    public var qrn: Double = 0
    /// Percent of hunters you cannot hear at all.
    public var inaudiblePercent: Double = 20
    /// Chance a character the activator fails to copy comes out as a wrong one, not a gap.
    public var bustRate: Double = 0.1
    public var toneSpread: Double = 250
    /// How strong you are at the activator.
    public var youLevel: Double = 0.6

    public init() {}

    public static let easy: HuntConfig = {
        var c = HuntConfig()
        c.pileupSize = 2; c.wpmMin = 14; c.wpmMax = 18; c.activatorWpm = 16
        c.inaudiblePercent = 0; c.bustRate = 0
        return c
    }()
    public static let medium = HuntConfig()
    public static let hard: HuntConfig = {
        var c = HuntConfig()
        c.pileupSize = 8; c.wpmMin = 22; c.wpmMax = 30; c.activatorWpm = 26
        c.qsb = true; c.qrn = 0.15; c.inaudiblePercent = 40; c.bustRate = 0.25
        return c
    }()
    public static let minPileup = 1
    public static let maxPileup = 10
}

public struct HuntHunter: Equatable, Sendable {
    public let id: Int
    public let call: String
    public let type: HuntType
    public let state: String
    public let wpm: Double
    /// Hertz from the activator's frequency.
    public let offset: Double
    /// Strength at the activator, 0–1.
    public let levelAct: Double
    /// Strength at you, 0–1; 0 = you cannot hear them.
    public let levelYou: Double
    public let qsb: Bool
    public let repeats: Int
}

/// One hunter transmission in a window.
public struct HuntVoice: Equatable, Sendable {
    public let id: Int
    public let call: String
    public let type: HuntType
    public let text: String
    public let wpm: Double
    public let offsetHz: Double
    public let levelAct: Double
    public let levelYou: Double
    public let qsb: Bool
    /// Seconds after the activator stops.
    public let delay: Double
}

/// What the activator just sent, and who answers.
public struct HuntTurn: Equatable, Sendable {
    public let round: Int
    public let kind: HuntKind
    public let text: String
    public let query: String
    public let voices: [HuntVoice]
    public let wpm: Double
}

/// What you keyed in a window: text, your speed, seconds after the activator stopped, and your XIT offset.
public struct HuntYouSend: Equatable, Sendable {
    public let text: String
    public let wpm: Double
    public let delay: Double
    public let offsetHz: Double
    public init(text: String, wpm: Double, delay: Double, offsetHz: Double) {
        self.text = text; self.wpm = wpm; self.delay = delay; self.offsetHz = offsetHz
    }
}

/// One graded point, tied to the lesson that teaches it.
public struct HuntPoint: Equatable, Sendable {
    public let code: String
    public let good: Bool
    public let lesson: OpLesson
}

/// Who sent what and how the model copied it. `who` is "you" or a hunter's id.
public struct HuntHeard: Equatable, Sendable {
    public let who: String
    public let sent: String
    public let decoded: String
}

/// One finished window, for the debrief.
public struct HuntRecord: Equatable, Sendable {
    public let round: Int
    public let turn: HuntTurn
    public let you: HuntYouSend?
    public let late: Bool
    public let activatorHeard: [HuntHeard]
    public let youHeard: [HuntHeard]
    public let grade: [HuntPoint]
}

/// What the grader needs to know about a window.
public struct HuntGradeContext: Sendable {
    public let kind: HuntKind
    public let query: String
    public let queryForYou: Bool
    public let pickedIsYou: Bool
    public let pileup: Bool
    public let myCall: String
    public let myState: String
    public let actCall: String
    public let pickedCall: String?
    public let activatorText: String
    public init(kind: HuntKind, query: String, queryForYou: Bool, pickedIsYou: Bool, pileup: Bool,
                myCall: String, myState: String, actCall: String, pickedCall: String?, activatorText: String) {
        self.kind = kind; self.query = query; self.queryForYou = queryForYou; self.pickedIsYou = pickedIsYou
        self.pileup = pileup; self.myCall = myCall; self.myState = myState; self.actCall = actCall
        self.pickedCall = pickedCall; self.activatorText = activatorText
    }
}

/// The grader's view of what you keyed.
public struct HuntGradeSend: Sendable {
    public let text: String
    public let delay: Double
    public let offsetHz: Double
    public let late: Bool
    public init(text: String, delay: Double, offsetHz: Double, late: Bool) {
        self.text = text; self.delay = delay; self.offsetHz = offsetHz; self.late = late
    }
}

/// A transmission as the copy model sees it.
public struct HuntTx: Sendable {
    public let who: Int
    public let text: String
    public let wpm: Double
    public let start: Double
    public let level: Double
    public let pitch: Double
    public let end: Double
    public init(who: Int, text: String, wpm: Double, start: Double, level: Double, pitch: Double) {
        self.who = who; self.text = text; self.wpm = wpm; self.start = start; self.level = level; self.pitch = pitch
        self.end = HuntModel.txEnd(text, wpm: wpm, start: start)
    }
}

public struct HuntDecoded: Equatable, Sendable {
    public let text: String
    public let minOdds: Double
}

public enum HuntExchangeForm: String, Sendable { case primary, bk, none }

/// The copy model, the query rules and the grader.
public enum HuntModel {
    public static let you = -1

    public static let noiseFloor = 0.05
    public static let maxOdds = 0.98
    public static let maskWeight = 0.9
    public static let zeroBeatHz = 20.0
    public static let sepFarStart = 0.6
    public static let sepSlope = 0.0025
    public static let sepMin = 0.15
    public static let qsbFactor = 0.6
    public static let doubtOdds = 0.6
    public static let listenSeconds = 3.5
    public static let asProb = 0.1
    public static let sriAfter = 2
    public static let maxExchangeTries = 3
    public static let maxQueries = 2
    public static let maxRounds = 60
    public static let callProb = 0.8
    public static let pickJitter = 0.5
    public static let pileupMin = 3
    public static let offsetMinHz = 20.0
    public static let offsetMaxHz = 100.0

    public static let prefixes = ["K", "W", "N", "AA", "AB", "KC", "KD", "KE", "KF", "WA", "WB", "WD"]
    public static let states = ["AL", "FL", "GA", "TN", "NC", "OH", "WI", "TX", "CO", "NY"]

    // Text

    public static func norm(_ t: String) -> String {
        t.uppercased().split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    private static func words(_ t: String) -> [String] {
        let n = norm(t)
        return n.isEmpty ? [] : n.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
    }

    private static func stripQ(_ q: String) -> String {
        var s = OperatingProcedure.normalizeCall(q)
        while s.hasSuffix("?") { s.removeLast() }
        return s
    }

    static func lev1(_ a0: String, _ b0: String) -> Bool {
        var a = Array(a0)
        var b = Array(b0)
        if a == b { return false }
        if abs(a.count - b.count) > 1 { return false }
        if a.count == b.count {
            var diff = 0
            for i in 0..<a.count where a[i] != b[i] { diff += 1 }
            return diff == 1
        }
        if a.count > b.count { swap(&a, &b) }
        for i in 0..<b.count {
            var c = b
            c.remove(at: i)
            if c == a { return true }
        }
        return false
    }

    /// A full-looking query ("K1ABD?") one edit from your call.
    public static func nearMissQuery(_ q: String, call: String) -> Bool {
        let s = stripQ(q)
        return s.count >= 4 && lev1(s, call)
    }

    /// Whether a hunter with `call` should answer the activator's `query`: the partial rule, or a near miss with "?".
    public static func answers(_ query: String, call: String) -> Bool {
        OperatingProcedure.partialMatches(query, call: call) || nearMissQuery(query, call: call)
    }

    /// The query's characters occur in the call in order, with anything between.
    public static func looseMatch(_ query: String, call: String) -> Bool {
        let s = Array(stripQ(query))
        var i = 0
        for ch in call where i < s.count && s[i] == ch { i += 1 }
        return i == s.count
    }

    // Timing

    private static func units(_ c: Character) -> Int {
        guard let p = MorseCode.table[c] ?? MorseCode.optionalPunctuation[c] else { return 0 }
        let dits = p.filter { $0 == "." }.count
        let dahs = p.count - dits
        return dits + 3 * dahs + p.count - 1
    }

    /// Each non-space character with its start and end second.
    public struct CharSpan: Equatable, Sendable {
        public let ch: Character
        public let start: Double
        public let end: Double
    }

    public static func charSpans(_ text: String, wpm: Double, start: Double) -> [CharSpan] {
        let unit = 1.2 / wpm
        var cum = 0
        var prev = false
        var out: [CharSpan] = []
        for c in text {
            if c == " " { cum += 7; prev = false; continue }
            if prev { cum += 3 }
            let s = cum
            cum += units(c)
            out.append(CharSpan(ch: c, start: start + Double(s) * unit, end: start + Double(cum) * unit))
            prev = true
        }
        return out
    }

    public static func txEnd(_ text: String, wpm: Double, start: Double) -> Double {
        charSpans(text, wpm: wpm, start: start).last?.end ?? start
    }

    // Copy model

    /// How much a caller `diffHz` away blurs into this one: 1 within zero beat, less the further off.
    public static func sep(_ diffHz: Double) -> Double {
        diffHz <= zeroBeatHz ? 1.0 : max(sepMin, sepFarStart - (diffHz - zeroBeatHz) * sepSlope)
    }

    public struct Other: Sendable {
        public let overlap: Double
        public let level: Double
        public let diffHz: Double
        public init(overlap: Double, level: Double, diffHz: Double) {
            self.overlap = overlap; self.level = level; self.diffHz = diffHz
        }
    }

    public static func oddsFor(level: Double, qrn: Double, others: [Other]) -> Double {
        var o = min(maxOdds, level / (level + noiseFloor + qrn))
        for x in others {
            let mask = min(1.0, 2.0 * x.level / (x.level + level))
            o = o * (1.0 - maskWeight * x.overlap * sep(x.diffHz) * mask)
        }
        return o
    }

    public static func effLevel(_ level: Double, qsb: Bool) -> Double {
        level * (qsb ? qsbFactor : 1.0)
    }

    /// How the listener copies transmission `i` of `txs`: a char each, or a "?" gap, or (at the bust rate) a wrong one.
    public static func decode(_ i: Int, _ txs: [HuntTx], qrn: Double, bust: Double, rng: inout HuntRandom) -> HuntDecoded {
        let me = txs[i]
        let spans = charSpans(me.text, wpm: me.wpm, start: me.start)
        var k = 0
        var mn = 1.0
        var out = ""
        for c in me.text {
            if c == " " { out.append(" "); continue }
            let cs = spans[k].start
            let ce = spans[k].end
            k += 1
            var others: [Other] = []
            for j in 0..<txs.count where j != i {
                let o = txs[j]
                let ov = max(0.0, min(ce, o.end) - max(cs, o.start)) / (ce - cs)
                others.append(Other(overlap: ov, level: o.level, diffHz: abs(me.pitch - o.pitch)))
            }
            let od = oddsFor(level: me.level, qrn: qrn, others: others)
            mn = min(mn, od)
            let u = rng.nextDouble()
            if u < od {
                out.append(c)
            } else {
                let v = rng.nextDouble()
                out.append(v < bust ? OperatingProcedure.shift(c, by: 1) : "?")
            }
        }
        return HuntDecoded(text: out, minOdds: mn)
    }

    /// Repeated copies (a repeater's CALL CALL) fill each other's gaps, position by position.
    public static func mergeCopies(_ copies: [String]) -> String {
        let cs = copies.map { Array($0) }
        let n = cs[0].count
        if cs.contains(where: { $0.count != n }) { return copies[0] }
        var out = ""
        for k in 0..<n {
            var ch: Character = "?"
            for c in cs where c[k] != "?" { ch = c[k]; break }
            out.append(ch)
        }
        return out
    }

    public static func candidateOf(_ decoded: String) -> String {
        let w = decoded.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        return w.count > 1 ? mergeCopies(w) : w[0]
    }

    /// Worth acting on: three characters or more, with a decoded digit and a decoded letter.
    public static func isCandidate(_ s: String) -> Bool {
        s.count >= 3 && s.contains(where: { $0 >= "0" && $0 <= "9" }) && s.contains(where: { $0 >= "A" && $0 <= "Z" })
    }

    /// The longest unbroken run of decoded characters, if it has two or more; the first on ties.
    public static func longestRun(_ s: String) -> String {
        var best = ""
        var cur = ""
        for c in s + "?" {
            if c == "?" {
                if cur.count > best.count { best = cur }
                cur = ""
            } else {
                cur.append(c)
            }
        }
        return best.count >= 2 ? best : ""
    }

    // Exchange and sign-off

    /// `5NN ST 73` or `5NN ST BK`, after any leading calls and DE.
    public static func exchangeForm(_ text: String, state: String, myCall: String, actCall: String) -> HuntExchangeForm {
        var toks = words(text)
        while let f = toks.first, f == myCall || f == actCall || f == "DE" { toks.removeFirst() }
        if toks.count == 3, toks[0] == "5NN" || toks[0] == "599", toks[1] == state {
            if toks[2] == "73" { return .primary }
            if toks[2] == "BK" { return .bk }
        }
        return .none
    }

    /// The activator logs it: a copy with no gap or bust that holds a report and your state.
    public static func exchangeAccepts(_ decoded: String, state: String) -> Bool {
        let toks = words(decoded)
        return !decoded.contains("?") && toks.contains(where: { $0 == "5NN" || $0 == "599" }) && toks.contains(state)
    }

    public static func signoffForm(_ text: String, myCall: String) -> Bool {
        var toks = words(text)
        while let f = toks.first, f == myCall { toks.removeFirst() }
        return toks == ["E", "E"] || toks == ["73", "E", "E"]
    }

    // Hunters

    public static func makeHunter(_ cfg: HuntConfig, _ rng: inout HuntRandom, id: Int, taken: [String]) -> HuntHunter {
        var call = ""
        while true {
            let pre = prefixes[rng.nextInt(0, prefixes.count - 1)]
            let dig = rng.nextInt(0, 9)
            let n = rng.nextInt(2, 3)
            var suf = ""
            for _ in 0..<n {
                suf.append(Character(UnicodeScalar(UInt8(65 + rng.nextInt(0, 25)))))
            }
            call = "\(pre)\(dig)\(suf)"
            if !taken.contains(call) { break }
        }
        let roll = rng.nextInt(0, 99)
        var acc = 0
        var type = HuntType.good
        for t in HuntType.allCases {
            acc += t.weight
            if roll < acc { type = t; break }
        }
        let state = states[rng.nextInt(0, states.count - 1)]
        let wpm = rng.range(cfg.wpmMin, cfg.wpmMax)
        let spread = rng.range(-cfg.toneSpread, cfg.toneSpread)
        let offset = type == .zeroBeater ? 0.0 : spread
        let a = rng.nextDouble()
        let la = type == .qrp ? 0.04 + 0.06 * a : 0.25 + 0.75 * a
        let yf = rng.range(0.6, 1.2)
        var ly = min(1.0, la * yf)
        let h = rng.nextDouble()
        if h < cfg.inaudiblePercent / 100.0 { ly = 0.0 }
        let q = rng.nextDouble()
        let qsb = cfg.qsb && q < 0.5
        let rp = rng.nextInt(2, 3)
        let repeats = type == .repeater ? rp : 1
        return HuntHunter(id: id, call: call, type: type, state: state, wpm: wpm, offset: offset,
                          levelAct: la, levelYou: ly, qsb: qsb, repeats: repeats)
    }

    public static func callText(_ h: HuntHunter) -> String {
        Array(repeating: h.call, count: h.repeats).joined(separator: " ")
    }

    // The grader

    /// Grade one window. `you` is what you keyed there, or nil for silence.
    public static func grade(_ ctx: HuntGradeContext, _ you: HuntGradeSend?) -> [HuntPoint] {
        var out: [HuntPoint] = []
        func add(_ code: String, _ good: Bool, _ lesson: OpLesson) { out.append(HuntPoint(code: code, good: good, lesson: lesson)) }
        let me = ctx.myCall
        guard let you = you, !norm(you.text).isEmpty else {
            if ctx.kind == .query && ctx.queryForYou && answers(ctx.query, call: me) { add("partial.silent", false, .partial) }
            if ctx.kind == .exchange && ctx.pickedIsYou { add("exchange.silent", false, .exchange) }
            if ctx.kind == .close { add("signoff.missing", false, .exchange) }
            return out
        }
        let text = norm(you.text)
        let toks = text.split(separator: " ").map(String.init)
        if you.late { add("timing.late", false, .when); return out }
        func offsetCheck() {
            let off = abs(you.offsetHz)
            if ctx.pileup {
                if off < offsetMinHz { add("offset.zeroBeat", false, .offset) }
                else if off > offsetMaxHz { add("offset.far", false, .offset) }
                else { add("offset.ok", true, .offset) }
            } else {
                if off > offsetMaxHz { add("offset.far", false, .offset) }
                else { add("offset.ok", true, .offset) }
            }
        }
        func callCheck(_ goodCode: String) {
            let n = toks.filter { $0 == me }.count
            if n == 0 { add("call.missing", false, .once) }
            else if n > 1 { add("call.repeated", false, .once) }
            else if toks.count > 1 { add("call.extra", false, .once) }
            else { add(goodCode, true, .once) }
        }
        switch ctx.kind {
        case .as:
            add("as.ignored", false, .signals)
        case .calling:
            add("timing.ok", true, .when); callCheck("call.once"); offsetCheck()
        case .query:
            if answers(ctx.query, call: me) {
                callCheck(!nearMissQuery(ctx.query, call: me) || OperatingProcedure.partialMatches(ctx.query, call: me)
                          ? "partial.answered" : "near.answered")
                offsetCheck()
            } else {
                add("partial.notMine", false, .partial)
            }
        case .exchange:
            if ctx.pickedIsYou {
                let f = exchangeForm(text, state: ctx.myState, myCall: me, actCall: ctx.actCall)
                if f != .none { add("exchange.ok", true, .exchange) } else { add("exchange.form", false, .exchange) }
            } else if let pc = ctx.pickedCall, !pc.isEmpty, pc != me, lev1(pc, me), !ctx.activatorText.contains("?") {
                add("near.noQuestion", false, .me)
            } else {
                add("timing.over", false, .when)
            }
        case .close:
            if signoffForm(text, myCall: me) { add("signoff.ok", true, .exchange) } else { add("signoff.form", false, .exchange) }
        }
        return out
    }
}

public enum HuntOutcome: String, Sendable { case logged, timeout, quit }

/// One run of the simulator: you against a pileup, for one contact. `respond`
/// takes what you keyed in the current window (or nil) and opens the next one.
public final class HuntSession {
    public let config: HuntConfig
    public let myCall: String
    public let myState: String
    private var rng: HuntRandom
    private let actor: OperatingProcedure.Station
    public private(set) var pool: [HuntHunter] = []
    private var nextId = 0
    private var failed = 0
    private var queries = 0
    private var exTries = 0
    private var queryFor: Int?
    private var lastAS = false
    private var pending = ""

    /// The call the activator is working, if any.
    public private(set) var pickedCall: String?
    public private(set) var round = 0
    public private(set) var ended = false
    public private(set) var outcome: HuntOutcome?
    public private(set) var records: [HuntRecord] = []
    public private(set) var turn: HuntTurn

    public var activatorCall: String { actor.call }
    public var activatorState: String { actor.state }

    public init(config: HuntConfig, seed: UInt64, myCall: String, myState: String) {
        self.config = config
        self.myCall = myCall
        self.myState = myState
        self.rng = HuntRandom(seed: seed)
        self.actor = OperatingProcedure.activator(for: myCall)
        // Placeholder until the pool exists; replaced below.
        self.turn = HuntTurn(round: 0, kind: .calling, text: "", query: "", voices: [], wpm: config.activatorWpm)
        for _ in 0..<config.pileupSize { addHunter() }
        self.turn = makeTurn(.calling, "CQ POTA DE \(actor.call) K")
    }

    private func addHunter() {
        let taken = pool.map { $0.call } + [myCall]
        pool.append(HuntModel.makeHunter(config, &rng, id: nextId, taken: taken))
        nextId += 1
    }

    private func makeTurn(_ kind: HuntKind, _ text: String, query: String = "") -> HuntTurn {
        var v: [(HuntHunter, String, Double)] = []
        switch kind {
        case .calling:
            for h in pool {
                let u = rng.nextDouble()
                if u >= HuntModel.callProb { continue }
                let t: String
                switch h.type {
                case .repeater: t = HuntModel.callText(h)
                case .questionSender: t = "?"
                case .tuneUp: t = "TTTT"
                default: t = h.call
                }
                v.append((h, t, rng.range(0.2, 1.4)))
            }
        case .query:
            for h in pool {
                if HuntModel.answers(query, call: h.call) || (h.type == .wrongCall && HuntModel.looseMatch(query, call: h.call)) {
                    v.append((h, HuntModel.callText(h), rng.range(0.2, 1.4)))
                }
            }
        case .exchange:
            let ph = pool.first(where: { $0.call == pickedCall })
            if let ph = ph { v.append((ph, "5NN \(ph.state) 73", rng.range(0.2, 0.5))) }
            for h in pool where h.type == .tailEnder && h.id != ph?.id {
                v.append((h, h.call, rng.range(0.0, 0.4)))
            }
        default:
            break
        }
        let voices = v.map { (h, t, d) in
            HuntVoice(id: h.id, call: h.call, type: h.type, text: t, wpm: h.wpm, offsetHz: h.offset,
                      levelAct: h.levelAct, levelYou: h.levelYou, qsb: h.qsb, delay: d)
        }
        return HuntTurn(round: round, kind: kind, text: text, query: query, voices: voices, wpm: config.activatorWpm)
    }

    private func toCalling(_ text: String) {
        if !lastAS && rng.nextDouble() < HuntModel.asProb {
            lastAS = true
            pending = text
            turn = makeTurn(.as, "<AS>")
        } else {
            lastAS = false
            turn = makeTurn(.calling, text)
        }
    }

    private func fail() {
        failed += 1
        queries = 0
        pickedCall = nil
        let text: String
        if failed >= HuntModel.sriAfter { failed = 0; text = "SRI QRZ?" } else { text = "QRZ?" }
        toCalling(text)
    }

    private func startExchange(_ call: String) {
        pickedCall = call
        exTries = 0
        queries = 0
        turn = makeTurn(.exchange, "\(call) 5NN \(actor.state) \(actor.state) BK")
    }

    /// Stop early: the run ends as `.quit`.
    public func quit() {
        if !ended { ended = true; outcome = .quit }
    }

    /// Whether the pileup is big enough that the offset rule is 20–100 Hz.
    public var isPileup: Bool { pool.count >= HuntModel.pileupMin }

    public func respond(_ you: HuntYouSend?) {
        if ended { return }
        let t = turn
        round += 1
        var txs: [HuntTx] = []
        for v in t.voices {
            txs.append(HuntTx(who: v.id, text: v.text, wpm: v.wpm, start: v.delay,
                              level: HuntModel.effLevel(v.levelAct, qsb: v.qsb), pitch: v.offsetHz))
        }
        var yourIndex = -1
        var late = false
        if let you = you, !HuntModel.norm(you.text).isEmpty {
            if you.delay > HuntModel.listenSeconds {
                late = true
            } else {
                txs.append(HuntTx(who: HuntModel.you, text: HuntModel.norm(you.text), wpm: you.wpm, start: you.delay,
                                  level: config.youLevel, pitch: you.offsetHz))
                yourIndex = txs.count - 1
            }
        }
        var dec: [HuntDecoded] = []
        for i in 0..<txs.count {
            dec.append(HuntModel.decode(i, txs, qrn: config.qrn, bust: config.bustRate, rng: &rng))
        }
        let hv = t.voices.filter { $0.levelYou > 0.0 }
        let htx = hv.map {
            HuntTx(who: $0.id, text: $0.text, wpm: $0.wpm, start: $0.delay,
                   level: HuntModel.effLevel($0.levelYou, qsb: $0.qsb), pitch: $0.offsetHz)
        }
        var ydec: [HuntDecoded] = []
        for i in 0..<htx.count {
            ydec.append(HuntModel.decode(i, htx, qrn: config.qrn, bust: config.bustRate, rng: &rng))
        }
        let pickedIsYou = pickedCall == myCall
        let ctx = HuntGradeContext(
            kind: t.kind, query: t.query, queryForYou: queryFor == HuntModel.you, pickedIsYou: pickedIsYou,
            pileup: isPileup, myCall: myCall, myState: myState, actCall: actor.call,
            pickedCall: pickedCall, activatorText: t.text)
        let g = HuntModel.grade(ctx, you.map { HuntGradeSend(text: $0.text, delay: $0.delay, offsetHz: $0.offsetHz, late: late) })
        func who(_ id: Int) -> String { id == HuntModel.you ? "you" : String(id) }
        records.append(HuntRecord(
            round: round, turn: t, you: you, late: late,
            activatorHeard: (0..<txs.count).map { HuntHeard(who: who(txs[$0].who), sent: txs[$0].text, decoded: dec[$0].text) },
            youHeard: (0..<htx.count).map { HuntHeard(who: who(htx[$0].who), sent: htx[$0].text, decoded: ydec[$0].text) },
            grade: g))
        switch t.kind {
        case .close:
            ended = true
            outcome = .logged
            return
        case .as:
            lastAS = false
            turn = makeTurn(.calling, pending)
        case .calling, .query:
            var best: (tx: HuntTx, s: String, minOdds: Double)?
            var any = false
            var bs = 0.0
            for i in 0..<txs.count {
                let sc = HuntModel.candidateOf(dec[i].text)
                if !HuntModel.isCandidate(sc) { continue }
                let score = txs[i].level + rng.nextDouble() * HuntModel.pickJitter
                if !any || score > bs { bs = score; best = (txs[i], sc, dec[i].minOdds) }
                any = true
            }
            if !any {
                fail()
            } else if let best = best {
                let sc = best.s
                queryFor = best.tx.who
                if !sc.contains("?") {
                    if !OperatingProcedure.isValidCall(sc) {
                        fail()
                    } else if best.minOdds < HuntModel.doubtOdds && queries < HuntModel.maxQueries {
                        queries += 1
                        turn = makeTurn(.query, "\(sc)?", query: "\(sc)?")
                    } else {
                        startExchange(sc)
                    }
                } else {
                    let run = HuntModel.longestRun(sc)
                    if run.isEmpty || queries >= HuntModel.maxQueries {
                        fail()
                    } else {
                        queries += 1
                        turn = makeTurn(.query, "\(run)?", query: "\(run)?")
                    }
                }
            }
        case .exchange:
            var ptx = -1
            if pickedCall == myCall {
                if yourIndex >= 0 { ptx = yourIndex }
            } else if let ph = pool.first(where: { $0.call == pickedCall }) {
                for i in 0..<txs.count where txs[i].who == ph.id { ptx = i }
            }
            if ptx < 0 {
                fail()
            } else {
                let tx = txs[ptx]
                let d = dec[ptx]
                let ok = pickedCall == myCall ? HuntModel.exchangeAccepts(d.text, state: myState) : d.text == tx.text
                if ok {
                    if pickedCall == myCall {
                        turn = makeTurn(.close, "\(myCall) TU 73 E E")
                    } else {
                        let done = pickedCall ?? ""
                        pool = pool.filter { $0.call != done }
                        addHunter()
                        failed = 0
                        pickedCall = nil
                        queryFor = nil
                        toCalling("\(done) TU 73 E E")
                    }
                } else {
                    exTries += 1
                    if exTries >= HuntModel.maxExchangeTries { fail() }
                    else { turn = makeTurn(.exchange, "AGN?") }
                }
            }
        }
        if !ended && round >= HuntModel.maxRounds {
            ended = true
            outcome = .timeout
        }
    }
}
