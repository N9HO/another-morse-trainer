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
        guard let q = lastQuestion else {
            return DrillOutcome(correct: false, unlocked: nil)
        }
        // A typed or keyed answer can be anything (#232): only a single Morse
        // character is a confusion partner. A blank, two characters or a
        // symbol with no code is a miss with no partner, and a miss never
        // advances the ladder.
        let grade = TypedAnswer.gradeCharacter(choice, target: q.target)
        guard grade.correct || grade.confusedWith != nil else {
            noteMiss(target: q.target)
            return DrillOutcome(correct: false, unlocked: nil)
        }
        let outcome = record(answer: grade.confusedWith ?? q.target, for: q, ttr: ttr)
        return DrillOutcome(correct: outcome.correct,
                            unlocked: outcome.addedCharacter.map(String.init))
    }

    public var summary: String {
        let n = activeCharacters.count
        return "\(n) char\(n == 1 ? "" : "s")"
    }
}
