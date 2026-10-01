import Foundation

// First Four (#265): the minimum CW a brand-new operator needs to hunt one
// POTA activator — their call, their state, "?" and "73", copied and sent,
// then three short scripted scenes (a busted call, an unanswered call, and a
// whole contact). docs/first-four-design.md is the spec; every rule below is
// pinned by fixtures/first-four.json, which the Kotlin FirstFourTest reads
// too. Twin of android/…/morsekit/FirstFour.kt.

/// The seven stages, in the order the stage list recommends.
public enum FirstFourStage: String, CaseIterable, Codable, Sendable {
    case call, state, question, seventyThree, bustedCall, noReply, walkthrough

    /// The four element stages each copy, then send, one piece of text.
    public var isElement: Bool {
        switch self {
        case .call, .state, .question, .seventyThree: return true
        case .bustedCall, .noReply, .walkthrough: return false
        }
    }
}

/// An element stage's two halves: hear it and type it, then key it.
public enum FirstFourPhase: String, Codable, Sendable {
    case copy, send
}

/// Namespace for the rules: constants, validation, matching, tables, scenes.
public enum FirstFour {

    // MARK: Pass criteria (defaults the maintainer can change; see the design note)

    /// Correct copies in a row that finish an element stage's copy phase.
    public static let copyStreakToPass = 3
    /// Correct sends in a row that finish an element stage's send phase.
    public static let sendStreakToPass = 3
    /// Clean busted-call rounds that pass that stage.
    public static let bustedRoundsToPass = 3
    /// Clean no-reply scenes that pass that stage: scene A, then scene B.
    public static let noReplyScenesToPass = 2
    /// Clean walkthroughs that pass the last stage.
    public static let walkthroughRunsToPass = 2

    /// Clean runs a scene stage needs; nil for an element stage.
    public static func cleanRunsToPass(_ stage: FirstFourStage) -> Int? {
        switch stage {
        case .bustedCall: return bustedRoundsToPass
        case .noReply: return noReplyScenesToPass
        case .walkthrough: return walkthroughRunsToPass
        default: return nil
        }
    }

    /// The callsign Settings ships with — a placeholder, not the learner's.
    public static let defaultCall = "W1AW"

    // MARK: Callsign and state

    /// Upper-cased with every whitespace character removed; nothing else.
    public static func normalizeCall(_ raw: String) -> String {
        String(raw.uppercased().filter { !$0.isWhitespace })
    }

    public static func normalizeState(_ raw: String) -> String {
        String(raw.uppercased().filter { !$0.isWhitespace })
    }

    /// 3–10 characters of A–Z, 0–9 and "/", with a letter and a digit.
    public static func isValidCall(_ raw: String) -> Bool {
        let call = normalizeCall(raw)
        guard (3...10).contains(call.count) else { return false }
        guard call.allSatisfy({ isAsciiLetter($0) || isAsciiDigit($0) || $0 == "/" }) else { return false }
        return call.contains(where: isAsciiLetter) && call.contains(where: isAsciiDigit)
    }

    /// 2–3 letters. Not checked against a list, so a province works too.
    public static func isValidState(_ raw: String) -> Bool {
        let state = normalizeState(raw)
        return (2...3).contains(state.count) && state.allSatisfy(isAsciiLetter)
    }

    /// What the call field starts with: the saved call, unless it is the
    /// settings placeholder (or empty), which the learner has not entered.
    public static func prefillCall(saved: String) -> String {
        let call = normalizeCall(saved)
        return call == defaultCall ? "" : call
    }

    private static func isAsciiLetter(_ c: Character) -> Bool { ("A"..."Z").contains(c) }
    private static func isAsciiDigit(_ c: Character) -> Bool { ("0"..."9").contains(c) }

    // MARK: Elements

    /// What an element stage drills: the call, the state, "?" or "73".
    public static func element(_ stage: FirstFourStage, call: String, state: String) -> String? {
        switch stage {
        case .call: return normalizeCall(call)
        case .state: return normalizeState(state)
        case .question: return "?"
        case .seventyThree: return "73"
        case .bustedCall, .noReply, .walkthrough: return nil
        }
    }

    // MARK: Matching

    /// Upper-cased with every whitespace character removed: word spacing is
    /// what a beginner's fist and a typed answer are least sure of.
    public static func compact(_ s: String) -> String {
        String(s.uppercased().filter { !$0.isWhitespace })
    }

    /// A typed copy is right when it is the expected text, spacing aside.
    public static func copyMatches(_ typed: String, expected: String) -> Bool {
        let t = compact(typed)
        return !t.isEmpty && t == compact(expected)
    }

    /// A keyed send is right when it is the expected text, spacing aside —
    /// or the expected text twice, as operators often send their call.
    public static func sendMatches(_ sent: String, expected: String) -> Bool {
        let s = compact(sent)
        let e = compact(expected)
        return !s.isEmpty && (s == e || s == e + e)
    }

    // MARK: Busted-call partials

    /// A bare "?" first, then every proper prefix of two or more characters
    /// with "?" after it, skipping a prefix that ends in "/".
    public static func partials(call: String) -> [String] {
        let c = Array(normalizeCall(call))
        var out = ["?"]
        if c.count >= 3 {
            for k in 2..<c.count where c[k - 1] != "/" {
                out.append(String(c[0..<k]) + "?")
            }
        }
        return out
    }

    /// The partial busted round `round` uses (they rotate).
    public static func partial(call: String, round: Int) -> String {
        let list = partials(call: call)
        return list[((round % list.count) + list.count) % list.count]
    }

    // MARK: Stations

    /// An activator for a scene: a callsign and the state they are in.
    public struct Station: Equatable, Sendable {
        public let call: String
        public let state: String
        public init(call: String, state: String) {
            self.call = call
            self.state = state
        }
    }

    /// Fictional activators, pinned in the fixture so both ports carry the same.
    public static let activators: [Station] = [
        Station(call: "K4RTZ", state: "NC"),
        Station(call: "W0PQA", state: "CO"),
        Station(call: "N7XKT", state: "AZ"),
        Station(call: "KB3MZL", state: "PA"),
        Station(call: "AC9WD", state: "IL"),
        Station(call: "W5JBQ", state: "TX"),
        Station(call: "K1VLM", state: "ME"),
        Station(call: "N6GUZ", state: "CA"),
    ]

    /// The other hunter an activator answers instead of you (no-reply scene B).
    public static let otherHunters: [String] = ["W8KDP", "KE0RJ"]

    /// Row `index` (mod the count) of the activators that are not you.
    public static func activator(index: Int, excluding call: String) -> Station {
        let me = normalizeCall(call)
        let pool = activators.filter { $0.call != me }
        return pool[((index % pool.count) + pool.count) % pool.count]
    }

    /// Row `index` (mod the count) of the other hunters that are not you.
    public static func otherHunter(index: Int, excluding call: String) -> String {
        let me = normalizeCall(call)
        let pool = otherHunters.filter { $0 != me }
        return pool[((index % pool.count) + pool.count) % pool.count]
    }

    // MARK: Scenes

    /// The busted-call round: you call, they catch part of it, you send it
    /// all again and wait for the acknowledgement.
    public static func bustedCallScene(call: String, partial: String, activator: Station) -> [FirstFourBeat] {
        let me = normalizeCall(call)
        return [
            .init(.hear, "CQ POTA DE \(activator.call) K", cue: .cq),
            .init(.send, me, cue: .callThem),
            .init(.hear, partial, cue: .partial),
            .init(.send, me, cue: .resend),
            .init(.hear, ack(me, activator), cue: .ack),
        ]
    }

    /// No reply, scene A: nobody answers, so you call again.
    public static func noReplySceneA(call: String, activator: Station) -> [FirstFourBeat] {
        let me = normalizeCall(call)
        return [
            .init(.hear, "CQ POTA DE \(activator.call) K", cue: .cq),
            .init(.send, me, cue: .callThem),
            .init(.silence, "", cue: .noReply),
            .init(.send, me, cue: .callAgain),
            .init(.hear, ack(me, activator), cue: .ack),
        ]
    }

    /// No reply, scene B: they answer someone else, so you wait for QRZ.
    public static func noReplySceneB(call: String, activator: Station, otherHunter: String) -> [FirstFourBeat] {
        let me = normalizeCall(call)
        return [
            .init(.hear, "CQ POTA DE \(activator.call) K", cue: .cq),
            .init(.send, me, cue: .callThem),
            .init(.hear, "\(otherHunter) 5NN \(activator.state) \(activator.state) BK", cue: .otherStation),
            .init(.wait, "", cue: .stayQuiet),
            .init(.hear, "TU 73 QRZ", cue: .qrz),
            .init(.send, me, cue: .callAgain),
            .init(.hear, ack(me, activator), cue: .ack),
        ]
    }

    /// Which no-reply scene plays next: A until it has a clean run, then B.
    public static func noReplyUsesSceneB(cleanRuns: Int) -> Bool { cleanRuns > 0 }

    /// The whole minimal hunter-side contact.
    public static func walkthroughScene(call: String, state: String, activator: Station) -> [FirstFourBeat] {
        let me = normalizeCall(call)
        let st = normalizeState(state)
        return [
            .init(.hear, "CQ POTA DE \(activator.call) \(activator.call) K", cue: .cq),
            .init(.send, me, cue: .callThem),
            .init(.copy, ack(me, activator), answer: activator.state, cue: .theirExchange),
            .init(.send, "5NN \(st) 73", cue: .yourExchange),
            .init(.hear, "TU 73 E E", cue: .signOff),
        ]
    }

    /// The activator coming back to you: your call, the report, their state twice.
    private static func ack(_ me: String, _ activator: Station) -> String {
        "\(me) 5NN \(activator.state) \(activator.state) BK"
    }
}

// MARK: - Beats

/// One step of a scene.
public struct FirstFourBeat: Equatable, Sendable {
    public enum Kind: String, Sendable {
        /// The activator transmits; Continue moves on.
        case hear
        /// Nothing comes back.
        case silence
        /// The activator transmits and the learner types one piece of it.
        case copy
        /// The learner keys `text`.
        case send
        /// The right thing to do is nothing: tap Wait.
        case wait
    }

    /// Which explanation the screen shows beside the beat. The words are each
    /// platform's own; the cue is what the fixture pins.
    public enum Cue: String, Sendable {
        case cq, callThem, partial, resend, ack, noReply, callAgain
        case otherStation, stayQuiet, qrz, theirExchange, yourExchange, signOff
    }

    public let kind: Kind
    /// What plays (hear, copy) or what to key (send); empty otherwise.
    public let text: String
    /// What a copy beat asks for; nil otherwise.
    public let answer: String?
    public let cue: Cue

    public init(_ kind: Kind, _ text: String, answer: String? = nil, cue: Cue) {
        self.kind = kind
        self.text = text
        self.answer = answer
        self.cue = cue
    }
}

// MARK: - Scene runner

/// Walks a scene beat by beat, grading each response.
public struct FirstFourScene: Equatable, Sendable {
    public enum Response: Equatable, Sendable {
        case continued
        case sent(String)
        case copied(String)
        case waited
    }

    public enum Verdict: String, Equatable, Sendable {
        case advance
        case finished
        /// Keyed something that is not what the beat asks for.
        case sentWrong
        /// Typed something that is not what was sent.
        case copyWrong
        /// It was the learner's turn to send.
        case yourTurn
        /// The right thing was to wait, not to key.
        case stayQuiet
        /// The activator is transmitting; listen first.
        case notYourTurn
    }

    public let beats: [FirstFourBeat]
    public private(set) var index = 0
    public private(set) var mistakes = 0

    public init(beats: [FirstFourBeat]) {
        self.beats = beats
    }

    public var isFinished: Bool { index >= beats.count }
    /// Finished with no mistakes.
    public var isClean: Bool { isFinished && mistakes == 0 }
    public var current: FirstFourBeat? { isFinished ? nil : beats[index] }

    @discardableResult
    public mutating func respond(_ response: Response) -> Verdict {
        guard let beat = current else { return .finished }
        let verdict: Verdict
        switch beat.kind {
        case .hear, .silence:
            verdict = response == .continued ? .advance : .notYourTurn
        case .send:
            if case .sent(let text) = response {
                verdict = FirstFour.sendMatches(text, expected: beat.text) ? .advance : .sentWrong
            } else {
                verdict = .yourTurn
            }
        case .copy:
            if case .copied(let text) = response, FirstFour.copyMatches(text, expected: beat.answer ?? "") {
                verdict = .advance
            } else {
                verdict = .copyWrong
            }
        case .wait:
            verdict = response == .waited ? .advance : .stayQuiet
        }
        if verdict == .advance { index += 1 } else { mistakes += 1 }
        return verdict
    }
}

// MARK: - Progress

/// Which stages have passed, and the counters that decide the rest.
/// Persisted by the app; the in-phase streak is not (three in a row means
/// three in one sitting).
public struct FirstFourProgress: Codable, Equatable, Sendable {
    public private(set) var passed: Set<FirstFourStage> = []
    /// Element stages whose copy phase is done.
    public private(set) var copyPassed: Set<FirstFourStage> = []
    /// Clean runs per scene stage, keyed by the stage's raw value.
    public private(set) var cleanRuns: [String: Int] = [:]

    /// The current phase's run of correct answers, and what it counts.
    public private(set) var streak = 0
    private var streakKey: String?

    enum CodingKeys: String, CodingKey { case passed, copyPassed, cleanRuns }

    public init() {}

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        passed = try c.decodeIfPresent(Set<FirstFourStage>.self, forKey: .passed) ?? []
        copyPassed = try c.decodeIfPresent(Set<FirstFourStage>.self, forKey: .copyPassed) ?? []
        cleanRuns = try c.decodeIfPresent([String: Int].self, forKey: .cleanRuns) ?? [:]
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(passed.map(\.rawValue).sorted(), forKey: .passed)
        try c.encode(copyPassed.map(\.rawValue).sorted(), forKey: .copyPassed)
        try c.encode(cleanRuns, forKey: .cleanRuns)
    }

    public func hasPassed(_ stage: FirstFourStage) -> Bool { passed.contains(stage) }
    public var passedCount: Int { passed.count }
    public var isComplete: Bool { FirstFourStage.allCases.allSatisfy(passed.contains) }
    public var nextStage: FirstFourStage? { FirstFourStage.allCases.first { !passed.contains($0) } }
    public func cleanRuns(_ stage: FirstFourStage) -> Int { cleanRuns[stage.rawValue] ?? 0 }

    /// Where an element stage opens: the send phase once copy is done and the
    /// stage is still open, else copy (a passed stage is reviewed from the top).
    public func openingPhase(_ stage: FirstFourStage) -> FirstFourPhase {
        copyPassed.contains(stage) && !passed.contains(stage) ? .send : .copy
    }

    /// Count one element answer. Returns true when it completed the phase.
    @discardableResult
    public mutating func recordElement(_ stage: FirstFourStage, phase: FirstFourPhase, correct: Bool) -> Bool {
        let key = "\(stage.rawValue).\(phase.rawValue)"
        if streakKey != key { streak = 0; streakKey = key }
        guard correct else { streak = 0; return false }
        streak += 1
        let needed = phase == .copy ? FirstFour.copyStreakToPass : FirstFour.sendStreakToPass
        guard streak >= needed else { return false }
        streak = 0
        switch phase {
        case .copy: copyPassed.insert(stage)
        case .send: copyPassed.insert(stage); passed.insert(stage)
        }
        return true
    }

    /// Count one finished scene run. Returns true when this run passed the stage.
    @discardableResult
    public mutating func recordScene(_ stage: FirstFourStage, clean: Bool) -> Bool {
        guard clean, let needed = FirstFour.cleanRunsToPass(stage) else { return false }
        let runs = cleanRuns(stage) + 1
        cleanRuns[stage.rawValue] = runs
        guard runs >= needed, !passed.contains(stage) else { return false }
        passed.insert(stage)
        return true
    }
}
