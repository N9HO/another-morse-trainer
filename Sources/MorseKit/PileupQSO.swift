import Foundation

// MARK: - Modes

/// The QSO/contest flavours the simulator can run. Each has its own exchange.
public enum QSOContestMode: String, Codable, CaseIterable, Identifiable, Sendable {
    case singleCaller   // one station, ragchew-lite (call + name)
    case pota           // RST + state
    case basicContest   // RST + serial number
    case cwt            // CWops: name + number (members) / name + state
    case sst            // K1USN SST: name + state
    case fieldDay       // ARRL Field Day: class + section

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .singleCaller: return "Single Caller"
        case .pota:         return "POTA Activator"
        case .basicContest: return "Basic Contest"
        case .cwt:          return "CWT"
        case .sst:          return "K1USN SST"
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
        case .fieldDay:     return "ARRL Field Day — copy class and ARRL section (e.g. 2A OH)."
        }
    }

    /// Whether the exchange conventionally carries a signal report.
    var includesRST: Bool {
        switch self {
        case .pota, .basicContest, .singleCaller: return true
        case .cwt, .sst, .fieldDay:               return false
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
        var attempts: Int = 0
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

    private var config: PileupConfig
    private var rng: any RandomNumberGenerator
    private var nextID = 1

    public init(config: PileupConfig = PileupConfig(),
                rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.config = config
        self.rng = rng
    }

    public func update(config: PileupConfig) { self.config = config }

    /// Clear all state and start a fresh session with `config`.
    public func reset(config: PileupConfig) {
        self.config = config
        stations = []
        log = []
        qsoCount = 0
        bustCount = 0
        nextID = 1
        phase = .idle
    }

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
    public func callCQ() -> Action {
        if config.mode.isPileup {
            let target = Int.random(in: max(1, config.maxStations / 2)...max(1, config.maxStations), using: &rng)
            while stations.count < target { stations.append(makeStation()) }
        } else if stations.isEmpty {
            stations = [makeStation()]
        }
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
        let frag = Self.fragment(text)
        guard !frag.isEmpty else {
            // A bare "?" / AGN / empty send asks the whole pileup to call again.
            guard !stations.isEmpty else { return .silence }
            return .play(stations.map { callVoice(for: $0) })
        }
        // Exact full-call match -> straight to the exchange.
        if let i = stations.firstIndex(where: { $0.call == frag }) {
            return beginExchange(at: i)
        }
        // Only stations whose call STARTS WITH the fragment answer — sending
        // "W1" brings back the W1s, not everyone. The impatient may quit first.
        var matched = stations.indices.filter { stations[$0].call.hasPrefix(frag) }
        if config.giveUpEnabled && !matched.isEmpty {
            for idx in matched { bump(idx) }
            let quitters = matched.filter { quit($0) }
            if !quitters.isEmpty {
                removeStations(ids: quitters.map { stations[$0].id })
                matched = stations.indices.filter { stations[$0].call.hasPrefix(frag) }
            }
        }
        if !matched.isEmpty {
            return .play(matched.map { callVoice(for: stations[$0]) })
        }
        // No one matches the call you sent — handle per the busted-call setting.
        switch config.bustBehavior {
        case .forgiving:
            guard !stations.isEmpty else { return .silence }
            return .play(stations.map { callVoice(for: $0) })
        case .silence:
            return .silence
        case .nearest:
            guard let n = nearestStation(to: frag) else { return .silence }
            return .play([callVoice(for: stations[n])])
        }
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
        stations.remove(at: i)
        phase = stations.isEmpty ? .idle : .pileup
        guard !stations.isEmpty else { return .silence }
        return .play(stations.map { callVoice(for: $0) })
    }

    // MARK: Grading

    private func grade(_ input: String, against tokens: [ExchToken]) -> Bool {
        var user = input.uppercased().split(whereSeparator: { $0 == " " }).map(String.init)
        if !config.rstRequired, let first = user.first, Self.isRSTLike(first) {
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
        guard user.count == tokens.count else { return false }
        for (u, t) in zip(user, tokens) where !Self.tokenMatches(u, t) { return false }
        return true
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
            return user.uppercased().filter { !$0.isWhitespace } == token.value.uppercased()
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

    /// A callsign fragment from typed input: upper-cased, spaces removed, and the
    /// trailing query mark(s) stripped (so "W1?" queries the W1 prefix).
    static func fragment(_ text: String) -> String {
        var f = text.uppercased().replacingOccurrences(of: " ", with: "")
        while f.hasSuffix("?") { f.removeLast() }
        return f
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
        return Station(id: nextID, call: call, wpm: wpm, toneOffset: offset,
                       volume: vol, qsb: qsb, exchange: exch, patience: patience)
    }

    private func callVoice(for s: Station) -> Voice {
        Voice(text: s.call, wpm: s.wpm, toneOffset: s.toneOffset, volume: s.volume,
              qsb: s.qsb, delay: Double.random(in: config.minDelay...max(config.minDelay, config.maxDelay), using: &rng))
    }

    private func exchangeVoice(for s: Station) -> Voice {
        Voice(text: s.exchange.sentText, wpm: s.wpm, toneOffset: s.toneOffset,
              volume: s.volume, qsb: s.qsb, delay: 0.2)
    }

    private func index(of id: Int) -> Int? { stations.firstIndex { $0.id == id } }
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
