import Foundation

// The Sending Analyzer (#241, #234, #235): the operator is shown a text, sends
// it on whatever key they have, and gets back what was copied, how it lines up
// against the text, and how their elements and spacing compare with the
// 1:3 / 1:3:7 standard.
//
// Everything here is pure and works on one thing: a list of key-down/key-up
// times. Where those come from is the app's business — the on-screen key, a
// Vail adapter or other MIDI key, the microphone (`ToneKeyingDetector`), and
// later the on-screen paddles all reduce to the same stream, so none of them
// needs anything from this file but `KeyingRecorder`.
//
// The spec this implements is written out in `fixtures/sending-analysis.json`
// (`derivation`), which pins both ports to the same numbers. Read it before
// changing a threshold here; the Kotlin twin is `SendingAnalysis.kt`.

/// The kind of key the operator is sending on. It changes what the analysis
/// may fairly judge: a keyer times every element itself, and a bug times its
/// dits, so feedback about those parts would be about the machine.
public enum SendingKeyType: String, CaseIterable, Codable, Sendable, Identifiable {
    /// Straight key — every element and gap is hand-timed.
    case straight
    /// Semi-automatic "bug": dits machine-timed, dahs formed by hand.
    case bug
    /// Cootie / sideswiper — hand-timed like a straight key, sideways.
    case cootie
    /// Paddles through an electronic (iambic) keyer — elements machine-timed;
    /// only the spacing between characters and words is the operator's.
    case keyer

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .straight: return "Straight key"
        case .bug:      return "Bug"
        case .cootie:   return "Cootie"
        case .keyer:    return "Paddles (keyer)"
        }
    }

    /// Whether the operator, not a machine, forms the dahs and the gaps
    /// inside a character — i.e. whether element feedback is about them.
    public var handTimesElements: Bool { self != .keyer }
    /// Whether the dits are hand-formed (and so their evenness is theirs).
    public var handTimesDits: Bool { self == .straight || self == .cootie }
}

/// One key-down interval, in milliseconds on any clock that is consistent
/// within one attempt (wall clock for a key, sample count for the mic).
public struct KeyMark: Sendable, Equatable, Codable {
    public var downMs: Double
    public var upMs: Double
    public init(downMs: Double, upMs: Double) {
        self.downMs = downMs
        self.upMs = upMs
    }
    public var durationMs: Double { upMs - downMs }
}

/// Collects key edges from any input into `KeyMark`s. Any source that can say
/// "key went down at t" and "key went up at t" plugs in here — which is the
/// whole interface the analyzer asks of a keyer, paddles included.
public struct KeyingRecorder: Sendable, Equatable {
    public private(set) var marks: [KeyMark] = []
    private var downAt: Double?

    public init() {}

    public var isDown: Bool { downAt != nil }
    /// Time of the most recent edge, or nil before the first.
    public private(set) var lastEdgeMs: Double?

    public mutating func keyDown(atMs ms: Double) {
        guard downAt == nil else { return }
        downAt = ms
        lastEdgeMs = ms
    }

    public mutating func keyUp(atMs ms: Double) {
        guard let down = downAt else { return }
        downAt = nil
        lastEdgeMs = ms
        if ms > down { marks.append(KeyMark(downMs: down, upMs: ms)) }
    }

    public mutating func reset() {
        marks.removeAll()
        downAt = nil
        lastEdgeMs = nil
    }
}

/// Feedback the analysis can give, in the order it is shown. The raw values
/// are the codes `fixtures/sending-analysis.json` pins.
public enum SendingFeedback: String, CaseIterable, Sendable {
    case slowerThanTarget, fasterThanTarget
    case dahsShort, dahsLong, ditsUneven, dahsUneven
    case elementGapsShort, elementGapsLong
    case charGapsShort, charGapsLong, charGapsUneven
    case wordGapsShort, wordGapsLong
    case wordsRunTogether, wordsSplit
    case perfectCopy, timingOnTarget

    /// The codes that are about timing (everything `timingOnTarget` stands
    /// for the absence of).
    public var isTiming: Bool {
        switch self {
        case .wordsRunTogether, .wordsSplit, .perfectCopy, .timingOnTarget: return false
        default: return true
        }
    }

    /// True for the two "well done" lines, so the UI can colour them.
    public var isPraise: Bool { self == .perfectCopy || self == .timingOnTarget }
}

public struct SendingAnalysis: Sendable {

    // MARK: Spec constants (see fixtures/sending-analysis.json, derivation)

    /// Key-down or key-up intervals shorter than this are contact bounce.
    public static let glitchMs: Double = 5
    /// Below this max/min duration ratio the marks are one kind of element.
    public static let separationRatio: Double = 1.8
    /// A gap shorter than this many units is inside a character.
    public static let intraGapLimit: Double = 2
    /// A gap of at least this many spacing units is a word break.
    public static let wordGapLimit: Double = 5
    /// Lower bin edges of the spacing histograms, in spacing units.
    public static let charGapBinEdges: [Double] = [1.75, 2.25, 2.75, 3.25, 3.75, 4.25]
    public static let charGapBinLabels = ["<2", "2", "2.5", "3", "3.5", "4", "4.5+"]
    public static let wordGapBinEdges: [Double] = [5.5, 6.5, 7.5, 8.5, 9.5, 10.5]
    public static let wordGapBinLabels = ["5", "6", "7", "8", "9", "10", "11+"]
    /// Index of the ideal bin (3 and 7 units) in each histogram.
    public static let idealCharGapBin = 3
    public static let idealWordGapBin = 2

    // MARK: Result types

    /// Mean and population standard deviation of one kind of interval.
    public struct Stat: Sendable, Equatable {
        public let count: Int
        public let mean: Double
        public let sd: Double
        public init(_ values: [Double]) {
            count = values.count
            guard !values.isEmpty else { mean = 0; sd = 0; return }
            let m = values.reduce(0, +) / Double(values.count)
            mean = m
            sd = (values.reduce(0) { $0 + ($1 - m) * ($1 - m) } / Double(values.count)).squareRoot()
        }
        /// Coefficient of variation (sd / mean), 0 when there is nothing.
        public var cv: Double { mean > 0 ? sd / mean : 0 }
    }

    /// One character as keyed.
    public struct SentCharacter: Sendable, Equatable {
        /// Dits and dahs as "." and "-".
        public let pattern: String
        /// What the pattern reads as, or `MorseDecoder.unknownMarker`.
        public let character: Character
        public let startMs: Double
        public let endMs: Double
    }

    /// One step of the alignment between the target and what was sent.
    public struct AlignmentOp: Sendable, Equatable {
        public enum Kind: String, Sendable { case match, substitute, missing, extra }
        public let kind: Kind
        /// The target character (nil for `extra`). A space is a word break.
        public let expected: Character?
        /// What was sent in its place (nil for `missing`).
        public let sent: Character?
        /// The keyed pattern of `sent`, when it is a character.
        public let sentPattern: String?

        /// The fixture's compact form: "=H", "~87", "- " (missing space), "+9".
        public var code: String {
            switch kind {
            case .match:      return "=" + String(expected ?? "?")
            case .substitute: return "~" + String(expected ?? "?") + String(sent ?? "?")
            case .missing:    return "-" + String(expected ?? "?")
            case .extra:      return "+" + String(sent ?? "?")
            }
        }
    }

    // MARK: Inputs, as analysed

    public let target: String
    public let keyType: SendingKeyType
    public let targetCharacterWpm: Double
    public let targetEffectiveWpm: Double
    /// The marks after contact-bounce clean-up.
    public let marks: [KeyMark]

    // MARK: Results

    public let sentCharacters: [SentCharacter]
    /// What was copied, words separated by single spaces.
    public let decodedText: String
    public let alignment: [AlignmentOp]
    /// Non-space characters in the target, and how many were sent correctly.
    public let targetCharacterCount: Int
    public let correctCharacterCount: Int
    /// Word breaks sent too short (words run together) and gaps inside a word
    /// long enough to read as a break (a word split).
    public let missingWordBreaks: Int
    public let extraWordBreaks: Int

    /// Measured unit (one dit) in ms: the mean dit, or a third of the mean dah
    /// when nothing was a dit.
    public let unitMs: Double
    /// Spacing unit / element unit at the target speeds (1 without Farnsworth).
    public let farnsworthFactor: Double
    /// Element durations in ms.
    public let dits: Stat
    public let dahs: Stat
    /// Gaps inside characters, in units.
    public let elementGaps: Stat
    /// Gaps between characters and between words, in spacing units.
    public let characterGaps: Stat
    public let wordGaps: Stat
    public let characterGapHistogram: [Int]
    public let wordGapHistogram: [Int]
    /// Per-gap values in spacing units, in sending order, for plotting.
    public let characterGapValues: [Double]
    public let wordGapValues: [Double]
    /// 1200 / unit.
    public let characterWpm: Double
    /// PARIS-standard length of what was sent over the time it took.
    public let effectiveWpm: Double
    public let feedback: [SendingFeedback]

    public var accuracy: Double {
        targetCharacterCount > 0 ? Double(correctCharacterCount) / Double(targetCharacterCount) : 0
    }
    /// Dah length over dit length, when both were sent.
    public var dahDitRatio: Double? {
        dits.count > 0 && dahs.count > 0 && dits.mean > 0 ? dahs.mean / dits.mean : nil
    }
    public var isEmpty: Bool { marks.isEmpty }

    // MARK: Target text

    /// The target as the analysis compares against it: uppercase, whitespace
    /// runs collapsed to one space, characters with no Morse pattern dropped.
    public static func normalizedTarget(_ text: String) -> String {
        var out = ""
        var pendingSpace = false
        for ch in text.uppercased() {
            if ch.isWhitespace {
                pendingSpace = !out.isEmpty
            } else if MorseCode.pattern(for: ch) != nil {
                if pendingSpace { out.append(" "); pendingSpace = false }
                out.append(ch)
            }
        }
        return out
    }

    /// Merge marks split by contact bounce and drop the bounce itself.
    public static func cleaned(_ raw: [KeyMark]) -> [KeyMark] {
        let sorted = raw.filter { $0.upMs > $0.downMs }.sorted { $0.downMs < $1.downMs }
        var merged: [KeyMark] = []
        for m in sorted {
            if var last = merged.last, m.downMs - last.upMs < glitchMs {
                last.upMs = max(last.upMs, m.upMs)
                merged[merged.count - 1] = last
            } else {
                merged.append(m)
            }
        }
        return merged.filter { $0.durationMs >= glitchMs }
    }

    // MARK: Analysis

    public init(marks rawMarks: [KeyMark],
                target rawTarget: String,
                keyType: SendingKeyType,
                characterWpm: Double,
                effectiveWpm: Double? = nil) {
        let cw = max(5, characterWpm)
        let ew = min(cw, max(1, effectiveWpm ?? cw))
        self.target = Self.normalizedTarget(rawTarget)
        self.keyType = keyType
        self.targetCharacterWpm = cw
        self.targetEffectiveWpm = ew
        let marks = Self.cleaned(rawMarks)
        self.marks = marks
        let timing = MorseTiming(characterWpm: cw, effectiveWpm: ew)
        let k = timing.spacingUnit / timing.unit
        self.farnsworthFactor = k
        let targetUnit = 1200.0 / cw

        // 1. Dit or dah.
        let durations = marks.map(\.durationMs)
        let isDah = Self.classify(durations, targetUnitMs: targetUnit)
        let ditValues = zip(durations, isDah).filter { !$0.1 }.map(\.0)
        let dahValues = zip(durations, isDah).filter { $0.1 }.map(\.0)
        let dits = Stat(ditValues)
        let dahs = Stat(dahValues)
        self.dits = dits
        self.dahs = dahs
        let unit: Double
        if dits.count > 0 { unit = dits.mean } else if dahs.count > 0 { unit = dahs.mean / 3 } else { unit = targetUnit }
        self.unitMs = unit

        // 2. Gaps: inside a character, between characters, between words.
        var chars: [SentCharacter] = []
        var words: [String] = []
        var word = ""
        var pattern = ""
        var charStart = marks.first?.downMs ?? 0
        var intra: [Double] = [], charGaps: [Double] = [], wordGapsV: [Double] = []
        var standardUnits = 0.0
        func closeCharacter(endMs: Double) {
            guard !pattern.isEmpty else { return }
            let ch = MorseCode.character(forPattern: pattern) ?? MorseDecoder.unknownMarker
            chars.append(SentCharacter(pattern: pattern, character: ch, startMs: charStart, endMs: endMs))
            word.append(ch)
            pattern = ""
        }
        for (i, m) in marks.enumerated() {
            if i > 0 {
                let gap = m.downMs - marks[i - 1].upMs
                let units = gap / unit
                if units < Self.intraGapLimit {
                    intra.append(units)
                    standardUnits += 1
                } else {
                    closeCharacter(endMs: marks[i - 1].upMs)
                    charStart = m.downMs
                    let spacing = gap / (unit * k)
                    if spacing < Self.wordGapLimit {
                        charGaps.append(spacing)
                        standardUnits += 3
                    } else {
                        wordGapsV.append(spacing)
                        standardUnits += 7
                        words.append(word)
                        word = ""
                    }
                }
            }
            pattern.append(isDah[i] ? "-" : ".")
            standardUnits += isDah[i] ? 3 : 1
        }
        if let last = marks.last { closeCharacter(endMs: last.upMs) }
        if !word.isEmpty { words.append(word) }
        self.sentCharacters = chars
        let decoded = words.joined(separator: " ")
        self.decodedText = decoded
        let elementGaps = Stat(intra)
        let characterGaps = Stat(charGaps)
        let wordGaps = Stat(wordGapsV)
        self.elementGaps = elementGaps
        self.characterGaps = characterGaps
        self.wordGaps = wordGaps
        self.characterGapValues = charGaps
        self.wordGapValues = wordGapsV
        self.characterGapHistogram = Self.histogram(charGaps, edges: Self.charGapBinEdges)
        self.wordGapHistogram = Self.histogram(wordGapsV, edges: Self.wordGapBinEdges)
        let charWpm = marks.isEmpty ? 0 : 1200 / unit
        self.characterWpm = charWpm
        let elapsed = (marks.last?.upMs ?? 0) - (marks.first?.downMs ?? 0)
        self.effectiveWpm = elapsed > 0 ? 1200 * standardUnits / elapsed : 0

        // 3. Line the copy up against the target.
        let target = self.target
        let ops = Self.align(target: Array(target), sent: Array(decoded), sentCharacters: chars)
        self.alignment = ops
        let targetCount = target.filter { $0 != " " }.count
        let correct = ops.filter { $0.kind == .match && $0.expected != " " }.count
        let missingBreaks = ops.filter { $0.kind == .missing && $0.expected == " " }.count
        let extraBreaks = ops.filter { $0.kind == .extra && $0.sent == " " }.count
        self.targetCharacterCount = targetCount
        self.correctCharacterCount = correct
        self.missingWordBreaks = missingBreaks
        self.extraWordBreaks = extraBreaks

        // 4. Plain-English feedback, in `SendingFeedback` order.
        var fb: [SendingFeedback] = []
        let enough = marks.count >= 5
        if enough, charWpm < 0.85 * cw { fb.append(.slowerThanTarget) }
        if enough, charWpm > 1.15 * cw { fb.append(.fasterThanTarget) }
        if keyType.handTimesElements, dits.count >= 3, dahs.count >= 3 {
            let ratio = dahs.mean / dits.mean
            if ratio < 2.5 { fb.append(.dahsShort) }
            if ratio > 3.5 { fb.append(.dahsLong) }
        }
        if keyType.handTimesDits, dits.count >= 5, dits.cv > 0.25 { fb.append(.ditsUneven) }
        if keyType.handTimesElements, dahs.count >= 5, dahs.cv > 0.25 { fb.append(.dahsUneven) }
        if keyType.handTimesElements, elementGaps.count >= 3 {
            if elementGaps.mean < 0.7 { fb.append(.elementGapsShort) }
            if elementGaps.mean > 1.5 { fb.append(.elementGapsLong) }
        }
        if characterGaps.count >= 3 {
            if characterGaps.mean < 2.5 { fb.append(.charGapsShort) }
            if characterGaps.mean > 4.0 { fb.append(.charGapsLong) }
        }
        if characterGaps.count >= 5, characterGaps.cv > 0.3 { fb.append(.charGapsUneven) }
        if wordGaps.count >= 2 {
            if wordGaps.mean < 6 { fb.append(.wordGapsShort) }
            if wordGaps.mean > 9 { fb.append(.wordGapsLong) }
        }
        if missingBreaks > 0 { fb.append(.wordsRunTogether) }
        if extraBreaks > 0 { fb.append(.wordsSplit) }
        if targetCount > 0, correct == targetCount, ops.allSatisfy({ $0.kind == .match }) {
            fb.append(.perfectCopy)
        }
        if enough, !fb.contains(where: \.isTiming) { fb.append(.timingOnTarget) }
        self.feedback = fb
    }

    /// Dit (false) or dah (true) for each duration. Two-means on the log
    /// durations, seeded at the extremes; when the extremes are closer than
    /// `separationRatio` (or the clusters end up that close) everything is one
    /// kind, split at two target units.
    static func classify(_ durations: [Double], targetUnitMs: Double) -> [Bool] {
        guard let lo = durations.min(), let hi = durations.max(), lo > 0 else {
            return durations.map { _ in false }
        }
        let single = durations.map { $0 >= 2 * targetUnitMs }
        guard hi / lo >= separationRatio else { return single }
        let logs = durations.map { Foundation.log($0) }
        var cDit = Foundation.log(lo), cDah = Foundation.log(hi)
        var isDah = logs.map { $0 >= (cDit + cDah) / 2 }
        for _ in 0..<20 {
            let d = zip(logs, isDah).filter { !$0.1 }.map(\.0)
            let h = zip(logs, isDah).filter { $0.1 }.map(\.0)
            guard !d.isEmpty, !h.isEmpty else { break }
            cDit = d.reduce(0, +) / Double(d.count)
            cDah = h.reduce(0, +) / Double(h.count)
            let next = logs.map { $0 >= (cDit + cDah) / 2 }
            if next == isDah { break }
            isDah = next
        }
        if Foundation.exp(cDah - cDit) < separationRatio { return single }
        return isDah
    }

    static func histogram(_ values: [Double], edges: [Double]) -> [Int] {
        var bins = [Int](repeating: 0, count: edges.count + 1)
        for v in values { bins[edges.filter { v >= $0 }.count] += 1 }
        return bins
    }

    /// Edit-distance alignment. Substituting one character for another costs
    /// 1; a space never substitutes for a character (it is a word break, not a
    /// letter). The backtrace prefers the diagonal, then a missing target
    /// token, then an extra sent one, so ties resolve the same on both ports.
    static func align(target: [Character], sent: [Character],
                      sentCharacters: [SentCharacter]) -> [AlignmentOp] {
        let n = target.count, m = sent.count
        func cost(_ a: Character, _ b: Character) -> Int? {
            if a == b { return 0 }
            if (a == " ") != (b == " ") { return nil }
            return 1
        }
        var dp = [[Int]](repeating: [Int](repeating: 0, count: m + 1), count: n + 1)
        for i in 0...n { dp[i][0] = i }
        for j in 0...m { dp[0][j] = j }
        if n > 0, m > 0 {
            for i in 1...n {
                for j in 1...m {
                    var best = min(dp[i - 1][j], dp[i][j - 1]) + 1
                    if let c = cost(target[i - 1], sent[j - 1]) { best = min(best, dp[i - 1][j - 1] + c) }
                    dp[i][j] = best
                }
            }
        }
        // Sent position → keyed pattern (spaces have none).
        var patternAt = [String?](repeating: nil, count: m)
        var k = 0
        for (j, ch) in sent.enumerated() where ch != " " {
            if k < sentCharacters.count { patternAt[j] = sentCharacters[k].pattern }
            k += 1
        }
        var ops: [AlignmentOp] = []
        var i = n, j = m
        while i > 0 || j > 0 {
            if i > 0, j > 0, let c = cost(target[i - 1], sent[j - 1]), dp[i][j] == dp[i - 1][j - 1] + c {
                ops.append(AlignmentOp(kind: c == 0 ? .match : .substitute, expected: target[i - 1],
                                       sent: sent[j - 1], sentPattern: patternAt[j - 1]))
                i -= 1; j -= 1
            } else if i > 0, dp[i][j] == dp[i - 1][j] + 1 {
                ops.append(AlignmentOp(kind: .missing, expected: target[i - 1], sent: nil, sentPattern: nil))
                i -= 1
            } else {
                ops.append(AlignmentOp(kind: .extra, expected: nil, sent: sent[j - 1], sentPattern: patternAt[j - 1]))
                j -= 1
            }
        }
        return ops.reversed()
    }

    // MARK: Wording

    /// One line of plain English for a feedback code, with this attempt's
    /// numbers in it.
    public func message(for code: SendingFeedback) -> String {
        func f1(_ v: Double) -> String { String(format: "%.1f", v) }
        func pct(_ v: Double) -> String { "\(Int((v * 100).rounded()))%" }
        let spacingNote = farnsworthFactor > 1.001 ? " (Farnsworth spacing units)" : ""
        switch code {
        case .slowerThanTarget:
            return "You sent at about \(Int(characterWpm.rounded())) WPM, slower than the \(Int(targetCharacterWpm.rounded())) WPM you set."
        case .fasterThanTarget:
            return "You sent at about \(Int(characterWpm.rounded())) WPM, faster than the \(Int(targetCharacterWpm.rounded())) WPM you set."
        case .dahsShort:
            return "Dahs are short: \(f1(dahDitRatio ?? 0)) dits long on average. Aim for 3."
        case .dahsLong:
            return "Dahs are long: \(f1(dahDitRatio ?? 0)) dits long on average. Aim for 3."
        case .ditsUneven:
            return "Dit lengths vary a lot (±\(pct(dits.cv))). Aim for an even rhythm."
        case .dahsUneven:
            return "Dah lengths vary a lot (±\(pct(dahs.cv))). Aim for an even rhythm."
        case .elementGapsShort:
            return "Gaps inside characters are clipped: \(f1(elementGaps.mean)) units, aim for 1."
        case .elementGapsLong:
            return "Gaps inside characters are long: \(f1(elementGaps.mean)) units, aim for 1. Letters can break apart."
        case .charGapsShort:
            return "Characters run together: spacing averages \(f1(characterGaps.mean)) units\(spacingNote), aim for 3."
        case .charGapsLong:
            return "Character spacing is long: \(f1(characterGaps.mean)) units\(spacingNote) on average, aim for 3."
        case .charGapsUneven:
            return "Character spacing is uneven (±\(pct(characterGaps.cv)))."
        case .wordGapsShort:
            return "Word spacing is short: \(f1(wordGaps.mean)) units\(spacingNote) on average, aim for 7."
        case .wordGapsLong:
            return "Word spacing is long: \(f1(wordGaps.mean)) units\(spacingNote) on average, aim for 7."
        case .wordsRunTogether:
            return missingWordBreaks == 1
                ? "One word break was too short, so two words ran together."
                : "\(missingWordBreaks) word breaks were too short, so words ran together."
        case .wordsSplit:
            return extraWordBreaks == 1
                ? "One gap inside a word was long enough to read as a word break."
                : "\(extraWordBreaks) gaps inside words were long enough to read as word breaks."
        case .perfectCopy:
            return "Every character copied correctly."
        case .timingOnTarget:
            return "Element and spacing timing are on target."
        }
    }
}

/// Texts to send, and the pangram list (#241's own example among them).
public enum SendingTargets {
    public static let pangrams: [String] = [
        "JACKDAWS LOVE MY BIG SPHINX OF QUARTZ",
        "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG",
        "PACK MY BOX WITH FIVE DOZEN LIQUOR JUGS",
        "SPHINX OF BLACK QUARTZ JUDGE MY VOW",
        "HOW VEXINGLY QUICK DAFT ZEBRAS JUMP",
        "THE FIVE BOXING WIZARDS JUMP QUICKLY"
    ]
}

/// What the Sending Analyzer remembers between attempts: per-character and
/// per-pair error rates, the mix-ups (target → what was sent), and a short
/// history of attempts. Kept apart from the copy-side `ConfusionMatrix`,
/// which drives the receive drills: a sending slip is a different skill.
public struct SendingRecord: Codable, Sendable, Equatable {

    public struct Tally: Codable, Sendable, Equatable {
        public var attempts: Int
        public var misses: Int
        public init(attempts: Int = 0, misses: Int = 0) {
            self.attempts = attempts
            self.misses = misses
        }
        public var missRate: Double { attempts > 0 ? Double(misses) / Double(attempts) : 0 }
    }

    public struct Attempt: Codable, Sendable, Equatable {
        public var date: Date
        public var accuracy: Double
        public var characterWpm: Double
        public var effectiveWpm: Double
        public var characters: Int
        public var characterGapMean: Double?
        public var wordGapMean: Double?
    }

    /// Keyed by the character (as a one-character string).
    public var characters: [String: Tally] = [:]
    /// Keyed by two adjacent characters of a target word ("LL").
    public var pairs: [String: Tally] = [:]
    public var mixups = ConfusionMatrix()
    /// Newest last, capped at `attemptLimit`.
    public var attempts: [Attempt] = []

    public static let attemptLimit = 30

    public init() {}

    public var isEmpty: Bool { characters.isEmpty && attempts.isEmpty }

    /// Fold one attempt in. Every target character is an attempt at that
    /// character; one sent as anything else, or not sent, is a miss. Each
    /// adjacent pair inside a target word is an attempt at the pair, missed
    /// when either half is. A substitution by a real character is a mix-up.
    public mutating func record(_ analysis: SendingAnalysis, at date: Date = Date()) {
        guard analysis.targetCharacterCount > 0, !analysis.isEmpty else { return }
        var word: [(Character, Bool)] = []
        func closeWord() {
            if word.count >= 2 {
                for p in 0..<(word.count - 1) {
                    let key = String([word[p].0, word[p + 1].0])
                    var t = pairs[key] ?? Tally()
                    t.attempts += 1
                    if !(word[p].1 && word[p + 1].1) { t.misses += 1 }
                    pairs[key] = t
                }
            }
            word.removeAll()
        }
        for op in analysis.alignment {
            guard let expected = op.expected else { continue }
            if expected == " " { closeWord(); continue }
            let ok = op.kind == .match
            var t = characters[String(expected)] ?? Tally()
            t.attempts += 1
            if !ok { t.misses += 1 }
            characters[String(expected)] = t
            word.append((expected, ok))
            if op.kind == .substitute, let sent = op.sent, sent != MorseDecoder.unknownMarker {
                mixups.record(target: expected, chosen: sent)
            }
        }
        closeWord()
        attempts.append(Attempt(date: date,
                                accuracy: analysis.accuracy,
                                characterWpm: analysis.characterWpm,
                                effectiveWpm: analysis.effectiveWpm,
                                characters: analysis.targetCharacterCount,
                                characterGapMean: analysis.characterGaps.count > 0 ? analysis.characterGaps.mean : nil,
                                wordGapMean: analysis.wordGaps.count > 0 ? analysis.wordGaps.mean : nil))
        if attempts.count > Self.attemptLimit { attempts.removeFirst(attempts.count - Self.attemptLimit) }
    }

    /// Characters with at least one miss, worst miss rate first (then most
    /// misses, then alphabetical), among those tried `minAttempts` times.
    public func problemCharacters(minAttempts: Int = 3, limit: Int = 6) -> [(key: String, tally: Tally)] {
        Self.worst(characters, minAttempts: minAttempts, limit: limit)
    }

    /// Same, for adjacent pairs.
    public func problemPairs(minAttempts: Int = 3, limit: Int = 6) -> [(key: String, tally: Tally)] {
        Self.worst(pairs, minAttempts: minAttempts, limit: limit)
    }

    static func worst(_ map: [String: Tally], minAttempts: Int, limit: Int) -> [(key: String, tally: Tally)] {
        map.filter { $0.value.attempts >= minAttempts && $0.value.misses > 0 }
            .sorted { a, b in
                if a.value.missRate != b.value.missRate { return a.value.missRate > b.value.missRate }
                if a.value.misses != b.value.misses { return a.value.misses > b.value.misses }
                return a.key < b.key
            }
            .prefix(limit)
            .map { (key: $0.key, tally: $0.value) }
    }
}
