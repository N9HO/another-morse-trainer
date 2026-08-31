import Foundation

/// One multiple-choice round, in a form the UI can render regardless of which
/// quiz mode produced it (characters, words, abbreviations, prosigns).
public struct Drill: Sendable, Equatable {
    /// What to sound out in Morse.
    public let playable: MorseItem.Playable
    /// The answer choices shown on the buttons (includes the correct one).
    public let options: [String]
    /// The correct choice.
    public let correct: String
    /// Big text shown when revealing (e.g. "X", "ES", "<AR>").
    public let revealPrimary: String
    /// Smaller supporting text on reveal (e.g. the dot-dash pattern or meaning).
    public let revealSecondary: String
    /// Optional question/context shown above the choices (e.g. the QSO
    /// simulator's "What's their name?"). Empty for simple recognition drills.
    public let question: String

    public init(playable: MorseItem.Playable,
                options: [String],
                correct: String,
                revealPrimary: String,
                revealSecondary: String,
                question: String = "") {
        self.playable = playable
        self.options = options
        self.correct = correct
        self.revealPrimary = revealPrimary
        self.revealSecondary = revealSecondary
        self.question = question
    }
}

/// Result of answering a drill.
public struct DrillOutcome: Sendable, Equatable {
    public let correct: Bool
    /// A newly unlocked item (character/word), if answering triggered progression.
    public let unlocked: String?
    public init(correct: Bool, unlocked: String?) {
        self.correct = correct
        self.unlocked = unlocked
    }
}

/// Anything that can drive the quiz loop: hand out drills and record answers.
public protocol QuizSource: AnyObject {
    func nextDrill() -> Drill
    func record(choice: String, ttr: TimeInterval) -> DrillOutcome
    /// Short status for the toolbar (e.g. "12 letters", "70 abbreviations").
    var summary: String { get }
}
