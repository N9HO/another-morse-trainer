import Foundation

/// What Rapid Fire streams, back to back. Each item is sent as plain text and
/// the learner copies it (typing, keying, or just reviewing the list at the end).
public enum RapidFireContent: String, Codable, CaseIterable, Identifiable, Sendable {
    case callsigns, words, numbers, states, serials, names, power, mixed

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .callsigns: return "Call signs"
        case .words:     return "Words"
        case .numbers:   return "Number groups"
        case .states:    return "State abbreviations"
        case .serials:   return "Serial numbers"
        case .names:     return "Names"
        case .power:     return "Power"
        case .mixed:     return "Mixed"
        }
    }

    /// One-line explanation shown under the picker.
    public var blurb: String {
        switch self {
        case .callsigns: return "Random call signs in the shapes you pick."
        case .words:     return "Common words, within the length bounds you set."
        case .numbers:   return "Random digit groups, N digits each."
        case .states:    return "Two-letter US state abbreviations — add the ARRL/RAC Field Day sections for three-letter ones like EPA and STX."
        case .serials:   return "Contest serial numbers, 001–999, as a sprint or CWT station sends them."
        case .names:     return "Short operator names heard in CW QSOs and contest exchanges."
        case .power:     return "Transmit power as sent on the air — 5W, 100W, 1KW."
        case .mixed:     return "Call signs, words, number groups and states, shuffled."
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
        /// Widen the `.states` pool with the ARRL/RAC section abbreviations
        /// (`ContestData.arrlSections`), so EPA, STX and SDG turn up beside OH.
        public var statesIncludeSections: Bool
        /// Send `.serials` with cut numbers (T for 0, N for 9 …), the way the
        /// pileup does. Either form is accepted as the copy regardless.
        public var serialCutNumbers: Bool

        public init(content: RapidFireContent = .callsigns,
                    callsignFormats: [CallsignFormat] = CallsignFormat.commonDefaults,
                    callsignUSOnly: Bool = true,
                    wordMinLength: Int = 3,
                    wordMaxLength: Int = 6,
                    numberCount: Int = 5,
                    statesIncludeSections: Bool = false,
                    serialCutNumbers: Bool = false) {
            self.content = content
            self.callsignFormats = callsignFormats
            self.callsignUSOnly = callsignUSOnly
            self.wordMinLength = wordMinLength
            self.wordMaxLength = wordMaxLength
            self.numberCount = numberCount
            self.statesIncludeSections = statesIncludeSections
            self.serialCutNumbers = serialCutNumbers
        }
    }

    public let config: Config
    private var rng: any RandomNumberGenerator
    private let wordPool: [String]
    /// States, plus the ARRL/RAC sections when enabled (each abbreviation once).
    private let statePool: [String]
    private var lastAnswer = ""
    /// The last item was a serial number, so its copy is graded numerically
    /// (cut letters decoded, leading zeros ignored) rather than by text.
    private var lastIsSerial = false

    public init(config: Config,
                rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.config = config
        self.rng = rng
        let lo = max(1, min(config.wordMinLength, config.wordMaxLength))
        let hi = max(lo, config.wordMaxLength)
        let filtered = MorseData.rankedWords.filter { $0.count >= lo && $0.count <= hi }
        self.wordPool = filtered.isEmpty ? MorseData.rankedWords : filtered
        self.statePool = Self.statePool(includeSections: config.statesIncludeSections)
    }

    /// The `.states` pool: the 51 state abbreviations, widened with the
    /// ARRL/RAC sections when asked. Sections that are also states (CT, OH …)
    /// appear once.
    public static func statePool(includeSections: Bool) -> [String] {
        guard includeSections else { return MorseData.usStates }
        let states = Set(MorseData.usStates)
        return MorseData.usStates + ContestData.arrlSections.filter { !states.contains($0) }
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
        case .states:    return config.statesIncludeSections ? "States & sections" : "State abbreviations"
        case .serials:   return config.serialCutNumbers ? "Serial numbers (cut)" : "Serial numbers"
        case .names:     return "Names"
        case .power:     return "Power levels"
        case .mixed:     return "Mixed copy"
        }
    }

    public func nextDrill() -> Drill {
        lastIsSerial = config.content == .serials
        let text = generate()
        lastAnswer = text
        // A cut serial is *sent* as letters but *means* the digits: the answer
        // and the reveal are the true value (as the pileup logs it), and the
        // sent form rides along as the secondary line.
        let sent = lastIsSerial && config.serialCutNumbers
            ? CutNumbers.encode(text, enabled: Set(CutNumbers.cuttableDigits)) : text
        // Free recall: a single "option" (the answer) keeps the Drill valid for
        // the shared loop; the Rapid Fire UI never shows a choice grid.
        return Drill(playable: .text(sent),
                     options: [text],
                     correct: text,
                     revealPrimary: text,
                     revealSecondary: sent == text ? "" : "sent as \(sent)")
    }

    public func record(choice: String, ttr: TimeInterval) -> DrillOutcome {
        DrillOutcome(correct: Self.matches(choice, answer: lastAnswer, serial: lastIsSerial),
                     unlocked: nil)
    }

    /// Grade one copy. A serial is compared as a number — "TTA", "001" and "1"
    /// all copy 001, as the pileup accepts them — everything else as text.
    static func matches(_ choice: String, answer: String, serial: Bool) -> Bool {
        if serial, let a = Int(CutNumbers.decodeDigits(choice)), let b = Int(answer) {
            return a == b
        }
        return normalize(choice) == normalize(answer)
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
        case .states:    return statePool.randomElement(using: &rng) ?? "OH"
        case .serials:   return makeSerial()
        case .names:     return MorseData.opNames.randomElement(using: &rng) ?? "JIM"
        case .power:     return MorseData.powers.randomElement(using: &rng) ?? "100W"
        case .mixed:     return makeMixed()
        }
    }

    /// A contest serial, 001–999, zero-padded to three digits the way the
    /// pileup's basic contest sends it. Cut numbers are applied when sending,
    /// not here: this is the true value.
    private func makeSerial() -> String {
        String(format: "%03d", Int.random(in: 1...999, using: &rng))
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
        default: return statePool.randomElement(using: &rng) ?? "OH"
        }
    }
}
