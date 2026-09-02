import Foundation

// MARK: - Modes

/// The QSO/contest flavours the simulator can run. Each has its own exchange.
public enum QSOContestMode: String, Codable, CaseIterable, Identifiable, Sendable {
    case singleCaller   // one station, ragchew-lite (call + name)
    case pota           // RST + state
    case basicContest   // RST + serial number
    case cwt            // CWops: name + number (members) / name + state
    case sst            // K1USN SST: name + state
    case mst            // ICWC MST: name + serial number
    case sprint         // NCCC/NA Sprint: serial + name + state
    case fieldDay       // ARRL Field Day: class + section

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .singleCaller: return "Single Caller"
        case .pota:         return "POTA Activator"
        case .basicContest: return "Basic Contest"
        case .cwt:          return "CWT"
        case .sst:          return "K1USN SST"
        case .mst:          return "ICWC MST"
        case .sprint:       return "NS Sprint"
        case .fieldDay:     return "Field Day"
        }
    }

    public var blurb: String {
        switch self {
        case .singleCaller: return "One station answers — copy their call and name. The gentle warmup."
        case .pota:         return "Work a park pileup — copy each hunter's call and their state."
        case .basicContest: return "A generic CW sprint — copy callsign and serial number."
        case .cwt:          return "CWops mini-test — copy name and member number (or state)."
        case .sst:          return "K1USN Slow Speed Test — copy name and state, taken easy."
        case .mst:          return "ICWC Medium Speed Test — copy name and serial number."
        case .sprint:       return "NCCC/NA Sprint — copy serial number, name, and state."
        case .fieldDay:     return "ARRL Field Day — copy class and ARRL section (e.g. 2A OH)."
        }
    }

    /// Whether the exchange conventionally carries a signal report.
    var includesRST: Bool {
        switch self {
        case .pota, .basicContest, .singleCaller: return true
        case .cwt, .sst, .mst, .sprint, .fieldDay: return false
        }
    }

    /// A single caller never piles up.
    var isPileup: Bool { self != .singleCaller }
}

// MARK: - Exchange tokens

enum TokenKind: Sendable, Equatable { case alpha, numeric, raw }

struct ExchToken: Sendable, Equatable {
    let value: String      // canonical value (real digits, upper-case)
    let kind: TokenKind
}

/// Builds one station's exchange: what it transmits, what you must copy, and a
/// human-readable form for the log.
struct ExchangeSpec: Sendable, Equatable {
    let sentText: String          // Morse text the station sends (cut numbers applied)
    let requiredTokens: [ExchToken]
    let display: String           // true values, for the log

    static func build<R: RandomNumberGenerator>(
        mode: QSOContestMode,
        cutEnabled: Bool,
        cutDigits: Set<Character>,
        rstRequired: Bool,
        using rng: inout R
    ) -> ExchangeSpec {
        func num(_ s: String) -> String { cutEnabled ? CutNumbers.encode(s, enabled: cutDigits) : s }
        let states = MorseData.qthList

        var info: [ExchToken] = []     // informational tokens (graded)
        var sentInfo = ""
        var dispInfo = ""

        switch mode {
        case .singleCaller:
            let name = ContestData.names.randomElement(using: &rng) ?? "BOB"
            info = [ExchToken(value: name, kind: .alpha)]
            sentInfo = "OP \(name) \(name)"
            dispInfo = name

        case .pota:
            let st = states.randomElement(using: &rng) ?? "OH"
            info = [ExchToken(value: st, kind: .alpha)]
            sentInfo = "\(st) \(st)"
            dispInfo = st

        case .basicContest:
            let serial = String(format: "%03d", Int.random(in: 1...999, using: &rng))
            info = [ExchToken(value: serial, kind: .numeric)]
            sentInfo = num(serial)
            dispInfo = serial

        case .cwt:
            let name = ContestData.names.randomElement(using: &rng) ?? "BOB"
            if Double.random(in: 0..<1, using: &rng) < 0.7 {
                let n = String(Int.random(in: 1...3300, using: &rng))
                info = [ExchToken(value: name, kind: .alpha), ExchToken(value: n, kind: .numeric)]
                sentInfo = "\(name) \(num(n))"
                dispInfo = "\(name) \(n)"
            } else {
                let st = states.randomElement(using: &rng) ?? "OH"
                info = [ExchToken(value: name, kind: .alpha), ExchToken(value: st, kind: .alpha)]
                sentInfo = "\(name) \(st)"
                dispInfo = "\(name) \(st)"
            }

        case .sst:
            let name = ContestData.names.randomElement(using: &rng) ?? "BOB"
            let st = states.randomElement(using: &rng) ?? "OH"
            info = [ExchToken(value: name, kind: .alpha), ExchToken(value: st, kind: .alpha)]
            sentInfo = "\(name) \(st)"
            dispInfo = "\(name) \(st)"

        case .mst:
            // ICWC MST: name + a running serial number (no RST). Each station
            // sends its own QSO count, so a plausible serial varies per caller.
            let name = ContestData.names.randomElement(using: &rng) ?? "BOB"
            let n = String(Int.random(in: 1...999, using: &rng))
            info = [ExchToken(value: name, kind: .alpha), ExchToken(value: n, kind: .numeric)]
            sentInfo = "\(name) \(num(n))"
            dispInfo = "\(name) \(n)"

        case .sprint:
            // NCCC/NA Sprint: serial number + operator name + state (no RST).
            let serial = String(Int.random(in: 1...999, using: &rng))
            let name = ContestData.names.randomElement(using: &rng) ?? "BOB"
            let st = states.randomElement(using: &rng) ?? "OH"
            info = [ExchToken(value: serial, kind: .numeric),
                    ExchToken(value: name, kind: .alpha),
                    ExchToken(value: st, kind: .alpha)]
            sentInfo = "\(num(serial)) \(name) \(st)"
            dispInfo = "\(serial) \(name) \(st)"

        case .fieldDay:
            let cls = "\(Int.random(in: 1...12, using: &rng))\(ContestData.fieldDayCategories.randomElement(using: &rng) ?? "A")"
            let sec = ContestData.arrlSections.randomElement(using: &rng) ?? "OH"
            info = [ExchToken(value: cls, kind: .raw), ExchToken(value: sec, kind: .alpha)]
            sentInfo = "\(cls) \(sec)"
            dispInfo = "\(cls) \(sec)"
        }

        // RST is always sent as "5NN" where the exchange carries one; it's only
        // *graded* when the user opted into copying it.
        let sent = mode.includesRST ? "5NN \(sentInfo)" : sentInfo
        let disp = mode.includesRST ? "599 \(dispInfo)" : dispInfo
        var required = info
        if mode.includesRST && rstRequired {
            required.insert(ExchToken(value: "599", kind: .numeric), at: 0)
        }
        return ExchangeSpec(sentText: sent, requiredTokens: required, display: disp)
    }
}

// MARK: - Config

/// When to tell the operator that a caller gave up, and what they had them as.
public enum MissedCallerFeedback: String, Codable, CaseIterable, Identifiable, Sendable {
    case off          // never mention it
    case endOfRun     // in the run summary only
    case immediate    // the moment it happens, and in the summary

    public var id: String { rawValue }
    public var label: String {
        switch self {
        case .off: return "Off"
        case .endOfRun: return "At the end"
        case .immediate: return "As it happens"
        }
    }
}

public enum BustBehavior: String, Codable, CaseIterable, Identifiable, Sendable {
    case forgiving   // matches repeat; total bust -> whole pileup re-calls
    case silence     // matches repeat; total bust -> silence
    case nearest     // total bust -> the closest call nudges once

    public var id: String { rawValue }
    public var label: String {
        switch self {
        case .forgiving: return "Forgiving (pileup re-calls)"
        case .silence:   return "Strict (silence on a bust)"
        case .nearest:   return "Nudge (closest re-calls)"
        }
    }
}

/// Everything the engine needs to run a session. AppModel derives this from
/// AppSettings + the operator's tone.
public struct PileupConfig: Sendable, Equatable {
    public var mode: QSOContestMode = .pota
    public var maxStations: Int = 4
    public var minWPM: Double = 18
    public var maxWPM: Double = 28
    public var toneSpread: Double = 250        // Hz of zero-beat<->offset spread
    public var minVolume: Float = 0.5
    public var maxVolume: Float = 1.0
    public var minDelay: TimeInterval = 0.1
    public var maxDelay: TimeInterval = 1.2
    public var qsbEnabled: Bool = false
    public var qrnLevel: Float = 0             // 0 = off
    public var cutNumbersEnabled: Bool = false
    public var cutDigits: Set<Character> = CutNumbers.commonDefaults
    public var rstRequired: Bool = false
    public var bustBehavior: BustBehavior = .forgiving
    public var giveUpEnabled: Bool = false
    public var giveUpMin: Int = 3
    public var giveUpMax: Int = 6
    public var formats: [CallsignFormat] = CallsignFormat.commonDefaults
    public var usOnly: Bool = true

    public init() {}
}

// MARK: - Engine

/// Pure-logic pileup QSO engine. No audio, no UI — it decides who transmits
/// what in response to your sends, so it can be unit-tested. AppModel turns its
/// `Voice` lists into mixed audio.
public final class PileupEngine {

    public struct Station: Sendable, Equatable, Identifiable {
        public let id: Int
        public let call: String
        public var wpm: Double          // mutable so QRS/QRQ can change it
        public let toneOffset: Double
        public let volume: Float
        public let qsb: Bool
        let exchange: ExchangeSpec
        let patience: Int
        /// How quickly this operator tends to come back, across the delay
        /// window: 0 leaps straight in, 1 hangs back. Drawn once, so a given
        /// op reads as consistently quick or consistently hesitant all run —
        /// which is what makes a pileup sound like people rather than a random
        /// number generator picking a new order every time.
        let reaction: Double
        var attempts: Int = 0
        /// The nearest call you actually sent for this station, if you have
        /// sent one that was close but wrong. Kept so that a caller who walks
        /// off can tell you what you had them as.
        var miscopiedAs: String? = nil
    }

    /// A caller who left before you logged them, and the call you had for them.
    public struct MissedCaller: Sendable, Equatable, Identifiable {
        public let id: Int
        /// What the station was actually sending.
        public let call: String
        /// The closest call you sent for them, when you got close enough that
        /// they kept correcting you. Nil when you never got near it.
        public let miscopiedAs: String?
        /// How many times they came back before giving up.
        public let attempts: Int
    }

    /// One transmission to mix into the pileup audio.
    public struct Voice: Sendable, Equatable {
        public let text: String
        public let wpm: Double
        public let toneOffset: Double
        public let volume: Float
        public let qsb: Bool
        public let delay: TimeInterval
    }

    public enum Phase: Equatable {
        case idle
        case pileup
        case working(id: Int)
        case readyToLog(id: Int)
    }

    public enum Action: Equatable {
        case play([Voice])
        case silence
        case logged(call: String)
    }

    public struct LoggedQSO: Sendable, Equatable, Identifiable {
        public let id: Int
        public let call: String
        public let exchange: String
        public let wpm: Int
    }

    // State
    public private(set) var phase: Phase = .idle
    public private(set) var stations: [Station] = []
    public private(set) var log: [LoggedQSO] = []
    public private(set) var qsoCount = 0
    public private(set) var bustCount = 0
    /// Callers who gave up before being logged, oldest first — the end-of-run
    /// "who got away, and what did I have them as" list.
    public private(set) var missedCallers: [MissedCaller] = []
    /// The most recent walk-off, for feedback shown the moment it happens.
    /// The UI clears it once shown; it is not cleared automatically.
    public private(set) var lastMissedCaller: MissedCaller?

    private var config: PileupConfig
    private var rng: any RandomNumberGenerator
    private var nextID = 1

    public init(config: PileupConfig = PileupConfig(),
                rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.config = config
        self.rng = rng
    }

    /// Adopt new settings mid-session. Callers already on the air keep the
    /// speed and pitch they arrived with, but the caller cap applies at once:
    /// a smaller pileup is what the operator asked for, so the surplus leaves
    /// now rather than at the next session (#143).
    public func update(config: PileupConfig) {
        self.config = config
        enforceCap()
    }

    /// Clear all state and start a fresh session with `config`.
    public func reset(config: PileupConfig) {
        self.config = config
        stations = []
        log = []
        qsoCount = 0
        bustCount = 0
        missedCallers = []
        lastMissedCaller = nil
        nextID = 1
        phase = .idle
    }

    /// Acknowledge the newest walk-off so it is shown only once.
    public func clearLastMissedCaller() { lastMissedCaller = nil }

    public var summary: String { qsoCount == 0 ? config.mode.label : "\(qsoCount) in the log" }
    public var activeCount: Int { stations.count }
    public var workingStation: Station? {
        switch phase {
        case .working(let id), .readyToLog(let id): return stations.first { $0.id == id }
        default: return nil
        }
    }
    /// The canonical answer for the station being worked (for a reveal/hint and
    /// for tests): required tokens joined with spaces, in true digits.
    public var expectedCopy: String? {
        workingStation.map { $0.exchange.requiredTokens.map(\.value).joined(separator: " ") }
    }
    /// Clean-copy accuracy: completed QSOs vs. completed + busts.
    public var accuracy: Double {
        let total = qsoCount + bustCount
        return total == 0 ? 1 : Double(qsoCount) / Double(total)
    }

    // MARK: Calling CQ

    /// Call CQ: top the pileup up with fresh callers and have them all answer.
    ///
    /// Topping up never trimmed: a pileup that had grown to eight stayed eight
    /// after Max callers came down to two, which read as the setting doing
    /// nothing (#143). The cap is enforced here as well as on `update`.
    public func callCQ() -> Action {
        if config.mode.isPileup {
            let target = Int.random(in: max(1, config.maxStations / 2)...max(1, config.maxStations), using: &rng)
            while stations.count < target { stations.append(makeStation()) }
        } else if stations.isEmpty {
            stations = [makeStation()]
        }
        enforceCap()
        phase = .pileup
        guard !stations.isEmpty else { return .silence }
        return .play(stations.map { callVoice(for: $0) })
    }

    // MARK: Sending

    public func send(_ raw: String) -> Action {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        // Operating commands act in any phase and don't count as misses.
        if Self.isQRS(text) { return adjustSpeed(by: -6) }
        if Self.isQRQ(text) { return adjustSpeed(by: 6) }
        switch phase {
        case .idle:
            return callCQ()
        case .pileup:
            return handlePileupSend(text)
        case .working(let id):
            return handleExchangeSend(text, id: id)
        case .readyToLog(let id):
            if text.isEmpty || Self.isSignOff(text) { return doLog(id) }
            return handlePileupSend(text)
        }
    }

    /// The "?" / "AGN" button: ask for a repeat appropriate to the phase.
    public func repeatRequest() -> Action {
        switch phase {
        case .idle:
            return callCQ()
        case .pileup:
            guard !stations.isEmpty else { return .silence }
            return .play(stations.map { callVoice(for: $0) })
        case .working(let id), .readyToLog(let id):
            guard let i = index(of: id) else { return .silence }
            bump(i)
            if quit(i) { return stationQuits(at: i) }
            return .play([exchangeVoice(for: stations[i])])
        }
    }

    /// Log the station currently ready to be logged (the TU button).
    public func logCurrent() -> Action {
        if case .readyToLog(let id) = phase { return doLog(id) }
        if case .working(let id) = phase, let i = index(of: id) {
            // Allow an early TU only once the exchange was copied; otherwise no-op.
            _ = i
        }
        return .silence
    }

    // MARK: Pileup handling

    private func handlePileupSend(_ text: String) -> Action {
        phase = .pileup
        let whole = Self.fragment(text)
        guard !whole.isEmpty else {
            // A bare "?" / AGN / empty send asks the whole pileup to call again.
            guard !stations.isEmpty else { return .silence }
            return .play(stations.map { callVoice(for: $0) })
        }
        // A send is either a bare call or a call with your exchange behind it
        // ("N9HS 5NN AL"). Try the whole thing first, so a stray space inside
        // a call still copies, then fall back to just the leading token.
        let lead = Self.callToken(text)
        for frag in (lead.isEmpty || lead == whole) ? [whole] : [whole, lead] {
            if let action = matchCall(frag) { return action }
        }
        // Nobody is even close to the call you sent — you miscopied it badly.
        // Count it against clean-copy accuracy (issue #30: earlier missed
        // attempts were being ignored, so a QSO logged after retries showed
        // 100%), then respond per the busted-call setting. A fragment a station
        // contains, or one it is a near miss of, was handled above and is a
        // legitimate copy in progress rather than a bust.
        if !stations.isEmpty { bustCount += 1 }
        switch config.bustBehavior {
        case .forgiving:
            guard !stations.isEmpty else { return .silence }
            return .play(stations.map { callVoice(for: $0) })
        case .silence:
            return .silence
        case .nearest:
            guard let n = nearestStation(to: whole) else { return .silence }
            return .play([callVoice(for: stations[n])])
        }
    }

    /// Resolve a call fragment to a response, or nil when it names nobody.
    ///
    /// Three ways a send can land: the exact call opens the exchange, a partial
    /// re-calls everyone it could be (#85), and a near miss has the one station
    /// you nearly copied send its call again.
    private func matchCall(_ frag: String) -> Action? {
        // Exact full-call match -> straight to the exchange.
        if let i = stations.firstIndex(where: { $0.call == frag }) {
            return beginExchange(at: i)
        }
        // Stations the partial could be addressing answer — sending "W1"
        // brings back the W1s, not everyone. The impatient may quit first.
        let matched = recallers { Self.isTightPartial(frag, of: $0) }
        if !matched.isEmpty {
            return .play(matched.map { callVoice(for: stations[$0]) })
        }
        // A near miss: a call you have all but copied, like "N9HS" for N9HO.
        // On the air the station answers that by sending their own call again,
        // and keeps doing it until you get it right — they do not open the
        // exchange on a call that isn't theirs, and they do not go quiet. It is
        // still a miscopy, so it counts as a bust and spends their patience: a
        // caller you never resolve eventually walks, and takes with them a
        // record of what you had them as.
        var near = nearMissStations(for: frag)
        if !near.isEmpty {
            bustCount += 1
            for idx in near { stations[idx].miscopiedAs = frag; bump(idx) }
            let quitters = near.filter { quit($0) }
            if !quitters.isEmpty {
                for idx in quitters { recordMiss(at: idx) }
                let ids = quitters.map { stations[$0].id }
                removeStations(ids: ids)
                near = nearMissStations(for: frag)
                if near.isEmpty {
                    phase = stations.isEmpty ? .idle : .pileup
                    guard !stations.isEmpty else { return .silence }
                    return .play(stations.map { callVoice(for: $0) })
                }
            }
            // One station knows you mean them and simply corrects you. Two or
            // more are all plausibly the call you sent, so none of them is sure
            // it was theirs: they each come back, hanging off the beat while
            // they work out whether you were answering them.
            let hesitation = near.count > 1 ? 1.0 : 0.0
            return .play(near.map { callVoice(for: stations[$0], hesitation: hesitation) })
        }
        // A partial with a hole in it: "WU?" for W1ABU, the first and last
        // letters of a call heard through two others on top of it. No call
        // contains "WU", and at two characters it is nobody's near miss, so
        // it fell through to the busted-call path — on the silence setting,
        // no reply at all (#143). On the air every W-something-U station
        // would come back; here every call carrying those letters in that
        // order does. Tried last, so a tight partial or a near miss keeps
        // the meaning it already had.
        let loose = recallers { Self.isLoosePartial(frag, of: $0) }
        if !loose.isEmpty {
            return .play(loose.map { callVoice(for: stations[$0]) })
        }
        return nil
    }

    /// How far a copied call can sit from a real one and still read as a near
    /// miss rather than a different station: one character wrong, dropped, or
    /// added. Substitutions cost 1 and indels 1.5, so 1.5 is exactly that.
    static let nearMissTolerance = 1.5

    /// Every station a near miss could plausibly be aimed at, nearest first.
    ///
    /// More than one is not a failure to resolve — it is what a real pileup
    /// does when your copy fits two of them. Both come back (the caller keeps
    /// their exchange to themselves until you actually name them), so you get
    /// another pass at the difference instead of silence.
    private func nearMissStations(for frag: String) -> [Int] {
        // Below three characters everything is near everything; that range is
        // the partial's business, and it has already had its turn above.
        guard frag.count >= 3 else { return [] }
        return stations.indices
            .filter { abs(stations[$0].call.count - frag.count) <= 1 }
            .map { (i: $0, d: MorseDistance.distance(frag, stations[$0].call)) }
            .filter { $0.d <= Self.nearMissTolerance }
            .sorted { $0.d < $1.d }
            .map(\.i)
    }

    /// Note a caller who left before being logged, for the feedback readouts.
    private func recordMiss(at i: Int) {
        let s = stations[i]
        let miss = MissedCaller(id: s.id, call: s.call,
                                miscopiedAs: s.miscopiedAs, attempts: s.attempts)
        missedCallers.append(miss)
        lastMissedCaller = miss
    }

    /// The stations a partial re-calls: those `matches` picks out, less any
    /// whose patience this attempt exhausted (they walk, and are recorded).
    ///
    /// A partial is whatever fragment you managed to copy, and it is not always
    /// the front of the call. Two stations landing on top of each other often
    /// leave you one letter from the end, and querying the middle is ordinary
    /// contest practice — "9H?" is how you ask N9HO to come back. Matching only
    /// a prefix left every one of those unanswered (#85): the fragment fell
    /// through to the busted-call path, which on the default silence setting
    /// meant the pileup simply ignored you.
    ///
    /// Callers guarantee a non-empty fragment; an empty one matches every call
    /// and is handled earlier as a bare "?" to the whole pileup.
    private func recallers(_ matches: (String) -> Bool) -> [Int] {
        var matched = stations.indices.filter { matches(stations[$0].call) }
        if config.giveUpEnabled && !matched.isEmpty {
            for idx in matched { bump(idx) }
            let quitters = matched.filter { quit($0) }
            if !quitters.isEmpty {
                for idx in quitters { recordMiss(at: idx) }
                removeStations(ids: quitters.map { stations[$0].id })
                matched = stations.indices.filter { matches(stations[$0].call) }
            }
        }
        return matched
    }

    /// A tight partial: the fragment is a run of consecutive characters of the
    /// call — "9H?" for N9HO. Pinned by fixtures/pileup-partials.json.
    public static func isTightPartial(_ frag: String, of call: String) -> Bool {
        call.contains(frag)
    }

    /// A loose partial: the fragment's characters occur in the call in that
    /// order, with anything at all between them — "WU?" for W1ABU. Every tight
    /// partial is also loose. Pinned by fixtures/pileup-partials.json.
    public static func isLoosePartial(_ frag: String, of call: String) -> Bool {
        var rest = call[...]
        for ch in frag {
            guard let i = rest.firstIndex(of: ch) else { return false }
            rest = rest[rest.index(after: i)...]
        }
        return true
    }

    private func beginExchange(at i: Int) -> Action {
        phase = .working(id: stations[i].id)
        return .play([exchangeVoice(for: stations[i])])
    }

    // MARK: Exchange handling

    private func handleExchangeSend(_ text: String, id: Int) -> Action {
        guard let i = index(of: id) else { phase = .pileup; return .silence }
        if text.isEmpty || Self.isRepeat(text) {
            bump(i)
            if quit(i) { return stationQuits(at: i) }
            return .play([exchangeVoice(for: stations[i])])
        }
        // Bailing to another station you can hear better.
        let frag = Self.fragment(text)
        if frag != stations[i].call, let j = stations.firstIndex(where: { $0.call == frag }) {
            return beginExchange(at: j)
        }
        if grade(text, against: stations[i].exchange.requiredTokens) {
            phase = .readyToLog(id: id)
            return .silence
        }
        bustCount += 1
        bump(i)
        if quit(i) { return stationQuits(at: i) }
        return .play([exchangeVoice(for: stations[i])])
    }

    private func doLog(_ id: Int) -> Action {
        guard let i = index(of: id) else { phase = stations.isEmpty ? .idle : .pileup; return .silence }
        let s = stations[i]
        log.append(LoggedQSO(id: s.id, call: s.call, exchange: s.exchange.display, wpm: Int(s.wpm.rounded())))
        qsoCount += 1
        stations.remove(at: i)
        phase = stations.isEmpty ? .idle : .pileup
        return .logged(call: s.call)
    }

    /// QRS (slow down) / QRQ (speed up): change the speed of whoever you're
    /// working — or the whole pileup — and have them send again at the new rate.
    private func adjustSpeed(by delta: Double) -> Action {
        func clamp(_ w: Double) -> Double { min(45, max(10, w)) }
        switch phase {
        case .working(let id), .readyToLog(let id):
            guard let i = index(of: id) else { return .silence }
            stations[i].wpm = clamp(stations[i].wpm + delta)
            phase = .working(id: id)
            return .play([exchangeVoice(for: stations[i])])
        case .pileup:
            guard !stations.isEmpty else { return .silence }
            for i in stations.indices { stations[i].wpm = clamp(stations[i].wpm + delta) }
            return .play(stations.map { callVoice(for: $0) })
        case .idle:
            return .silence
        }
    }

    private func stationQuits(at i: Int) -> Action {
        recordMiss(at: i)
        stations.remove(at: i)
        phase = stations.isEmpty ? .idle : .pileup
        guard !stations.isEmpty else { return .silence }
        return .play(stations.map { callVoice(for: $0) })
    }

    // MARK: Grading

    /// Field separators an operator might type between exchange elements. Any
    /// run of these breaks tokens, so "9B/EWA" and "9B-EWA" copy like "9B EWA".
    static let fieldSeparators = Set<Character>(" /-,.")

    private func grade(_ input: String, against tokens: [ExchToken]) -> Bool {
        var user = input.uppercased()
            .split(whereSeparator: { Self.fieldSeparators.contains($0) })
            .map(String.init)
        // Drop a leading signal report the operator typed but wasn't asked to
        // copy ("599 OH" -> "OH"). An operator sends 5NN out of habit even in
        // the exchanges that don't carry one — SST, CWT, MST, Sprint and Field
        // Day all take a bare name/serial — so the report is surplus in every
        // mode, not just the ones that send an RST (#38). Only dropped when
        // there's a surplus token to drop, so a serial that merely looks like a
        // report (the NS Sprint's serial, or a basic contest serial in the
        // 500s) is never mistaken for one and stripped.
        if !reportIsRequired, user.count > tokens.count,
           let first = user.first, Self.isRSTLike(first) {
            user.removeFirst()
        }
        // Stations send each exchange element twice for copyability ("OH OH")
        // and prefix a name with the filler "OP" — so a faithful copy of what
        // was *heard* carries more tokens than the exchange requires. Drop the
        // filler and collapse immediately-repeated tokens before counting. No
        // real exchange has two genuinely-identical adjacent tokens, so this is
        // lossless for the de-duplicated form too.
        user.removeAll { $0 == "OP" }
        var collapsed: [String] = []
        for tok in user where collapsed.last != tok { collapsed.append(tok) }
        user = collapsed
        if user.count == tokens.count,
           zip(user, tokens).allSatisfy({ Self.tokenMatches($0, $1) }) {
            return true
        }
        // Fallback: the operator ran the fields together with no separator at
        // all ("9BEWA" for "9B EWA"). Peel each required token's width off the
        // alphanumeric stream in order. Only reached once the separated parse
        // above has failed, so it can't turn a real miss into a match.
        let glued = user.joined()
        if Self.gradeGlued(glued, against: tokens) { return true }
        // The same run-together copy with a report typed in front of it
        // ("5NNOH"): nothing separates the report from the exchange, so the
        // token split above never saw it as its own field to drop.
        return !reportIsRequired
            && Self.gradeGlued(glued, against: tokens, droppingLeadingReport: true)
    }

    /// Whether a signal report is one of the tokens the operator has to copy.
    /// The `rstRequired` setting only bites in a mode that sends one.
    private var reportIsRequired: Bool { config.mode.includesRST && config.rstRequired }

    /// Match run-together input by consuming each token's expected width in turn.
    /// With `droppingLeadingReport`, a three-character signal report at the head
    /// of the stream is peeled off first — the glued twin of the surplus-report
    /// drop in `grade`.
    static func gradeGlued(_ input: String, against tokens: [ExchToken],
                           droppingLeadingReport: Bool = false) -> Bool {
        var stream = Array(input.uppercased().filter { $0.isLetter || $0.isNumber })
        if droppingLeadingReport {
            guard stream.count > 3, isRSTLike(String(stream.prefix(3))) else { return false }
            stream.removeFirst(3)
        }
        var idx = 0
        for t in tokens {
            let n = t.value.count
            guard n > 0, idx + n <= stream.count,
                  tokenMatches(String(stream[idx..<idx + n]), t) else { return false }
            idx += n
        }
        return idx == stream.count   // every character accounted for, nothing extra
    }

    static func tokenMatches(_ user: String, _ token: ExchToken) -> Bool {
        switch token.kind {
        case .alpha:
            let u = user.uppercased().filter { $0.isLetter }
            return u == token.value.uppercased()
        case .numeric:
            let u = CutNumbers.decodeDigits(user)
            if let a = Int(u), let b = Int(token.value) { return a == b }
            return u == token.value
        case .raw:
            let u = user.uppercased().filter { !$0.isWhitespace }
            let value = token.value.uppercased()
            if u == value { return true }
            // A mixed field carries digits too: Field Day's class is a number
            // and a category letter ("2A"), so an operator copying cut numbers
            // writes "UA". Line the copy up against the token — a digit
            // position takes its cut letter, a letter position must match
            // outright — so cut input works wherever a digit is expected (#38).
            return Self.matchesWithCutDigits(u, value)
        }
    }

    /// Compare a copy against a mixed letter/digit field, accepting a cut letter
    /// wherever the field has a digit.
    static func matchesWithCutDigits(_ user: String, _ value: String) -> Bool {
        guard user.count == value.count else { return false }
        return zip(user, value).allSatisfy { u, v in
            u == v || (v.isNumber && CutNumbers.reverse[u] == v)
        }
    }

    static func isRSTLike(_ s: String) -> Bool {
        let d = CutNumbers.decodeDigits(s)
        return d.count == 3 && d.first == "5"
    }

    static func isRepeat(_ s: String) -> Bool {
        let t = s.uppercased()
        return t == "?" || t == "AGN" || t == "AGN?" || t == "QRZ"
    }

    static func isQRS(_ s: String) -> Bool {
        let t = s.uppercased()
        return t == "QRS" || t == "QRS PSE" || t == "PSE QRS" || t == "QRS QRS"
    }

    static func isQRQ(_ s: String) -> Bool { s.uppercased() == "QRQ" }

    /// A callsign fragment from typed input: upper-cased, with spaces and every
    /// query mark dropped. "W1?" queries the W1 prefix; "W?U" and "?U" mark
    /// where the missed letters were. No call carries a "?", so one left in
    /// place could never have matched anything (#143). Pinned by
    /// fixtures/pileup-partials.json.
    public static func fragment(_ text: String) -> String {
        text.uppercased().filter { $0 != " " && $0 != "?" }
    }

    /// The call an operator's send is aimed at: everything up to the first
    /// space, so "N9HS 5NN AL" reads as a call with an exchange behind it
    /// rather than one unbroken token.
    static func callToken(_ text: String) -> String {
        let head = text.split(separator: " ", maxSplits: 1,
                              omittingEmptySubsequences: true).first
        return fragment(head.map(String.init) ?? text)
    }

    static func isSignOff(_ s: String) -> Bool {
        let t = s.uppercased()
        return t == "TU" || t == "TU GL" || t == "73" || t == "TU 73" || t == "R TU"
    }

    // MARK: Station factory & helpers

    private func makeStation() -> Station {
        var call = ""
        repeat {
            call = CallsignGenerator.generate(formats: config.formats.isEmpty ? CallsignFormat.commonDefaults : config.formats,
                                              usOnly: config.usOnly, using: &rng)
        } while stations.contains(where: { $0.call == call })
        let exch = ExchangeSpec.build(mode: config.mode,
                                      cutEnabled: config.cutNumbersEnabled,
                                      cutDigits: config.cutDigits,
                                      rstRequired: config.rstRequired,
                                      using: &rng)
        let wpm = Double.random(in: min(config.minWPM, config.maxWPM)...max(config.minWPM, config.maxWPM), using: &rng)
        let offset = config.toneSpread <= 0 ? 0 : Double.random(in: -config.toneSpread...config.toneSpread, using: &rng)
        let vol = Float.random(in: min(config.minVolume, config.maxVolume)...max(config.minVolume, config.maxVolume), using: &rng)
        let qsb = config.qsbEnabled && Double.random(in: 0..<1, using: &rng) < 0.5
        let patience = Int.random(in: min(config.giveUpMin, config.giveUpMax)...max(config.giveUpMin, config.giveUpMax), using: &rng)
        defer { nextID += 1 }
        let reaction = Double.random(in: 0...1, using: &rng)
        return Station(id: nextID, call: call, wpm: wpm, toneOffset: offset,
                       volume: vol, qsb: qsb, exchange: exch, patience: patience,
                       reaction: reaction)
    }

    /// When a station comes back, in seconds after your send.
    ///
    /// Each operator's own `reaction` sets where in the window they usually
    /// land, and fresh jitter on top means no two rounds are identical even
    /// from the same op. `hesitation` (0...1) pushes the whole thing later and
    /// spreads it wider — a station that isn't sure the call you sent was
    /// theirs waits to see whether anyone else answers first.
    private func replyDelay(for s: Station, hesitation: Double = 0) -> TimeInterval {
        let lo = config.minDelay
        let hi = max(config.minDelay, config.maxDelay)
        let span = hi - lo
        let base = lo + span * s.reaction
        // Jitter is a slice of the window, so a deliberately tight window stays
        // tight and a wide one breathes.
        let jitter = Double.random(in: -0.2 ... 0.2, using: &rng) * span
        // Thinking time is never a fixed beat either, or the hesitation itself
        // would become the metronome.
        let thinking = hesitation * span * Double.random(in: 0.35 ... 1.0, using: &rng)
        return max(0, base + jitter + thinking)
    }

    private func callVoice(for s: Station, hesitation: Double = 0) -> Voice {
        Voice(text: s.call, wpm: s.wpm, toneOffset: s.toneOffset, volume: s.volume,
              qsb: s.qsb, delay: replyDelay(for: s, hesitation: hesitation))
    }

    /// The exchange comes back faster than a call — you have already picked
    /// this operator out and they know it — but not on a fixed beat, which is
    /// what a hard-coded delay made every single exchange sound like.
    private func exchangeVoice(for s: Station) -> Voice {
        let base = 0.15 + 0.25 * s.reaction
        let jitter = Double.random(in: -0.05 ... 0.10, using: &rng)
        return Voice(text: s.exchange.sentText, wpm: s.wpm, toneOffset: s.toneOffset,
                     volume: s.volume, qsb: s.qsb, delay: max(0.05, base + jitter))
    }

    private func index(of id: Int) -> Int? { stations.firstIndex { $0.id == id } }

    /// The most callers the settings allow on the air at once.
    private var stationCap: Int { config.mode.isPileup ? max(1, config.maxStations) : 1 }

    /// Send the callers beyond the cap away, newest first. They leave without
    /// a trace: nobody miscopied them, so it is neither a walk-off nor a bust.
    /// A station being worked is never the one sent away.
    private func enforceCap() {
        var surplus = stations.count - stationCap
        guard surplus > 0 else { return }
        let workingID: Int?
        switch phase {
        case .working(let id), .readyToLog(let id): workingID = id
        default: workingID = nil
        }
        var kept: [Station] = []
        for s in stations.reversed() {
            if surplus > 0 && s.id != workingID { surplus -= 1; continue }
            kept.append(s)
        }
        stations = kept.reversed()
    }
    private func bump(_ i: Int) { stations[i].attempts += 1 }
    private func quit(_ i: Int) -> Bool { config.giveUpEnabled && stations[i].attempts > stations[i].patience }
    private func removeStations(ids: [Int]) { stations.removeAll { ids.contains($0.id) } }

    private func nearestStation(to frag: String) -> Int? {
        guard !stations.isEmpty else { return nil }
        return stations.indices.min(by: {
            MorseDistance.distance(frag, stations[$0].call) < MorseDistance.distance(frag, stations[$1].call)
        })
    }
}
