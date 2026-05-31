import Foundation

/// The decision-making core of the trainer. It chooses which character to
/// play next, builds the multiple-choice question (correct answer plus the
/// closest-sounding distractors), records each result with its TTR, and grows
/// the active character set as the learner gets fast and accurate.
///
/// It holds no audio or UI — that lives in the app — so all of this logic can
/// be unit-tested on its own.
public final class TrainerEngine {

    public struct Config: Sendable, Equatable {
        public var wpm: Double
        public var ttrThreshold: TimeInterval
        public var optionCount: Int
        /// If true, distractors may be any character; if false, only ones
        /// already in the active set are offered.
        public var distractorsFromFullAlphabet: Bool
        public var masteryWindow: Int
        public var requiredAccuracy: Double

        public init(
            wpm: Double = 33,
            ttrThreshold: TimeInterval = 1.0,
            optionCount: Int = 4,
            distractorsFromFullAlphabet: Bool = true,
            masteryWindow: Int = 5,
            requiredAccuracy: Double = 0.9
        ) {
            self.wpm = wpm
            self.ttrThreshold = ttrThreshold
            self.optionCount = optionCount
            self.distractorsFromFullAlphabet = distractorsFromFullAlphabet
            self.masteryWindow = masteryWindow
            self.requiredAccuracy = requiredAccuracy
        }
    }

    /// A single multiple-choice round.
    public struct Question: Sendable, Equatable {
        public let target: Character
        public let options: [Character]   // includes `target`, presentation order
        public init(target: Character, options: [Character]) {
            self.target = target
            self.options = options
        }
    }

    public private(set) var activeCharacters: [Character]
    public private(set) var stats: [Character: CharacterStats]
    /// Which character the learner picked when they got one wrong — the raw
    /// material for the confusion-pair drills.
    public private(set) var confusions = ConfusionMatrix()
    public var config: Config

    private var rng: any RandomNumberGenerator

    /// Remembers the most recently handed-out question so the QuizSource
    /// `record(choice:ttr:)` bridge can score it.
    var lastQuestion: Question?

    public var timing: MorseTiming { MorseTiming(wpm: config.wpm) }

    /// - Parameters:
    ///   - seedCount: how many characters from the Koch order to start with.
    ///   - rng: injectable randomness so tests are deterministic.
    public init(
        config: Config = Config(),
        seedCount: Int = 2,
        rng: any RandomNumberGenerator = SystemRandomNumberGenerator()
    ) {
        self.config = config
        self.rng = rng
        let seed = Array(MorseCode.kochOrder.prefix(max(1, seedCount)))
        self.activeCharacters = seed
        self.stats = Dictionary(uniqueKeysWithValues: seed.map { ($0, CharacterStats(character: $0)) })
    }

    // MARK: - Question generation

    /// Build the next question: a target weighted toward characters that are
    /// missed or slow, plus its closest-sounding distractors.
    public func nextQuestion() -> Question {
        let target = pickTarget()
        let distractors = pickDistractors(for: target)
        var options = ([target] + distractors)
        options.shuffle(using: &rng)
        return Question(target: target, options: options)
    }

    /// Higher weight = more likely to be drilled. New, missed, and slow
    /// characters are favored so practice goes where it's needed.
    public func weight(for character: Character) -> Double {
        guard let s = stats[character] else { return 1 }
        let recent = s.recent(config.masteryWindow)
        if recent.isEmpty { return 4 }   // unpracticed → drill it
        var w = 1.0
        w += (1.0 - s.accuracy(window: config.masteryWindow)) * 4.0   // misses hurt
        if let median = s.medianTTR(window: config.masteryWindow) {
            if median > config.ttrThreshold { w += min(median / config.ttrThreshold, 3.0) }
        } else {
            w += 3.0   // no correct answers recently → needs work
        }
        return w
    }

    private func pickTarget() -> Character {
        let weights = activeCharacters.map { weight(for: $0) }
        let total = weights.reduce(0, +)
        guard total > 0 else { return activeCharacters.randomElement(using: &rng)! }
        var roll = Double.random(in: 0..<total, using: &rng)
        for (char, w) in zip(activeCharacters, weights) {
            if roll < w { return char }
            roll -= w
        }
        return activeCharacters.last!
    }

    private func pickDistractors(for target: Character) -> [Character] {
        let needed = max(0, config.optionCount - 1)
        let primaryPool = config.distractorsFromFullAlphabet ? MorseCode.alphabet : activeCharacters
        var picks = MorseDistance.nearestNeighbors(to: target, in: primaryPool, count: needed)
        // If the active-set pool was too small, top up from the full alphabet.
        if picks.count < needed {
            let extra = MorseDistance.nearestNeighbors(
                to: target,
                in: MorseCode.alphabet.filter { !picks.contains($0) && $0 != target },
                count: needed - picks.count
            )
            picks.append(contentsOf: extra)
        }
        return picks
    }

    // MARK: - Recording answers & progression

    @discardableResult
    public func record(answer: Character, for question: Question, ttr: TimeInterval) -> Outcome {
        let correct = noteAttempt(answer: answer, target: question.target, ttr: ttr)
        let added = advanceIfReady()
        return Outcome(correct: correct, addedCharacter: added)
    }

    /// Record one attempt's outcome — updating the character's stats and, on a
    /// miss, the confusion matrix — *without* advancing the Koch ladder. Returns
    /// whether the answer was correct. Used by `record(answer:for:)` and by
    /// review drills (e.g. the confusion-pair quiz) that shouldn't graduate new
    /// characters.
    @discardableResult
    public func noteAttempt(answer: Character, target: Character, ttr: TimeInterval) -> Bool {
        let correct = answer == target
        stats[target, default: CharacterStats(character: target)].record(correct: correct, ttr: ttr)
        if !correct { confusions.record(target: target, chosen: answer) }
        return correct
    }

    /// Ease a confused pairing after a correct recognition (used by the
    /// confusion-pair drill so sorted-out pairs fade from rotation).
    public func easeConfusion(target: Character, chosen: Character) {
        confusions.ease(target: target, chosen: chosen)
    }

    public struct Outcome: Sendable, Equatable {
        public let correct: Bool
        /// A newly introduced character, if this answer triggered progression.
        public let addedCharacter: Character?
    }

    /// Once every active character is mastered, introduce the next Koch
    /// character (one at a time). Returns the character added, if any.
    @discardableResult
    public func advanceIfReady() -> Character? {
        guard allActiveMastered else { return nil }
        guard let next = MorseCode.kochOrder.first(where: { !activeCharacters.contains($0) })
        else { return nil }   // whole alphabet learned 🎉
        activeCharacters.append(next)
        stats[next] = CharacterStats(character: next)
        return next
    }

    /// Replace the active character set (e.g. when the learner picks a
    /// proficiency level). Stats for new characters are created; existing
    /// stats are preserved so prior practice isn't lost.
    public func setActiveCharacters(_ characters: [Character]) {
        activeCharacters = characters
        for c in characters where stats[c] == nil {
            stats[c] = CharacterStats(character: c)
        }
    }

    /// Add one character to the active set (e.g. opting into a punctuation mark).
    public func addActiveCharacter(_ character: Character) {
        guard !activeCharacters.contains(character) else { return }
        activeCharacters.append(character)
        if stats[character] == nil { stats[character] = CharacterStats(character: character) }
    }

    /// Remove one character from the active set (e.g. opting back out).
    public func removeActiveCharacter(_ character: Character) {
        activeCharacters.removeAll { $0 == character }
    }

    public var allActiveMastered: Bool {
        activeCharacters.allSatisfy {
            stats[$0]?.isMastered(
                ttrThreshold: config.ttrThreshold,
                window: config.masteryWindow,
                requiredAccuracy: config.requiredAccuracy
            ) ?? false
        }
    }

    // MARK: - Persistence

    /// A Codable snapshot of progress for saving to disk.
    public struct Snapshot: Codable, Sendable {
        public var activeCharacters: [Character]
        public var stats: [CharacterStats]
        public var confusions: ConfusionMatrix

        enum CodingKeys: String, CodingKey { case activeCharacters, stats, confusions }

        public init(activeCharacters: [Character],
                    stats: [CharacterStats],
                    confusions: ConfusionMatrix = ConfusionMatrix()) {
            self.activeCharacters = activeCharacters
            self.stats = stats
            self.confusions = confusions
        }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            activeCharacters = try c.decode([String].self, forKey: .activeCharacters)
                .compactMap { $0.first }
            stats = try c.decode([CharacterStats].self, forKey: .stats)
            // Older snapshots predate confusion tracking.
            confusions = try c.decodeIfPresent(ConfusionMatrix.self, forKey: .confusions) ?? ConfusionMatrix()
        }

        public func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try c.encode(activeCharacters.map(String.init), forKey: .activeCharacters)
            try c.encode(stats, forKey: .stats)
            try c.encode(confusions, forKey: .confusions)
        }
    }

    public var snapshot: Snapshot {
        Snapshot(activeCharacters: activeCharacters, stats: Array(stats.values), confusions: confusions)
    }

    public func restore(from snapshot: Snapshot) {
        activeCharacters = snapshot.activeCharacters
        stats = Dictionary(uniqueKeysWithValues: snapshot.stats.map { ($0.character, $0) })
        confusions = snapshot.confusions
    }
}
