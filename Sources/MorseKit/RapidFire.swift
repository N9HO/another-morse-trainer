import Foundation

/// What Rapid Fire streams, back to back. Each item is sent as plain text and
/// the learner copies it (typing, keying, or just reviewing the list at the end).
public enum RapidFireContent: String, Codable, CaseIterable, Identifiable, Sendable {
    case callsigns, words, numbers, states, mixed

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .callsigns: return "Call signs"
        case .words:     return "Words"
        case .numbers:   return "Number groups"
        case .states:    return "State abbreviations"
        case .mixed:     return "Mixed"
        }
    }
}

/// A streaming free-recall quiz: it hands out one generated item at a time (a
/// call sign, word, number group, or state) and grades a typed/keyed copy of it.
/// Pure logic — seedable for tests, no audio or UI. Drives the same quiz loop as
/// the other modes via `QuizSource`, so it plugs straight into `AppModel`.
public final class RapidFireQuiz: QuizSource {

    public struct Config: Sendable, Equatable {
        public var content: RapidFireContent
        /// Call-sign shapes to draw from (1×2, 2×1, …). Empty falls back to the
        /// common defaults.
        public var callsignFormats: [CallsignFormat]
        public var callsignUSOnly: Bool
        /// Inclusive word-length bounds for the `.words` content.
        public var wordMinLength: Int
        public var wordMaxLength: Int
        /// How many digits in each `.numbers` group.
        public var numberCount: Int

        public init(content: RapidFireContent = .callsigns,
                    callsignFormats: [CallsignFormat] = CallsignFormat.commonDefaults,
                    callsignUSOnly: Bool = true,
                    wordMinLength: Int = 3,
                    wordMaxLength: Int = 6,
                    numberCount: Int = 5) {
            self.content = content
            self.callsignFormats = callsignFormats
            self.callsignUSOnly = callsignUSOnly
            self.wordMinLength = wordMinLength
            self.wordMaxLength = wordMaxLength
            self.numberCount = numberCount
        }
    }

    public let config: Config
    private var rng: any RandomNumberGenerator
    private let wordPool: [String]
    private var lastAnswer = ""

    public init(config: Config,
                rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.config = config
        self.rng = rng
        let lo = max(1, min(config.wordMinLength, config.wordMaxLength))
        let hi = max(lo, config.wordMaxLength)
        let filtered = MorseData.rankedWords.filter { $0.count >= lo && $0.count <= hi }
        self.wordPool = filtered.isEmpty ? MorseData.rankedWords : filtered
    }

    // MARK: QuizSource

    public var summary: String {
        switch config.content {
        case .callsigns: return "Call signs"
        case .words:
            let lo = max(1, min(config.wordMinLength, config.wordMaxLength))
            let hi = max(lo, config.wordMaxLength)
            return lo == hi ? "\(lo)-letter words" : "Words \(lo)–\(hi) letters"
        case .numbers:
            let n = max(1, config.numberCount)
            return "\(n)-digit numbers"
        case .states:    return "State abbreviations"
        case .mixed:     return "Mixed copy"
        }
    }

    public func nextDrill() -> Drill {
        let text = generate()
        lastAnswer = text
        // Free recall: a single "option" (the answer) keeps the Drill valid for
        // the shared loop; the Rapid Fire UI never shows a choice grid.
        return Drill(playable: .text(text),
                     options: [text],
                     correct: text,
                     revealPrimary: text,
                     revealSecondary: "")
    }

    public func record(choice: String, ttr: TimeInterval) -> DrillOutcome {
        DrillOutcome(correct: Self.normalize(choice) == Self.normalize(lastAnswer),
                     unlocked: nil)
    }

    /// Case- and space-insensitive comparison, so "K1 ABC" copies as "K1ABC".
    static func normalize(_ s: String) -> String {
        s.uppercased().filter { !$0.isWhitespace }
    }

    // MARK: Generation

    private func generate() -> String {
        switch config.content {
        case .callsigns: return makeCallsign()
        case .words:     return wordPool.randomElement(using: &rng) ?? "THE"
        case .numbers:   return makeNumberGroup()
        case .states:    return MorseData.usStates.randomElement(using: &rng) ?? "OH"
        case .mixed:     return makeMixed()
        }
    }

    private func makeCallsign() -> String {
        let formats = config.callsignFormats.isEmpty
            ? CallsignFormat.commonDefaults : config.callsignFormats
        return CallsignGenerator.generate(formats: formats,
                                          usOnly: config.callsignUSOnly,
                                          using: &rng)
    }

    private func makeNumberGroup() -> String {
        let n = max(1, config.numberCount)
        return String((0..<n).map { _ in Character("\(Int.random(in: 0...9, using: &rng))") })
    }

    private func makeMixed() -> String {
        switch Int.random(in: 0..<4, using: &rng) {
        case 0:  return makeCallsign()
        case 1:  return wordPool.randomElement(using: &rng) ?? "THE"
        case 2:  return makeNumberGroup()
        default: return MorseData.usStates.randomElement(using: &rng) ?? "OH"
        }
    }
}
