import Foundation

/// A targeted review mode that drills the character pairs you actually confuse.
///
/// It reads the confusion data the Koch `TrainerEngine` has been collecting
/// during normal practice (which character you picked when you got one wrong),
/// then builds drills that deliberately place a confusable look-alike on the
/// buttons next to the correct answer — weighted toward the mix-ups that happen
/// most. Getting one right eases that pairing, so resolved confusions naturally
/// drop out of rotation.
///
/// Before any errors have been recorded it falls back to your slowest active
/// character paired with its nearest-sounding neighbor, so the mode is always
/// useful.
public final class ConfusionQuiz: QuizSource {

    public let engine: TrainerEngine
    private var rng: any RandomNumberGenerator
    private var lastTarget: Character?
    private var lastConfuser: Character?

    public init(engine: TrainerEngine,
                rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.engine = engine
        self.rng = rng
    }

    // MARK: - QuizSource

    public var summary: String {
        let n = engine.confusions.pairs().count
        return n == 0 ? "no mix-ups yet" : "\(n) mix-up\(n == 1 ? "" : "s")"
    }

    public func nextDrill() -> Drill {
        let (target, confuser) = pickPair()
        lastTarget = target
        lastConfuser = confuser

        var options: [String] = [String(target)]
        if let confuser { options.append(String(confuser)) }

        // Fill the remaining slots with the nearest-sounding neighbors so the
        // whole lineup stays plausibly confusable.
        let pool = engine.activeCharacters.filter { $0 != target && $0 != confuser }
        let extras = MorseDistance.nearestNeighbors(to: target, in: pool, count: max(0, 4 - options.count))
        options.append(contentsOf: extras.map(String.init))

        // Top up from the whole alphabet if the active set was too small.
        if options.count < 4 {
            let more = MorseDistance.nearestNeighbors(
                to: target,
                in: MorseCode.alphabet.filter { c in c != target && !options.contains(String(c)) },
                count: 4 - options.count)
            options.append(contentsOf: more.map(String.init))
        }
        options.shuffle(using: &rng)

        return Drill(
            playable: .text(String(target)),
            options: options,
            correct: String(target),
            revealPrimary: String(target),
            revealSecondary: MorseCode.pattern(for: target) ?? "")
    }

    public func record(choice: String, ttr: TimeInterval) -> DrillOutcome {
        guard let target = lastTarget, let answer = choice.first else {
            return DrillOutcome(correct: false, unlocked: nil)
        }
        // Record the attempt without graduating new Koch characters — this is
        // review, not progression. A correct call eases the drilled pairing.
        let correct = engine.noteAttempt(answer: answer, target: target, ttr: ttr)
        if correct, let confuser = lastConfuser {
            engine.easeConfusion(target: target, chosen: confuser)
        }
        return DrillOutcome(correct: correct, unlocked: nil)
    }

    // MARK: - Selection

    /// Choose a `(target, confuser)` to drill. Prefer real confusions, weighted
    /// by how often they happen; if there are none yet, fall back to the slowest
    /// active character paired with its nearest-sounding neighbor.
    private func pickPair() -> (Character, Character?) {
        let entries = engine.confusions.entries()
        if !entries.isEmpty {
            let total = entries.reduce(0) { $0 + $1.count }
            var roll = Int.random(in: 0..<total, using: &rng)
            for e in entries {
                if roll < e.count { return (e.target, e.chosen) }
                roll -= e.count
            }
            return (entries[0].target, entries[0].chosen)
        }
        let target = slowestActiveCharacter() ?? engine.activeCharacters.first ?? "E"
        let neighbor = MorseDistance.nearestNeighbors(to: target, in: engine.activeCharacters, count: 1).first
        return (target, neighbor)
    }

    /// The active character with the highest median TTR (unpracticed characters,
    /// whose TTR is unknown, sort as "slow" so they get attention too).
    private func slowestActiveCharacter() -> Character? {
        engine.activeCharacters.max { a, b in
            let ta = engine.stats[a]?.medianTTR(window: CharacterStats.historyLimit) ?? .infinity
            let tb = engine.stats[b]?.medianTTR(window: CharacterStats.historyLimit) ?? .infinity
            return ta < tb
        }
    }
}
