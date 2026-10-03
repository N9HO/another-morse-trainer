import Foundation

// CW Operating Procedure (#294, #295): on-air etiquette for hunting POTA
// activators, one rule at a time — eight lessons of concept, demo and quick
// scenarios (the first is #294's zero beat / RIT / XIT / offset lesson, with
// its pileup demo and a tune-to-zero-beat drill), and a "What should you do?"
// mode over the lessons' scenarios. docs/operating-procedure-design.md is the
// spec; every rule below is pinned by fixtures/operating-procedure.json, which
// the Kotlin OperatingProcedureTest in android/ and desktop/ reads too. Twin of
// android/…/morsekit/OperatingProcedure.kt and desktop/…/morsekit/OperatingProcedure.kt.
//
// Call and state validation are this file's own copy of First Four's rules,
// so the section never depends on First Four's port (desktop has none yet).

/// The eight lessons, in the order the lesson list recommends. Offsetting
/// (#294) comes first, straight after First Four (maintainer, 2026-10-03).
public enum OpLesson: String, CaseIterable, Codable, Sendable {
    case offset, signals, when, once, partial, me, exchange, mistake
}

/// What a scenario offers the learner.
public enum OpChoice: Equatable, Hashable, Sendable {
    /// Key this text.
    case send(String)
    /// Stay silent and listen.
    case silent
    /// A named answer (what a signal means, which control to use). The words
    /// are each platform's own; the key is what the fixture pins.
    case option(String)

    public var isAction: Bool {
        switch self {
        case .send, .silent: return true
        case .option: return false
        }
    }
}

/// One quick question: a clip (what the activator just sent; may be empty),
/// a generated detail the situation text mentions (may be empty), and the
/// choices, **the primary right answer first**. `accepted` lists every choice
/// that counts as right (the exchange has two accepted forms). The screen
/// shuffles the choices.
public struct OpScenario: Equatable, Sendable {
    public let id: String
    public let lesson: OpLesson
    public let clip: String
    public let detail: String
    public let choices: [OpChoice]
    public let accepted: [Int]

    public init(id: String, lesson: OpLesson, clip: String, detail: String = "", choices: [OpChoice],
                accepted: [Int] = [0]) {
        self.id = id
        self.lesson = lesson
        self.clip = clip
        self.detail = detail
        self.choices = choices
        self.accepted = accepted
    }

    /// The primary right answer.
    public var correct: OpChoice { choices[0] }
    /// True when picking choice `index` (in `choices` order) is right.
    public func accepts(index: Int) -> Bool { accepted.contains(index) }
    /// True when picking `choice` is right.
    public func accepts(_ choice: OpChoice) -> Bool {
        choices.firstIndex(of: choice).map(accepts(index:)) ?? false
    }
    /// Every choice is send or stay silent: these make up "What should you do?".
    public var isAction: Bool { choices.allSatisfy(\.isAction) }
}

/// A demo clip: an example of right or wrong (or just "listen"), as a short
/// transcript of who sends what.
public struct OpDemo: Equatable, Sendable {
    public enum Kind: String, Sendable { case listen, right, wrong }
    public enum Who: String, Sendable { case activator, you }
    public struct Line: Equatable, Sendable {
        public let who: Who
        public let text: String
        public init(_ who: Who, _ text: String) { self.who = who; self.text = text }
    }
    public let kind: Kind
    public let lines: [Line]
    public init(_ kind: Kind, _ lines: [Line]) { self.kind = kind; self.lines = lines }
}

/// One caller in the pileup demo, ready for the mixer.
public struct OpPileupVoice: Equatable, Sendable {
    public let text: String
    public let pitch: Double
    public let wpm: Double
    public let gain: Double
    public let delay: Double
    public let isYou: Bool

    public init(text: String, pitch: Double, wpm: Double, gain: Double, delay: Double, isYou: Bool) {
        self.text = text
        self.pitch = pitch
        self.wpm = wpm
        self.gain = gain
        self.delay = delay
        self.isYou = isYou
    }
}

/// Namespace for the rules: constants, validation, generated texts, scenarios,
/// demos and the offset maths.
public enum OperatingProcedure {

    // MARK: Constants (defaults the maintainer can change; see the design note)

    /// Scenarios dealt per "What should you do?" run.
    public static let scenarioRunLength = 10
    /// Right answers in a row that pass the offsetting lesson's zero-beat drill.
    public static let zeroBeatStreakToPass = 3
    /// How close to the activator's frequency counts as zero beat, in hertz.
    public static let zeroBeatToleranceHz = 20.0
    /// The lowest pitch anything plays at — the Pileup Runner's floor too.
    public static let minimumPitchHz = 200.0
    public static let minimumWpm = 5.0
    /// Where you call in the pileup demo's second and third passes.
    public static let demoYourOffsetHz = 80.0
    /// The activator's offset in the RIT demo.
    public static let ritDemoStationOffsetHz = 200.0
    /// The RIT demo's control: ± this, in steps of `ritStepHz`.
    public static let ritRangeHz = 300.0
    public static let ritStepHz = 10.0
    /// Where an error goes in a clip or a choice: `<ERR>` (any shape, picked
    /// when it plays) or `<ERR:n>` (row n of `errorVariants`). Not a prosign:
    /// an error can sound like anything, and the lesson teaches recognising
    /// it, not copying one shape (maintainer, 2026-10-03).
    public static let errorToken = "<ERR>"
    /// How an error is shown: a word, never a fixed run of dots.
    public static let errorDisplay = "[error]"
    /// Stands in for a pileup-demo caller whose call is the learner's own.
    public static let replacementCaller = "KC2VWM"
    /// The callsign Settings ships with — a placeholder, not the learner's.
    public static let defaultCall = "W1AW"

    /// Where the offsetting drill starts the activator, round by round (hertz).
    public static let drillStarts: [Double] = [180, -120, 250, -70, 90, -210, 140, -260]
    /// The tuning buttons, in hertz.
    public static let knobSteps: [Double] = [-50, -10, 10, 50]

    // MARK: Stations

    public struct Station: Equatable, Sendable {
        public let call: String
        public let state: String
        public init(call: String, state: String) { self.call = call; self.state = state }
    }

    /// First Four's first two activators: the scenarios use the first that is
    /// not the learner.
    public static let activators: [Station] = [
        Station(call: "K4RTZ", state: "NC"),
        Station(call: "W0PQA", state: "CO"),
    ]
    public static let otherHunters: [String] = ["W8KDP", "KE0RJ"]

    public static func activator(for call: String) -> Station {
        let me = normalizeCall(call)
        return activators.first { $0.call != me } ?? activators[0]
    }

    public static func otherHunter(for call: String) -> String {
        let me = normalizeCall(call)
        return otherHunters.first { $0 != me } ?? otherHunters[0]
    }

    // MARK: Call and state

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
        guard call.allSatisfy({ isLetter($0) || isDigit($0) || $0 == "/" }) else { return false }
        return call.contains(where: isLetter) && call.contains(where: isDigit)
    }

    /// 2–3 letters.
    public static func isValidState(_ raw: String) -> Bool {
        let state = normalizeState(raw)
        return (2...3).contains(state.count) && state.allSatisfy(isLetter)
    }

    /// The saved call, unless it is the settings placeholder.
    public static func prefillCall(saved: String) -> String {
        let call = normalizeCall(saved)
        return call == defaultCall ? "" : call
    }

    private static func isLetter(_ c: Character) -> Bool { ("A"..."Z").contains(c) }
    private static func isDigit(_ c: Character) -> Bool { ("0"..."9").contains(c) }

    // MARK: Generated texts

    /// A letter steps through A–Z, a digit through 0–9, by `n`, wrapping.
    public static func shift(_ c: Character, by n: Int) -> Character {
        func step(_ base: Character, _ count: Int) -> Character {
            let b = Int(base.asciiValue!)
            let i = Int(c.asciiValue!) - b
            let j = ((i + n) % count + count) % count
            return Character(UnicodeScalar(UInt8(b + j)))
        }
        if isLetter(c) { return step("A", 26) }
        if isDigit(c) { return step("0", 10) }
        return c
    }

    /// The partial-calls rule: what is left of the partial once its "?"s are gone is
    /// yours when it occurs, unbroken, in your call. A bare "?" is everyone's.
    public static func partialMatches(_ partial: String, call: String) -> Bool {
        var p = normalizeCall(partial)
        while p.hasSuffix("?") { p.removeLast() }
        if p.isEmpty { return true }
        return normalizeCall(call).contains(p)
    }

    /// A partial that is certainly not yours: your call up to its first digit,
    /// that digit moved on two (then one more at a time while it is still in
    /// your call). `K9QRO` → `K1?`.
    public static func notMinePartial(call: String) -> String {
        let c = Array(normalizeCall(call))
        guard let d = c.firstIndex(where: isDigit) else { return "?" }
        let me = String(c)
        var digit = shift(c[d], by: 2)
        var head = String(c[0..<d]) + String(digit)
        var tries = 0
        while me.contains(head) && tries < 10 {
            digit = shift(digit, by: 1)
            head = String(c[0..<d]) + String(digit)
            tries += 1
        }
        return head + "?"
    }

    /// A call one letter from yours: the first letter after your first digit
    /// moved back six (`K9QRO` → `K9KRO`); with no letter there, your last letter.
    public static func nearMiss(call: String) -> String {
        var c = Array(normalizeCall(call))
        guard !c.isEmpty else { return "" }
        let afterDigit = c.firstIndex(where: isDigit).flatMap { d in
            c[(d + 1)...].firstIndex(where: isLetter)
        }
        guard let i = afterDigit ?? c.lastIndex(where: isLetter) else { return String(c) }
        c[i] = shift(c[i], by: -6)
        return String(c)
    }

    /// A clip or choice as it is shown: every error token becomes `errorDisplay`.
    public static func display(_ text: String) -> String {
        clipParts(text).map { part in
            switch part {
            case .text(let t): return t
            case .error: return errorDisplay
            }
        }.joined(separator: " ")
    }

    /// The hunter's reply, the same as First Four's: report, state, 73. The
    /// primary form.
    public static func reply(state: String) -> String {
        "5NN \(normalizeState(state)) 73"
    }

    /// WB0RLJ's order (RST, state, BK; then 73 and dit-dit after their TU),
    /// accepted as a second form (maintainer, 2026-10-03). Its first turn.
    public static func replyBK(state: String) -> String {
        "5NN \(normalizeState(state)) BK"
    }

    /// …and its second, after the activator's `TU 73 E E`.
    public static let closeBK = "73 E E"

    // MARK: Errors

    /// One way an error can sound: how many dits (5–8), sent run together
    /// (one keying, element gaps) or slapped as separate dits (character
    /// gaps), at the learner's speed times `speed`.
    public struct ErrorVariant: Equatable, Sendable {
        public let count: Int
        public let runTogether: Bool
        public let speed: Double
        public init(count: Int, runTogether: Bool, speed: Double) {
            self.count = count
            self.runTogether = runTogether
            self.speed = speed
        }

        /// Run together, as a dot pattern.
        public var pattern: String { String(repeating: ".", count: count) }
        /// Slapped, as separate letters E.
        public var spacedText: String { String(repeating: "E", count: count) }
    }

    /// The shapes an error takes in the app, so no two sound alike.
    public static let errorVariants: [ErrorVariant] = [
        ErrorVariant(count: 8, runTogether: true, speed: 1.0),
        ErrorVariant(count: 5, runTogether: true, speed: 1.5),
        ErrorVariant(count: 6, runTogether: false, speed: 0.8),
        ErrorVariant(count: 7, runTogether: true, speed: 1.25),
        ErrorVariant(count: 5, runTogether: false, speed: 1.0),
        ErrorVariant(count: 8, runTogether: false, speed: 1.5),
    ]

    /// Row `index` of `errorVariants`, cycling.
    public static func errorVariant(_ index: Int) -> ErrorVariant {
        let n = errorVariants.count
        return errorVariants[((index % n) + n) % n]
    }

    /// A piece of a clip: Morse text, or an error (with its row, if pinned).
    public enum ClipPart: Equatable, Sendable {
        case text(String)
        case error(Int?)
    }

    /// Split a clip on its error tokens, so a player can sound each error in
    /// its own shape. Words are joined by single spaces; empty text is dropped.
    public static func clipParts(_ text: String) -> [ClipPart] {
        var parts: [ClipPart] = []
        var words: [String] = []
        func flush() {
            if !words.isEmpty { parts.append(.text(words.joined(separator: " "))) }
            words = []
        }
        for word in text.split(separator: " ").map(String.init) {
            if word == errorToken {
                flush()
                parts.append(.error(nil))
            } else if word.hasPrefix("<ERR:"), word.hasSuffix(">"), let n = Int(word.dropFirst(5).dropLast()) {
                flush()
                parts.append(.error(n))
            } else {
                words.append(word)
            }
        }
        flush()
        return parts
    }

    /// The exchange lesson's wrong example: a ragchew where a POTA exchange belongs.
    public static func ragchew(call: String, state: String, activator: String) -> String {
        let c = normalizeCall(call)
        let s = normalizeState(state)
        return "\(activator) DE \(c) TNX FER CALL UR 5NN 5NN NAME JOE QTH \(s) \(s) HW? \(activator) DE \(c) KN"
    }

    // MARK: Scenarios

    /// Every lesson's scenarios, in lesson order, for this call and state.
    public static func scenarios(call rawCall: String, state rawState: String) -> [OpScenario] {
        let me = normalizeCall(rawCall)
        let st = normalizeState(rawState)
        let act = activator(for: me)
        let other = otherHunter(for: me)
        let chars = Array(me)
        let theirAck = "\(me) 5NN \(act.state) \(act.state) BK"
        let otherAck = "\(other) 5NN \(act.state) \(act.state) BK"
        let otherDone = "\(other) TU 73 E E"
        let mine = OpChoice.send(me)
        let near = nearMiss(call: me)
        let myReply = reply(state: st)
        let last = chars.last.map { String($0) } ?? ""
        let callWrong = String(chars.dropLast()) + (chars.last.map { String(shift($0, by: 1)) } ?? "")
        let callLast = String(chars.dropLast()) + (chars.last.map { String(shift($0, by: -6)) } ?? "")
        let stChars = Array(st)
        let stateWrong = String(stChars.dropLast()) + (stChars.last.map { String(shift($0, by: 1)) } ?? "")
        let myReplyBK = replyBK(state: st)
        let err = errorToken

        return [
            OpScenario(id: "offset.pileup", lesson: .offset, clip: "",
                       choices: [.option("offsetSmall"), .option("zeroBeat"), .option("twoKUp")]),
            OpScenario(id: "offset.rit", lesson: .offset, clip: "",
                       choices: [.option("rit"), .option("xit"), .option("pitch")]),
            OpScenario(id: "offset.xit", lesson: .offset, clip: "",
                       choices: [.option("xit"), .option("rit"), .option("pitch")]),
            OpScenario(id: "offset.tune", lesson: .offset, clip: "",
                       choices: [.option("tuneAway"), .option("tuneOnQuick"), .option("tuneOnLow")]),

            OpScenario(id: "signals.as", lesson: .signals, clip: "<AS>",
                       choices: [.option("wait"), .option("goAhead"), .option("goodbye")]),
            OpScenario(id: "signals.qrz", lesson: .signals, clip: "QRZ?",
                       choices: [.option("whoIsCalling"), .option("sayAgain"), .option("sorry")]),
            OpScenario(id: "signals.ee", lesson: .signals, clip: "E E",
                       choices: [.option("goodbye"), .option("error"), .option("whoIsCalling")]),
            OpScenario(id: "signals.bk", lesson: .signals, clip: "BK",
                       choices: [.option("backToYou"), .option("wait"), .option("sorry")]),
            OpScenario(id: "signals.agn", lesson: .signals, clip: "AGN?",
                       choices: [.option("sayAgain"), .option("goodbye"), .option("backToYou")]),

            OpScenario(id: "when.dits", lesson: .when, clip: otherDone, choices: [mine, .silent]),
            OpScenario(id: "when.inProgress", lesson: .when, clip: otherAck, choices: [.silent, mine]),
            OpScenario(id: "when.as", lesson: .when, clip: "<AS>", choices: [.silent, mine]),
            OpScenario(id: "when.sriQrz", lesson: .when, clip: "SRI SRI QRZ?", choices: [mine, .silent]),

            OpScenario(id: "once.cq", lesson: .once, clip: "CQ POTA DE \(act.call) K",
                       choices: [mine, .send("\(act.call) DE \(me) K"), .send("\(me) \(me) \(me)")]),
            OpScenario(id: "once.qrz", lesson: .once, clip: "QRZ?",
                       choices: [mine, .send("DE \(me) K"), .send("\(me) \(me)")]),
            OpScenario(id: "once.dits", lesson: .once, clip: otherDone,
                       choices: [mine, .send("\(act.call) \(me)"), .silent]),

            OpScenario(id: "partial.prefix", lesson: .partial, clip: String(chars.prefix(2)) + "?",
                       choices: [mine, .silent]),
            OpScenario(id: "partial.notMine", lesson: .partial, clip: notMinePartial(call: me),
                       choices: [.silent, mine]),
            OpScenario(id: "partial.suffix", lesson: .partial, clip: String(chars.suffix(2)) + "?",
                       choices: [mine, .silent]),
            OpScenario(id: "partial.fullCall", lesson: .partial, clip: String(chars.dropLast()) + "?",
                       choices: [mine, .send(last), .silent]),

            OpScenario(id: "me.other", lesson: .me, clip: otherAck, choices: [.silent, mine]),
            OpScenario(id: "me.mine", lesson: .me, clip: theirAck,
                       choices: [.send(myReply), .send(myReplyBK), mine, .silent], accepted: [0, 1]),
            OpScenario(id: "me.close", lesson: .me, clip: "\(near) 5NN \(act.state) \(act.state) BK",
                       detail: near, choices: [.silent, mine]),
            OpScenario(id: "me.closeAsked", lesson: .me, clip: "\(near)?", detail: near,
                       choices: [mine, .silent]),

            OpScenario(id: "exchange.reply", lesson: .exchange, clip: theirAck,
                       choices: [.send(myReply), .send(myReplyBK),
                                 .send(ragchew(call: me, state: st, activator: act.call)),
                                 .send("\(me) 5NN \(st)")], accepted: [0, 1]),
            OpScenario(id: "exchange.agn", lesson: .exchange, clip: "AGN?",
                       choices: [.send(myReply), .send(myReplyBK), mine, .silent], accepted: [0, 1]),
            OpScenario(id: "exchange.dits", lesson: .exchange, clip: "TU 73 E E",
                       choices: [.send("E E"), .send(closeBK), mine, .send("TU 73 GL DE \(me) SK")],
                       accepted: [0, 1]),
            OpScenario(id: "exchange.stop", lesson: .exchange, clip: "QRZ?", choices: [.silent, mine]),

            OpScenario(id: "mistake.call", lesson: .mistake, clip: "", detail: callWrong,
                       choices: [.send("\(err) \(me)"), .send("SRI \(me)"), .silent]),
            OpScenario(id: "mistake.last", lesson: .mistake, clip: "", detail: callLast,
                       choices: [.send("\(err) \(me)"), .send("\(err) \(last)"), .silent]),
            OpScenario(id: "mistake.state", lesson: .mistake, clip: "", detail: "5NN \(stateWrong)",
                       choices: [.send("\(err) \(st) 73"), .send("5NN \(stateWrong) \(st) 73"), .silent]),
            OpScenario(id: "mistake.hear", lesson: .mistake, clip: "\(callWrong) \(err) \(theirAck)",
                       detail: callWrong,
                       choices: [.send(myReply), .send(myReplyBK), mine, .silent], accepted: [0, 1]),
        ]
    }

    /// One lesson's scenarios.
    public static func scenarios(_ lesson: OpLesson, call: String, state: String) -> [OpScenario] {
        scenarios(call: call, state: state).filter { $0.lesson == lesson }
    }

    /// "What should you do?"'s pool: every action scenario, in lesson order.
    public static func actionPool(call: String, state: String) -> [OpScenario] {
        scenarios(call: call, state: state).filter(\.isAction)
    }

    // MARK: Demos

    /// A lesson's right/wrong clips. The offsetting lesson's demos are the pileup and RIT
    /// demos below, so its list is empty.
    public static func demos(_ lesson: OpLesson, call rawCall: String, state rawState: String) -> [OpDemo] {
        let me = normalizeCall(rawCall)
        let st = normalizeState(rawState)
        let act = activator(for: me)
        let other = otherHunter(for: me)
        let chars = Array(me)
        let theirAck = "\(me) 5NN \(act.state) \(act.state) BK"
        switch lesson {
        case .signals:
            return ["?", "AGN?", "<AS>", "BK", "SRI", "QRZ?", "E E"].map { OpDemo(.listen, [.init(.activator, $0)]) }
        case .when:
            return [
                OpDemo(.wrong, [.init(.activator, "\(other) 5NN \(act.state) \(act.state) BK"), .init(.you, me)]),
                OpDemo(.right, [.init(.activator, "\(other) TU 73 E E"), .init(.you, me)]),
            ]
        case .once:
            return [
                OpDemo(.wrong, [.init(.activator, "CQ POTA DE \(act.call) K"), .init(.you, "\(act.call) DE \(me) \(me) K")]),
                OpDemo(.right, [.init(.activator, "CQ POTA DE \(act.call) K"), .init(.you, me)]),
            ]
        case .partial:
            return [
                OpDemo(.wrong, [.init(.activator, notMinePartial(call: me)), .init(.you, me)]),
                OpDemo(.right, [.init(.activator, String(chars.prefix(2)) + "?"), .init(.you, me)]),
            ]
        case .me:
            let near = nearMiss(call: me)
            return [
                OpDemo(.wrong, [.init(.activator, "\(near) 5NN \(act.state) \(act.state) BK"), .init(.you, me)]),
                OpDemo(.right, [.init(.activator, "\(near)?"), .init(.you, me), .init(.activator, theirAck)]),
            ]
        case .exchange:
            return [
                OpDemo(.wrong, [.init(.activator, theirAck), .init(.you, ragchew(call: me, state: st, activator: act.call))]),
                OpDemo(.right, [.init(.activator, theirAck), .init(.you, reply(state: st)),
                                .init(.activator, "TU 73 E E"), .init(.you, "E E")]),
                OpDemo(.right, [.init(.activator, theirAck), .init(.you, replyBK(state: st)),
                                .init(.activator, "TU 73 E E"), .init(.you, closeBK)]),
            ]
        case .mistake:
            let wrong = String(chars.dropLast()) + (chars.last.map { String(shift($0, by: 1)) } ?? "")
            return [
                OpDemo(.wrong, [.init(.you, wrong)]),
                OpDemo(.right, [.init(.you, "\(wrong) \(errorToken) \(me)")]),
                OpDemo(.listen, [.init(.activator, "\(wrong) <ERR:1> \(me)")]),
                OpDemo(.listen, [.init(.activator, "\(wrong) <ERR:2> \(me)")]),
                OpDemo(.listen, [.init(.activator, "\(wrong) <ERR:5> \(me)")]),
            ]
        case .offset:
            return []
        }
    }

    // MARK: The pileup demo (#294)

    public enum PileupPass: String, CaseIterable, Sendable {
        /// Everyone, you included, on the activator's frequency.
        case zeroBeat
        /// Only you off it.
        case youOffset
        /// Everyone on their own offset.
        case allOffset
    }

    private struct DemoCaller {
        let call: String?          // nil = you
        let wpmDelta: Double
        let gain: Double
        let delay: Double
        let spreadOffset: Double   // the allOffset pass
    }

    /// Mix order. You are in the middle, as you would be in a real pileup.
    private static let demoCallers: [DemoCaller] = [
        DemoCaller(call: "W8KDP", wpmDelta: 2, gain: 0.80, delay: 0.15, spreadOffset: -160),
        DemoCaller(call: "KE0RJ", wpmDelta: -2, gain: 0.90, delay: 0.30, spreadOffset: -70),
        DemoCaller(call: nil, wpmDelta: 0, gain: 1.00, delay: 0.00, spreadOffset: demoYourOffsetHz),
        DemoCaller(call: "AB7TF", wpmDelta: 4, gain: 0.70, delay: 0.10, spreadOffset: 30),
        DemoCaller(call: "N4LQX", wpmDelta: -1, gain: 0.85, delay: 0.22, spreadOffset: 150),
    ]

    /// The callers of one pass, as the activator hears them: each at the
    /// learner's tone plus their offset, sending their call once.
    public static func pileupVoices(_ pass: PileupPass, call rawCall: String, tone: Double, wpm: Double) -> [OpPileupVoice] {
        let me = normalizeCall(rawCall)
        return demoCallers.map { c in
            let isYou = c.call == nil
            let offset: Double
            switch pass {
            case .zeroBeat: offset = 0
            case .youOffset: offset = isYou ? demoYourOffsetHz : 0
            case .allOffset: offset = c.spreadOffset
            }
            let text = isYou ? me : (c.call == me ? replacementCaller : c.call!)
            return OpPileupVoice(text: text,
                                 pitch: audible(tone + offset),
                                 wpm: max(minimumWpm, wpm + c.wpmDelta),
                                 gain: c.gain,
                                 delay: c.delay,
                                 isYou: isYou)
        }
    }

    // MARK: Offset maths
    //
    // All in hertz. An "offset" is a frequency relative to the activator's;
    // higher frequency sounds higher, as on the upper sideband (CW-reverse
    // would flip the signs and is not modelled).

    /// The pitch you hear a station at: your tone, plus how far they are from
    /// where your receiver listens (the VFO, moved by RIT).
    public static func heardPitch(tone: Double, station: Double, vfo: Double, rit: Double) -> Double {
        tone + station - (vfo + rit)
    }

    /// How far from the station you transmit (the VFO, moved by XIT).
    public static func transmitOffset(station: Double, vfo: Double, xit: Double) -> Double {
        vfo + xit - station
    }

    public static func isZeroBeat(_ transmitOffset: Double) -> Bool {
        abs(transmitOffset) <= zeroBeatToleranceHz
    }

    public static func audible(_ pitch: Double) -> Double { max(minimumPitchHz, pitch) }

    /// Where the drill puts the activator in round `round` (the table cycles).
    public static func drillStart(round: Int) -> Double {
        let n = drillStarts.count
        return drillStarts[((round % n) + n) % n]
    }
}

// MARK: - Scenario run

/// A lesson's scenarios answered once each, in order. A wrong answer is a
/// mistake and still moves on; the run is clean when it finished without one.
public struct OpScenarioRun: Equatable, Sendable {
    public let scenarios: [OpScenario]
    public private(set) var index = 0
    public private(set) var mistakes = 0

    public init(scenarios: [OpScenario]) { self.scenarios = scenarios }

    public var current: OpScenario? { index < scenarios.count ? scenarios[index] : nil }
    public var isFinished: Bool { index >= scenarios.count }
    public var isClean: Bool { isFinished && mistakes == 0 }

    /// Answer the current scenario with `choice` (its index in the scenario's
    /// own order). True when it is one of the accepted answers; false when
    /// wrong or finished.
    @discardableResult
    public mutating func answer(_ choice: Int) -> Bool {
        guard let s = current else { return false }
        let right = s.accepts(index: choice)
        if !right { mistakes += 1 }
        index += 1
        return right
    }

    /// The same, by choice value — what a screen with shuffled buttons has.
    @discardableResult
    public mutating func answer(choice: OpChoice) -> Bool {
        guard let s = current, let i = s.choices.firstIndex(of: choice) else { return false }
        return answer(i)
    }
}

// MARK: - Progress

/// Which lessons have passed, which have a clean scenario run, and whether
/// the offsetting drill has passed. Persisted by the app; the drill's in-a-row
/// streak is not (three in a row means three in one sitting).
public struct OperatingProcedureProgress: Codable, Equatable, Sendable {
    public private(set) var passed: Set<OpLesson> = []
    public private(set) var cleanRuns: Set<OpLesson> = []
    public private(set) var drillPassed = false
    public private(set) var drillStreak = 0

    enum CodingKeys: String, CodingKey { case passed, cleanRuns, drillPassed }

    public init() {}

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // Unknown lesson names (from a newer build) are skipped, not fatal.
        let p = try c.decodeIfPresent([String].self, forKey: .passed) ?? []
        let r = try c.decodeIfPresent([String].self, forKey: .cleanRuns) ?? []
        passed = Set(p.compactMap(OpLesson.init(rawValue:)))
        cleanRuns = Set(r.compactMap(OpLesson.init(rawValue:)))
        drillPassed = try c.decodeIfPresent(Bool.self, forKey: .drillPassed) ?? false
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(passed.map(\.rawValue).sorted(), forKey: .passed)
        try c.encode(cleanRuns.map(\.rawValue).sorted(), forKey: .cleanRuns)
        try c.encode(drillPassed, forKey: .drillPassed)
    }

    public func hasPassed(_ lesson: OpLesson) -> Bool { passed.contains(lesson) }
    public func hasCleanRun(_ lesson: OpLesson) -> Bool { cleanRuns.contains(lesson) }
    public var passedCount: Int { passed.count }
    public var isComplete: Bool { OpLesson.allCases.allSatisfy(passed.contains) }
    public var nextLesson: OpLesson? { OpLesson.allCases.first { !passed.contains($0) } }

    /// Count one finished scenario run. True when it passed the lesson just now.
    @discardableResult
    public mutating func recordRun(_ lesson: OpLesson, clean: Bool) -> Bool {
        guard clean else { return false }
        cleanRuns.insert(lesson)
        return settle(lesson)
    }

    /// Count one drill answer. True when it passed the offsetting lesson just now.
    @discardableResult
    public mutating func recordDrill(correct: Bool) -> Bool {
        guard correct else { drillStreak = 0; return false }
        drillStreak += 1
        guard drillStreak >= OperatingProcedure.zeroBeatStreakToPass else { return false }
        drillStreak = 0
        drillPassed = true
        return settle(.offset)
    }

    private mutating func settle(_ lesson: OpLesson) -> Bool {
        guard !passed.contains(lesson), cleanRuns.contains(lesson),
              lesson != .offset || drillPassed else { return false }
        passed.insert(lesson)
        return true
    }
}
