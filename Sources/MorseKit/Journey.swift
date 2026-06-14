import Foundation

/// The "Journey" track: a gamified, level-based path through Morse — inspired by
/// the level ladders in apps like Morse Mania, but built on the same `Drill` /
/// `QuizSource` plumbing as every other AMT mode.
///
/// Where the `Characters` ladder is *adaptive* (it auto-advances when your recent
/// accuracy and speed are good enough), the Journey is *explicit and gamey*:
///
///   • A fixed sequence of numbered levels, each introducing two new symbols and
///     mixing in everything learned before.
///   • A per-level progress bar that fills on a correct answer and *drains* on a
///     miss — you have to fight the bar up to a threshold to clear the level.
///   • Clearing a level unlocks the next one; you climb a single visible map that
///     runs letters → numbers → punctuation → prosigns → Q-codes →
///     abbreviations → words → call signs.
///
/// The whole curriculum is generated from the data already in `MorseData` /
/// `MorseCode`, so there's nothing to hand-author and it stays in sync as the
/// underlying word/abbreviation lists grow.

// MARK: - Level

/// One step on the journey: a small set of newly introduced items plus the full
/// cumulative pool of everything learned up to and including this level.
public struct JourneyLevel: Identifiable, Sendable, Equatable {
    /// 1-based position on the map.
    public let number: Int
    /// The section this level belongs to ("Letters", "Q-Codes", …).
    public let section: String
    /// A short title, usually the new items ("K M", "Words I").
    public let title: String
    /// The items introduced for the first time at this level (drilled hardest).
    public let newItems: [MorseItem]
    /// Everything learnable at this level: this level's new items plus all
    /// items from earlier levels (the answer pool and distractor source).
    public let pool: [MorseItem]

    public var id: Int { number }

    public init(number: Int, section: String, title: String,
                newItems: [MorseItem], pool: [MorseItem]) {
        self.number = number
        self.section = section
        self.title = title
        self.newItems = newItems
        self.pool = pool
    }
}

// MARK: - Curriculum

/// Builds the ordered list of journey levels from existing MorseKit data.
public enum JourneyCurriculum {

    /// A section of the curriculum: an ordered list of items and how many new
    /// ones to introduce per level.
    private struct Section {
        let title: String
        let items: [MorseItem]
        let perLevel: Int
    }

    /// Single-character items (letters, numbers, punctuation) built straight from
    /// the code table, in the given character order.
    private static func charItems(_ chars: [Character]) -> [MorseItem] {
        chars.compactMap { ch in
            guard MorseCode.pattern(for: ch) != nil else { return nil }
            let s = String(ch)
            return MorseItem(id: "char-\(s)", playable: .text(s), answer: s, display: s)
        }
    }

    /// The full ordered curriculum, computed once.
    public static let levels: [JourneyLevel] = build()

    private static func build() -> [JourneyLevel] {
        // Letters & numbers follow the Koch teaching order; split out punctuation
        // (currently just "?") into its own short section so a level isn't half
        // letter / half symbol.
        let koch = MorseCode.kochOrder
        let letterDigits = koch.filter { $0.isLetter || $0.isNumber }
        let punctuation = koch.filter { !($0.isLetter || $0.isNumber) }

        let sections: [Section] = [
            Section(title: "Letters & Numbers", items: charItems(letterDigits), perLevel: 2),
            Section(title: "Punctuation",       items: charItems(punctuation),  perLevel: 1),
            Section(title: "Prosigns",          items: MorseData.prosignItems,  perLevel: 2),
            Section(title: "Q-Codes",           items: MorseData.qCodeItems,    perLevel: 2),
            Section(title: "Abbreviations",     items: MorseData.abbreviationItems, perLevel: 2),
            Section(title: "Words",             items: MorseData.topWordItems(60), perLevel: 4),
            Section(title: "Call Signs",        items: MorseData.callSignItems, perLevel: 3),
        ]

        var levels: [JourneyLevel] = []
        var pool: [MorseItem] = []   // accumulates across every section
        var number = 0

        for section in sections where !section.items.isEmpty {
            var index = 0
            while index < section.items.count {
                let end = min(index + section.perLevel, section.items.count)
                let newItems = Array(section.items[index..<end])
                pool.append(contentsOf: newItems)
                number += 1
                levels.append(JourneyLevel(
                    number: number,
                    section: section.title,
                    title: levelTitle(section: section.title, newItems: newItems),
                    newItems: newItems,
                    pool: pool
                ))
                index = end
            }
        }
        return levels
    }

    private static func levelTitle(section: String, newItems: [MorseItem]) -> String {
        // For short tokens (characters, prosigns) show them directly; for long
        // ones (Q-code meanings, words) the display label reads better.
        let labels = newItems.map { $0.display }
        let joined = labels.joined(separator: " ")
        return joined.count <= 14 ? joined : "\(section): \(labels.first ?? "")…"
    }
}

// MARK: - Scoring

/// Tunable rules for the per-level progress bar. Defaults give Morse-Mania-style
/// tension: a handful of correct answers clears a level, but each miss drains
/// real ground so you can't coast.
public struct JourneyScoring: Sendable, Equatable {
    /// Points needed to clear a level.
    public var target: Int
    /// Points added for a correct answer on a review (already-seen) item.
    public var fill: Int
    /// Bonus added when the correct item was newly introduced this level.
    public var newItemBonus: Int
    /// Points removed on a wrong answer. Set to 0 for a fill-only bar.
    public var drain: Int

    public init(target: Int = 100, fill: Int = 12, newItemBonus: Int = 4, drain: Int = 9) {
        self.target = target
        self.fill = fill
        self.newItemBonus = newItemBonus
        self.drain = drain
    }

    public static let `default` = JourneyScoring()
    /// Gentler variant with no penalty for misses.
    public static let fillOnly = JourneyScoring(drain: 0)
}

// MARK: - Quiz

/// Drives a single journey level: hands out drills weighted toward the newest
/// symbols, scores the progress bar, and advances to the next level once the bar
/// is cleared. Conforms to `QuizSource` so the existing play→answer→reveal loop
/// drives it unchanged; the extra level/bar state is read by the app layer to
/// render the map and the progress bar.
public final class JourneyQuiz: QuizSource {

    public let levels: [JourneyLevel]
    public var scoring: JourneyScoring
    public var config: PhraseQuiz.Config

    /// Index into `levels` of the level currently being played.
    public private(set) var levelIndex: Int
    /// Current points on the bar for this level (0...scoring.target).
    public private(set) var points: Int = 0

    private var rng: any RandomNumberGenerator
    private var attemptsById: [String: [CharacterStats.Attempt]] = [:]
    private var lastItem: MorseItem?

    public init(levels: [JourneyLevel] = JourneyCurriculum.levels,
                startIndex: Int = 0,
                scoring: JourneyScoring = .default,
                config: PhraseQuiz.Config = .init(),
                rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.levels = levels
        self.levelIndex = min(max(0, startIndex), max(0, levels.count - 1))
        self.scoring = scoring
        self.config = config
        self.rng = rng
    }

    // MARK: Derived state (read by the app/UI)

    public var level: JourneyLevel { levels[levelIndex] }
    public var levelNumber: Int { level.number }
    public var isLastLevel: Bool { levelIndex >= levels.count - 1 }
    /// Bar fill in 0...1.
    public var progress: Double {
        guard scoring.target > 0 else { return 0 }
        return min(1, Double(points) / Double(scoring.target))
    }

    /// Jump to a specific level (e.g. the player picked one off the map). Resets
    /// the bar; clamps to a valid index.
    public func select(levelIndex index: Int) {
        levelIndex = min(max(0, index), max(0, levels.count - 1))
        points = 0
        lastItem = nil
    }

    // MARK: QuizSource

    public var summary: String {
        "Level \(levelNumber) of \(levels.count) — \(level.section)"
    }

    public func nextDrill() -> Drill {
        let target = pickTarget()
        lastItem = target
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

        if correct {
            let isNew = level.newItems.contains { $0.id == item.id }
            points += scoring.fill + (isNew ? scoring.newItemBonus : 0)
        } else {
            points = max(0, points - scoring.drain)
        }

        // Level cleared: surface the unlock and advance to the next level.
        if points >= scoring.target {
            let clearedNumber = levelNumber
            let wasLast = isLastLevel
            if !wasLast { levelIndex += 1 }
            points = 0
            lastItem = nil
            let label = wasLast ? "Journey complete!" : "Level \(clearedNumber) complete!"
            return DrillOutcome(correct: correct, unlocked: label)
        }
        return DrillOutcome(correct: correct, unlocked: nil)
    }

    // MARK: Selection

    /// Distinct answer labels: the correct one plus the closest-sounding others,
    /// drawn only from the current level's learned pool.
    private func buildOptions(for target: MorseItem) -> [String] {
        let cap = max(1, config.optionCount)
        let others = level.pool.filter { $0.id != target.id && $0.answer != target.answer }
        let optionsToShow = min(cap, others.count + 1)
        let needed = max(0, optionsToShow - 1)
        let sorted = others
            .map { (answer: $0.answer, dist: MorseDistance.distance(target.soundKey, $0.soundKey)) }
            .sorted { $0.dist != $1.dist ? $0.dist < $1.dist : $0.answer < $1.answer }

        var distractors: [String] = []
        for candidate in sorted where distractors.count < needed {
            if !distractors.contains(candidate.answer) { distractors.append(candidate.answer) }
        }
        var options = [target.answer] + distractors
        options.shuffle(using: &rng)
        return options
    }

    private func pickTarget() -> MorseItem {
        let pool = level.pool
        let weights = pool.map { weight(for: $0) }
        let total = weights.reduce(0, +)
        guard total > 0 else { return pool.randomElement(using: &rng)! }
        var roll = Double.random(in: 0..<total, using: &rng)
        for (item, w) in zip(pool, weights) {
            if roll < w { return item }
            roll -= w
        }
        return pool.last!
    }

    /// Favor the newest items heavily (so each level teaches its new symbols),
    /// then items that are missed or slow — the old/new mix the journey is built on.
    func weight(for item: MorseItem) -> Double {
        var w = level.newItems.contains { $0.id == item.id } ? 4.0 : 1.0
        let recent = Array((attemptsById[item.id] ?? []).suffix(5))
        guard !recent.isEmpty else { return w + 2.0 }   // never-seen-this-session bump
        let accuracy = Double(recent.filter { $0.correct }.count) / Double(recent.count)
        w += (1.0 - accuracy) * 4.0
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

// MARK: - Progress (persistence)

/// Persisted journey progress: how far the player has unlocked and where they
/// currently are. Stored as JSON in UserDefaults by the app layer, alongside the
/// other mode snapshots.
public struct JourneyProgress: Codable, Sendable, Equatable {
    /// Highest level number the player has unlocked (1-based). Level 1 is always
    /// unlocked.
    public var unlockedThrough: Int
    /// The level the player last had selected (1-based).
    public var currentLevel: Int
    /// Level numbers the player has cleared at least once.
    public var completed: Set<Int>

    public init(unlockedThrough: Int = 1, currentLevel: Int = 1, completed: Set<Int> = []) {
        self.unlockedThrough = unlockedThrough
        self.currentLevel = currentLevel
        self.completed = completed
    }

    /// Record clearing the given level number: mark it complete and unlock the
    /// next one.
    public mutating func clear(level number: Int, totalLevels: Int) {
        completed.insert(number)
        unlockedThrough = min(totalLevels, max(unlockedThrough, number + 1))
    }

    public func isUnlocked(level number: Int) -> Bool { number <= unlockedThrough }
}
