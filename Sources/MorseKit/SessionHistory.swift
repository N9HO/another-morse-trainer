import Foundation

/// A completed practice session, kept so the learner can revisit how it went —
/// in particular the per-character "Instant Character Recognition" chart (#19).
///
/// Per-character data is only meaningful for single-character recognition drills,
/// so `characters` / `activeCharacters` are empty for word, QSO, story, and exam
/// sessions; those records still carry the aggregate stats.
public struct SessionRecord: Codable, Sendable, Identifiable, Equatable {
    public var id: UUID
    public var date: Date
    public var mode: String                 // TrainingMode rawValue
    public var characterWPM: Int
    public var effectiveWPM: Int
    public var attempts: Int
    public var correct: Int
    public var fastestTTR: TimeInterval?
    public var medianTTR: TimeInterval?
    public var durationSeconds: TimeInterval?
    /// Per-character results for characters actually drilled this session.
    public var characters: [CharResult]
    /// The full single-character set active during the session, so the chart can
    /// show a row per learned character (with a blank where one wasn't drilled),
    /// matching the reference design.
    public var activeCharacters: [String]

    public init(id: UUID, date: Date, mode: String,
                characterWPM: Int, effectiveWPM: Int,
                attempts: Int, correct: Int,
                fastestTTR: TimeInterval?, medianTTR: TimeInterval?,
                durationSeconds: TimeInterval?,
                characters: [CharResult], activeCharacters: [String]) {
        self.id = id
        self.date = date
        self.mode = mode
        self.characterWPM = characterWPM
        self.effectiveWPM = effectiveWPM
        self.attempts = attempts
        self.correct = correct
        self.fastestTTR = fastestTTR
        self.medianTTR = medianTTR
        self.durationSeconds = durationSeconds
        self.characters = characters
        self.activeCharacters = activeCharacters
    }

    public var accuracy: Double { attempts == 0 ? 0 : Double(correct) / Double(attempts) }

    /// Per-character recognition result within a single session.
    public struct CharResult: Codable, Sendable, Equatable, Identifiable {
        public var character: String        // a single character, e.g. "K"
        public var attempts: Int
        public var correct: Int
        /// Median time-to-recognize over *correct* answers; nil if never correct.
        public var medianTTR: TimeInterval?

        public init(character: String, attempts: Int, correct: Int, medianTTR: TimeInterval?) {
            self.character = character
            self.attempts = attempts
            self.correct = correct
            self.medianTTR = medianTTR
        }

        public var id: String { character }
        public var accuracy: Double { attempts == 0 ? 0 : Double(correct) / Double(attempts) }
        /// Median recognition time in whole milliseconds (nil if never correct).
        public var medianMS: Int? { medianTTR.map { Int(($0 * 1000).rounded()) } }
    }

    /// One row of the recognition chart: a learned character and its result this
    /// session (nil when it wasn't drilled).
    public struct ChartRow: Identifiable, Equatable {
        public let character: String
        public let result: CharResult?
        public var id: String { character }
    }

    /// Chart rows — every character active this session plus any drilled, ordered
    /// letters-then-digits (matching the reference's A–Z then 0–9 layout). Empty
    /// for sessions with no per-character data.
    public var chartRows: [ChartRow] {
        var names = Set(activeCharacters.filter { $0.count == 1 })
        for c in characters { names.insert(c.character) }
        let byChar = Dictionary(characters.map { ($0.character, $0) }, uniquingKeysWith: { a, _ in a })
        return names.sorted(by: Self.characterOrder).map { ChartRow(character: $0, result: byChar[$0]) }
    }

    /// Order single characters letters-first (A–Z), then digits (0–9), so the
    /// chart reads like the reference image.
    public static func characterOrder(_ lhs: String, _ rhs: String) -> Bool {
        let lDigit = lhs.first?.isNumber ?? false
        let rDigit = rhs.first?.isNumber ?? false
        if lDigit != rDigit { return !lDigit }   // letters before digits
        return lhs < rhs
    }

    /// Round a millisecond value up to a tidy axis ceiling: at least 1000ms, and
    /// otherwise the next multiple of 250 so the gridlines stay even.
    public static func axisCeilingMS(_ ms: Int) -> Int {
        let floored = max(1000, ms)
        return ((floored + 249) / 250) * 250
    }
}

/// A bounded, newest-first list of completed sessions, persisted between launches.
public struct SessionHistory: Codable, Sendable, Equatable {
    public private(set) var sessions: [SessionRecord]
    /// Keep history from growing without bound; oldest sessions age out.
    public static let limit = 100

    public init(sessions: [SessionRecord] = []) { self.sessions = sessions }

    /// Add a freshly-completed session to the front, trimming the oldest beyond
    /// the cap.
    public mutating func add(_ record: SessionRecord) {
        sessions.insert(record, at: 0)
        if sessions.count > Self.limit {
            sessions.removeLast(sessions.count - Self.limit)
        }
    }
}
