import Foundation

/// Bridges the character-mode Koch engine to the shared quiz loop.
extension TrainerEngine: QuizSource {

    public func nextDrill() -> Drill {
        let q = nextQuestion()
        lastQuestion = q
        return Drill(
            playable: .text(String(q.target)),
            options: q.options.map(String.init),
            correct: String(q.target),
            revealPrimary: String(q.target),
            revealSecondary: MorseCode.pattern(for: q.target) ?? ""
        )
    }

    public func record(choice: String, ttr: TimeInterval) -> DrillOutcome {
        guard let q = lastQuestion, let answer = choice.first else {
            return DrillOutcome(correct: false, unlocked: nil)
        }
        let outcome = record(answer: answer, for: q, ttr: ttr)
        return DrillOutcome(correct: outcome.correct,
                            unlocked: outcome.addedCharacter.map(String.init))
    }

    public var summary: String { "\(activeCharacters.count) characters" }
}
