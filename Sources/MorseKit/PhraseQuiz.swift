import Foundation

/// A quiz over a fixed set of items (words, abbreviations, or prosigns). Like
/// the character engine it plays one item, offers the correct answer plus the
/// three closest-sounding distractors, tracks time-to-recognize, and drills
/// harder on the items you miss or are slow on — but it doesn't "graduate"
/// items the way the Koch character ladder does.
public final class PhraseQuiz: QuizSource {

    public struct Config: Sendable, Equatable {
        public var ttrThreshold: TimeInterval
        public var optionCount: Int
        public init(ttrThreshold: TimeInterval = 1.5, optionCount: Int = 4) {
            self.ttrThreshold = ttrThreshold
            self.optionCount = optionCount
        }
    }

    public let name: String
    public let items: [MorseItem]
    public var config: Config

    private var attemptsById: [String: [CharacterStats.Attempt]] = [:]
    /// Items the learner has actually heard at least once. Choices are drawn
    /// only from here, and their number grows with it (just the answer at first)
    /// up to `config.optionCount`.
    private var exposedIds: Set<String> = []
    private var rng: any RandomNumberGenerator
    private var lastItem: MorseItem?

    public init(name: String,
                items: [MorseItem],
                config: Config = Config(),
                rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.name = name
        self.items = items
        self.config = config
        self.rng = rng
    }

    // MARK: QuizSource

    public var summary: String { "\(items.count) \(name.lowercased())" }

    public func nextDrill() -> Drill {
        let target = pickTarget()
        lastItem = target
        exposedIds.insert(target.id)   // hearing it counts as meeting it
        let options = buildOptions(for: target)
        return Drill(
            playable: target.playable,
            options: options,
            correct: target.answer,
            revealPrimary: target.display,
            revealSecondary: target.answer == target.display ? "" : target.answer
        )
    }

    public func record(choice: String, ttr: TimeInterval) -> DrillOutcome {
        guard let item = lastItem else { return DrillOutcome(correct: false, unlocked: nil) }
        let correct = choice == item.answer
        attemptsById[item.id, default: []].append(.init(correct: correct, ttr: ttr))
        if attemptsById[item.id]!.count > CharacterStats.historyLimit {
            attemptsById[item.id]!.removeFirst()
        }
        return DrillOutcome(correct: correct, unlocked: nil)
    }

    // MARK: Selection

    /// Distinct answer labels: correct one plus closest-sounding others. Only
    /// items the learner has already heard are eligible, and the number of
    /// choices grows with that set up to `config.optionCount`.
    private func buildOptions(for target: MorseItem) -> [String] {
        let cap = max(1, config.optionCount)
        let pool = items.filter {
            exposedIds.contains($0.id) && $0.id != target.id && $0.answer != target.answer
        }
        let optionsToShow = min(cap, pool.count + 1)   // +1 for the target itself
        let needed = max(0, optionsToShow - 1)
        let others = pool
            .map { (answer: $0.answer, dist: MorseDistance.distance(target.soundKey, $0.soundKey)) }
            .sorted { $0.dist != $1.dist ? $0.dist < $1.dist : $0.answer < $1.answer }

        var distractors: [String] = []
        for candidate in others where distractors.count < needed {
            if !distractors.contains(candidate.answer) { distractors.append(candidate.answer) }
        }
        var options = [target.answer] + distractors
        options.shuffle(using: &rng)
        return options
    }

    private func pickTarget() -> MorseItem {
        let weights = items.map { weight(for: $0) }
        let total = weights.reduce(0, +)
        guard total > 0 else { return items.randomElement(using: &rng)! }
        var roll = Double.random(in: 0..<total, using: &rng)
        for (item, w) in zip(items, weights) {
            if roll < w { return item }
            roll -= w
        }
        return items.last!
    }

    /// Favor items that are new, missed, or slow.
    func weight(for item: MorseItem) -> Double {
        let recent = Array((attemptsById[item.id] ?? []).suffix(5))
        if recent.isEmpty { return 4 }
        let correct = recent.filter { $0.correct }.count
        let accuracy = Double(correct) / Double(recent.count)
        var w = 1.0 + (1.0 - accuracy) * 4.0
        let correctTimes = recent.filter { $0.correct }.map { $0.ttr }.sorted()
        if correctTimes.isEmpty {
            w += 3.0
        } else {
            let median = correctTimes[correctTimes.count / 2]
            if median > config.ttrThreshold { w += min(median / config.ttrThreshold, 3.0) }
        }
        return w
    }
}
