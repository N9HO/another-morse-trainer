import Foundation

/// Performance history for a single character: how often it was answered
/// correctly and how long recognition took (time-to-recognize, "TTR").
public struct CharacterStats: Codable, Sendable, Equatable {
    public var character: Character
    /// Most recent attempts, newest last. Bounded so old data ages out.
    public var attempts: [Attempt]

    // `Character` isn't Codable on its own, so encode it as a String.
    enum CodingKeys: String, CodingKey { case character, attempts }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let s = try c.decode(String.self, forKey: .character)
        character = s.first ?? " "
        attempts = try c.decode([Attempt].self, forKey: .attempts)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(String(character), forKey: .character)
        try c.encode(attempts, forKey: .attempts)
    }

    public struct Attempt: Codable, Sendable, Equatable {
        public var correct: Bool
        /// Seconds from the end of the last tone to the user's selection.
        public var ttr: TimeInterval
        public init(correct: Bool, ttr: TimeInterval) {
            self.correct = correct
            self.ttr = ttr
        }
    }

    public static let historyLimit = 20

    public init(character: Character, attempts: [Attempt] = []) {
        self.character = character
        self.attempts = attempts
    }

    public mutating func record(correct: Bool, ttr: TimeInterval) {
        attempts.append(Attempt(correct: correct, ttr: ttr))
        if attempts.count > Self.historyLimit {
            attempts.removeFirst(attempts.count - Self.historyLimit)
        }
    }

    /// Attempts from the most recent window used for mastery decisions.
    public func recent(_ k: Int) -> [Attempt] {
        Array(attempts.suffix(k))
    }

    /// Median TTR over correct answers in the recent window (nil if none).
    public func medianTTR(window k: Int = 5) -> TimeInterval? {
        let times = recent(k).filter { $0.correct }.map { $0.ttr }.sorted()
        guard !times.isEmpty else { return nil }
        let mid = times.count / 2
        return times.count % 2 == 0 ? (times[mid - 1] + times[mid]) / 2 : times[mid]
    }

    public func accuracy(window k: Int = 5) -> Double {
        let recent = recent(k)
        guard !recent.isEmpty else { return 0 }
        return Double(recent.filter { $0.correct }.count) / Double(recent.count)
    }

    /// A character is "mastered" when recent answers are reliably correct and
    /// fast enough — this is the gate for introducing a new character.
    public func isMastered(ttrThreshold: TimeInterval,
                           window k: Int = 5,
                           requiredAccuracy: Double = 0.9) -> Bool {
        guard recent(k).count >= k else { return false }
        guard accuracy(window: k) >= requiredAccuracy else { return false }
        guard let median = medianTTR(window: k) else { return false }
        return median <= ttrThreshold
    }
}
