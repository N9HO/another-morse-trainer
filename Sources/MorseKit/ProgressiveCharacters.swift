import Foundation

/// The full "Characters" track. It starts with the single-character Koch ladder
/// (delegated to `TrainerEngine`) and, once every character is mastered, keeps
/// progressing on its own:
///
///   singles → pairs → triples → words & call signs
///
/// In the multi-character stages it plays groups of learned characters and
/// offers sound-alike groups as distractors, and it mixes in prosigns
/// (`<AR>`, `<SK>`, …) to recognize by ear. Each stage advances once recent
/// accuracy and time-to-recognize are good enough.
public final class ProgressiveCharacters: QuizSource {

    public enum Stage: String, Codable, Sendable, CaseIterable {
        case singles, pairs, triples, phrases
        public var displayName: String {
            switch self {
            case .singles: return "Characters"
            case .pairs:   return "Pairs"
            case .triples: return "Triples"
            case .phrases: return "Words & Call Signs"
            }
        }
        var groupSize: Int { self == .pairs ? 2 : (self == .triples ? 3 : 0) }
    }

    public let engine: TrainerEngine
    public private(set) var stage: Stage = .singles

    private var rng: any RandomNumberGenerator
    private let prosignItems = MorseData.prosignTokenItems
    private let phraseItems = MorseData.wordAndCallSignItems

    /// Rolling results within the current advanced stage (for advancement).
    private var stageResults: [(correct: Bool, ttr: TimeInterval)] = []
    private let stageWindow = 12

    /// What kind of drill we last handed out, so `record` scores it correctly.
    private enum LastKind { case singles, group(String), item(String) }
    private var lastKind: LastKind = .singles

    public init(engine: TrainerEngine,
                rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.engine = engine
        self.rng = rng
    }

    private var ttrThreshold: TimeInterval { engine.config.ttrThreshold }

    // MARK: - QuizSource

    public var summary: String {
        stage == .singles ? engine.summary : stage.displayName
    }

    public func nextDrill() -> Drill {
        // Leave the singles stage automatically once the ladder is complete.
        if stage == .singles, singlesComplete {
            advance(to: .pairs)
        }

        switch stage {
        case .singles:
            lastKind = .singles
            return engine.nextDrill()

        case .pairs, .triples:
            // Occasionally drill a prosign by ear instead of a character group.
            if shouldDoProsign() { return prosignDrill() }
            return groupDrill(size: stage.groupSize)

        case .phrases:
            if shouldDoProsign() { return prosignDrill() }
            return phraseDrill()
        }
    }

    public func record(choice: String, ttr: TimeInterval) -> DrillOutcome {
        switch lastKind {
        case .singles:
            let outcome = engine.record(choice: choice, ttr: ttr)
            // The very answer that completes the ladder unlocks the pairs stage.
            if stage == .singles, singlesComplete {
                advance(to: .pairs)
                return DrillOutcome(correct: outcome.correct, unlocked: Stage.pairs.displayName)
            }
            return outcome

        case .group(let answer), .item(let answer):
            let correct = choice == answer
            stageResults.append((correct, ttr))
            if stageResults.count > stageWindow { stageResults.removeFirst() }
            if let next = advanceIfStageMastered() {
                return DrillOutcome(correct: correct, unlocked: next.displayName)
            }
            return DrillOutcome(correct: correct, unlocked: nil)
        }
    }

    // MARK: - Stage advancement

    private var singlesComplete: Bool {
        engine.allActiveMastered &&
        MorseCode.kochOrder.allSatisfy { engine.activeCharacters.contains($0) }
    }

    /// Time allowed to recognize, in seconds, scaled to the current stage.
    private var stageAllowedTTR: TimeInterval {
        switch stage {
        case .singles: return ttrThreshold
        case .pairs:   return ttrThreshold * 2.5
        case .triples: return ttrThreshold * 3.5
        case .phrases: return ttrThreshold * 4.0
        }
    }

    private func advanceIfStageMastered() -> Stage? {
        guard stageResults.count >= stageWindow else { return nil }
        let accuracy = Double(stageResults.filter { $0.correct }.count) / Double(stageResults.count)
        guard accuracy >= 0.9 else { return nil }
        let times = stageResults.filter { $0.correct }.map { $0.ttr }.sorted()
        guard !times.isEmpty else { return nil }
        let median = times[times.count / 2]
        guard median <= stageAllowedTTR else { return nil }

        switch stage {
        case .pairs:   advance(to: .triples); return .triples
        case .triples: advance(to: .phrases); return .phrases
        case .singles, .phrases: return nil   // phrases is the final stage
        }
    }

    private func advance(to newStage: Stage) {
        stage = newStage
        stageResults.removeAll()
    }

    /// Restart the ladder at the single-character stage (e.g. after the user
    /// changes their proficiency level).
    public func resetToSingles() {
        stage = .singles
        stageResults.removeAll()
    }

    /// Developer/testing aid: jump directly to a stage. For the multi-character
    /// stages it makes sure there are enough learned characters to form varied
    /// groups (expanding to the full letter+number set if needed).
    public func jumpToStage(_ newStage: Stage) {
        if newStage != .singles && engine.activeCharacters.count < 10 {
            engine.setActiveCharacters(MorseCode.kochOrder)
        }
        stage = newStage
        stageResults.removeAll()
    }

    // MARK: - Drill builders

    private func shouldDoProsign() -> Bool {
        Double.random(in: 0..<1, using: &rng) < 0.25
    }

    private func groupDrill(size: Int) -> Drill {
        let pool = engine.activeCharacters
        let group = String((0..<size).map { _ in pool.randomElement(using: &rng)! })
        var options = [group]
        var attempts = 0
        while options.count < 4 && attempts < 40 {
            attempts += 1
            let candidate = mutate(group, pool: pool)
            if candidate != group && !options.contains(candidate) { options.append(candidate) }
        }
        // Fallback: pad with fresh random groups if mutation didn't find enough.
        while options.count < 4 {
            let g = String((0..<size).map { _ in pool.randomElement(using: &rng)! })
            if !options.contains(g) { options.append(g) }
        }
        options.shuffle(using: &rng)
        lastKind = .group(group)
        return Drill(playable: .text(group), options: options, correct: group,
                     revealPrimary: group, revealSecondary: "")
    }

    /// Change one character of `group` to a sound-alike, yielding a confusable group.
    private func mutate(_ group: String, pool: [Character]) -> String {
        var chars = Array(group)
        let pos = Int.random(in: 0..<chars.count, using: &rng)
        let neighbors = MorseDistance.nearestNeighbors(to: chars[pos], in: pool, count: 3)
        if let replacement = neighbors.randomElement(using: &rng) {
            chars[pos] = replacement
        }
        return String(chars)
    }

    private func prosignDrill() -> Drill {
        let target = prosignItems.randomElement(using: &rng)!
        var options = [target.answer]
        let others = prosignItems
            .filter { $0.id != target.id }
            .sorted { MorseDistance.distance(target.soundKey, $0.soundKey)
                    < MorseDistance.distance(target.soundKey, $1.soundKey) }
        for item in others where options.count < 4 {
            if !options.contains(item.answer) { options.append(item.answer) }
        }
        options.shuffle(using: &rng)
        lastKind = .item(target.answer)
        return Drill(playable: target.playable, options: options, correct: target.answer,
                     revealPrimary: target.display,
                     revealSecondary: MorseData.prosigns.first { $0.name == target.id }?.meaning ?? "")
    }

    private func phraseDrill() -> Drill {
        let target = phraseItems.randomElement(using: &rng)!
        var options = [target.answer]
        let others = phraseItems
            .filter { $0.id != target.id }
            .map { (answer: $0.answer, dist: MorseDistance.distance(target.soundKey, $0.soundKey)) }
            .sorted { $0.dist < $1.dist }
        for candidate in others where options.count < 4 {
            if !options.contains(candidate.answer) { options.append(candidate.answer) }
        }
        options.shuffle(using: &rng)
        lastKind = .item(target.answer)
        return Drill(playable: target.playable, options: options, correct: target.answer,
                     revealPrimary: target.display, revealSecondary: "")
    }

    // MARK: - Persistence

    public struct Snapshot: Codable, Sendable {
        public var engine: TrainerEngine.Snapshot
        public var stage: Stage
    }

    public var snapshot: Snapshot { Snapshot(engine: engine.snapshot, stage: stage) }

    public func restore(from snapshot: Snapshot) {
        engine.restore(from: snapshot.engine)
        stage = snapshot.stage
        stageResults.removeAll()
    }
}
