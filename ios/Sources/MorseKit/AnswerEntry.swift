import Foundation

/// How the choice drills (Characters, Words, Abbreviations, Q-Codes, Prosigns,
/// Confusion Drill) take an answer (#232). Multiple choice narrows the answer
/// space and lets a learner eliminate and guess; typing the answer asks for
/// full recall, the closer match to real copying, so it is offered as the next
/// difficulty tier rather than as a separate mode.
public enum AnswerEntryMode: String, Codable, CaseIterable, Sendable {
    /// Tap one of the offered answers — the long-standing behaviour and the
    /// default. The number of buttons is the Answer choices setting.
    case choices
    /// Climb the per-level ladder: four choices, then six, then type the
    /// answer (`AnswerEntryLadder`). Each new level starts again at four.
    case progressive
    /// Always type the answer: the manual jump ahead.
    case typed
}

/// One rung of the answer-entry ladder.
public enum AnswerEntryTier: String, Codable, CaseIterable, Sendable {
    case fourChoices, sixChoices, typed

    /// The number of answer buttons this tier shows, or nil when the answer is typed.
    public var choiceCount: Int? {
        switch self {
        case .fourChoices: return 4
        case .sixChoices:  return 6
        case .typed:       return nil
        }
    }

    /// The rung above this one; nil at the top.
    public var next: AnswerEntryTier? {
        switch self {
        case .fourChoices: return .sixChoices
        case .sixChoices:  return .typed
        case .typed:       return nil
        }
    }
}

/// Per-level progression of how an answer is given (#232):
/// four choices → six choices → typed.
///
/// A rung is cleared by `requiredCorrect` of the last `window` answers given
/// on it; the window is emptied on each promotion, so every rung is earned on
/// its own answers. A new level — a character added to the Koch ladder, or a
/// new Characters stage — starts again at four choices: multiple choice is
/// the scaffold for something new. There is no demotion; a learner who wants
/// the choices back switches the Answer entry setting. Pinned for both ports
/// by `fixtures/answer-entry.json`.
public struct AnswerEntryLadder: Codable, Equatable, Sendable {
    public static let window = 20
    public static let requiredCorrect = 18

    public private(set) var tier: AnswerEntryTier
    /// Results on the current rung, oldest first, at most `window` long.
    public private(set) var recent: [Bool]

    public init(tier: AnswerEntryTier = .fourChoices, recent: [Bool] = []) {
        self.tier = tier
        self.recent = Array(recent.suffix(Self.window))
    }

    /// Record one answer given on the current rung. Returns the rung this
    /// answer promoted to, or nil when it did not promote.
    @discardableResult
    public mutating func record(correct: Bool) -> AnswerEntryTier? {
        guard let next = tier.next else { return nil }   // typed is the top
        recent.append(correct)
        if recent.count > Self.window { recent.removeFirst(recent.count - Self.window) }
        guard recent.count == Self.window,
              recent.filter({ $0 }).count >= Self.requiredCorrect else { return nil }
        tier = next
        recent.removeAll()
        return next
    }

    /// A new level began: back to the first rung with an empty window.
    public mutating func restartLevel() {
        tier = .fourChoices
        recent.removeAll()
    }

    enum CodingKeys: String, CodingKey { case tier, recent }

    /// Tolerant: a missing or unknown field falls back to the first rung, so a
    /// saved ladder never stops the app loading.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let raw = try c.decodeIfPresent(String.self, forKey: .tier)
        self.init(tier: raw.flatMap(AnswerEntryTier.init(rawValue:)) ?? .fourChoices,
                  recent: (try? c.decodeIfPresent([Bool].self, forKey: .recent)) ?? [])
    }
}

/// Grading of a typed (or keyed) answer (#232).
public enum TypedAnswer {

    /// What was typed, in the form answers are compared in: surrounding
    /// whitespace trimmed, runs of inner whitespace collapsed to one space,
    /// upper case, and a slashed zero (Ø) read as the digit it stands for.
    public static func normalize(_ text: String) -> String {
        text.uppercased()
            .replacingOccurrences(of: "Ø", with: "0")
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
    }

    /// How an answer to a single-character drill is scored.
    public struct CharacterGrade: Equatable, Sendable {
        public let correct: Bool
        /// The character the learner gave in place of the target — the
        /// confusion to record — or nil. Nil for a correct answer, and for one
        /// that is not a single Morse character: a blank ("don't know"), two
        /// characters, or a symbol Morse has no code for. Those are misses
        /// with no partner, so a typing slip never lands in the Confusion
        /// Matrix as a sound-alike.
        public let confusedWith: Character?
        public init(correct: Bool, confusedWith: Character?) {
            self.correct = correct
            self.confusedWith = confusedWith
        }
    }

    public static func gradeCharacter(_ answer: String, target: Character) -> CharacterGrade {
        let given = normalize(answer)
        if given == String(target) { return CharacterGrade(correct: true, confusedWith: nil) }
        guard given.count == 1, let c = given.first, MorseCode.pattern(for: c) != nil else {
            return CharacterGrade(correct: false, confusedWith: nil)
        }
        return CharacterGrade(correct: false, confusedWith: c)
    }
}
