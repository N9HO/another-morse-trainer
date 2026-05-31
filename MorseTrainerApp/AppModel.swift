import Foundation
import SwiftUI

/// The four ways to practice.
enum TrainingMode: String, CaseIterable, Identifiable {
    case characters, words, abbreviations, prosigns, headCopy, typed, confusion
    var id: String { rawValue }
    var title: String {
        switch self {
        case .characters:   return "Characters"
        case .words:        return "Words"
        case .abbreviations: return "Abbreviations"
        case .prosigns:     return "Prosigns"
        case .headCopy:     return "Head Copy"
        case .typed:        return "Type It"
        case .confusion:    return "Confusion Drill"
        }
    }
    var icon: String {
        switch self {
        case .characters:    return "character"
        case .words:         return "textformat"
        case .abbreviations: return "text.bubble"
        case .prosigns:      return "antenna.radiowaves.left.and.right"
        case .headCopy:      return "brain.head.profile"
        case .typed:         return "keyboard"
        case .confusion:     return "arrow.left.arrow.right"
        }
    }
    /// In meaning-based modes the question is "what are they saying?"
    var prompt: String {
        switch self {
        case .characters, .words, .confusion: return "What did you hear?"
        case .abbreviations:      return "What are they saying?"
        case .prosigns:           return "Which prosign?"
        case .headCopy:           return "Copy it in your head…"
        case .typed:              return "Type what you hear"
        }
    }
}

/// The app's single source of truth. Connects the tested MorseKit quiz engines
/// (character Koch ladder + phrase quizzes) to the audio player and SwiftUI,
/// driving the play → answer → feedback loop with a time-to-recognize clock.
@MainActor
final class AppModel: ObservableObject {

    enum Phase { case idle, playing, awaiting, revealed, answered }

    @Published var settings: AppSettings {
        didSet { settings.save(); applySettings() }
    }
    @Published private(set) var mode: TrainingMode = .characters
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var drill: Drill?

    @Published private(set) var lastCorrect: Bool?
    @Published private(set) var lastSelected: String?
    @Published private(set) var lastTTR: TimeInterval?
    @Published private(set) var justUnlocked: String?
    @Published private(set) var summary: String = ""

    private let engine: TrainerEngine
    private let charLadder: ProgressiveCharacters
    private let wordsQuiz: PhraseQuiz
    private let abbrevQuiz: PhraseQuiz
    private let prosignQuiz: PhraseQuiz
    private let headCopyQuiz: PhraseQuiz
    private let typedQuiz: PhraseQuiz
    private let confusionQuiz: ConfusionQuiz

    private let player = MorsePlayer()
    private var toneEndDate: Date?
    private var advanceGeneration = 0

    private static let progressKey = "MorseTrainer.progress"

    init() {
        let loaded = AppSettings.load()
        self.settings = loaded
        self.engine = TrainerEngine(config: AppModel.config(from: loaded), seedCount: 2)
        self.charLadder = ProgressiveCharacters(engine: engine)
        self.wordsQuiz = PhraseQuiz(name: "Words", items: MorseData.wordItems)
        self.abbrevQuiz = PhraseQuiz(name: "Abbreviations", items: MorseData.abbreviationItems)
        self.prosignQuiz = PhraseQuiz(name: "Prosigns", items: MorseData.prosignItems)
        self.headCopyQuiz = PhraseQuiz(name: "Head Copy", items: MorseData.wordAndCallSignItems)
        self.typedQuiz = PhraseQuiz(name: "Type It", items: MorseData.wordAndCallSignItems)
        self.confusionQuiz = ConfusionQuiz(engine: engine)
        restoreProgress()
        reconcilePunctuation()
        applyPhraseConfig(from: loaded)
        summary = charLadder.summary
    }

    private var source: QuizSource {
        switch mode {
        case .characters:   return charLadder
        case .words:        return wordsQuiz
        case .abbreviations: return abbrevQuiz
        case .prosigns:     return prosignQuiz
        case .headCopy:     return headCopyQuiz
        case .typed:        return typedQuiz
        case .confusion:    return confusionQuiz
        }
    }

    var isHeadCopy: Bool { mode == .headCopy }
    var isTyped: Bool { mode == .typed }

    private static func config(from s: AppSettings) -> TrainerEngine.Config {
        TrainerEngine.Config(
            wpm: s.wpm,
            ttrThreshold: s.ttrThreshold,
            optionCount: 4,
            distractorsFromFullAlphabet: s.distractorsFromFullAlphabet
        )
    }

    private func applyPhraseConfig(from s: AppSettings) {
        for quiz in [wordsQuiz, abbrevQuiz, prosignQuiz, headCopyQuiz, typedQuiz] {
            quiz.config.ttrThreshold = s.ttrThreshold
        }
    }

    private func applySettings() {
        engine.config = AppModel.config(from: settings)
        applyPhraseConfig(from: settings)
        reconcilePunctuation()
    }

    var timing: MorseTiming {
        settings.farnsworth
            ? MorseTiming(characterWpm: settings.wpm, effectiveWpm: settings.effectiveWpm)
            : MorseTiming(wpm: settings.wpm)
    }

    // MARK: - Mode switching

    func setMode(_ newMode: TrainingMode) {
        guard newMode != mode else { return }
        mode = newMode
        start()
    }

    // MARK: - Game loop

    func start() {
        newDrill()
    }

    private func newDrill() {
        advanceGeneration += 1   // cancel any pending auto-advance
        justUnlocked = nil
        lastCorrect = nil
        lastSelected = nil
        lastTTR = nil
        summary = source.summary
        drill = source.nextDrill()
        playCurrentTone()
    }

    func playCurrentTone() {
        guard let drill else { return }
        phase = .playing
        player.play(playable: drill.playable,
                    frequency: settings.toneFrequency,
                    timing: timing) { [weak self] in
            guard let self else { return }
            self.toneEndDate = Date()   // TTR clock starts when the last tone ends
            if self.phase == .playing { self.phase = .awaiting }
        }
    }

    /// Replay without disturbing the TTR clock (optional replay button).
    func replay() {
        guard let drill else { return }
        player.replaySound(playable: drill.playable,
                           frequency: settings.toneFrequency,
                           timing: timing)
    }

    func select(_ choice: String) {
        guard phase == .awaiting, drill != nil, let end = toneEndDate else { return }
        let ttr = Date().timeIntervalSince(end)
        let outcome = source.record(choice: choice, ttr: ttr)

        lastSelected = choice
        lastCorrect = outcome.correct
        lastTTR = ttr
        justUnlocked = outcome.unlocked
        summary = source.summary
        phase = .answered
        saveProgress()

        // Keep the rhythm going: correct answers auto-advance (unless a new
        // item was just unlocked, so the celebration banner isn't missed).
        if outcome.correct && outcome.unlocked == nil {
            advanceGeneration += 1
            let token = advanceGeneration
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { [weak self] in
                guard let self, self.advanceGeneration == token,
                      self.phase == .answered else { return }
                self.next()
            }
        }
    }

    // MARK: - Typed free-recall

    /// "Type what you hear": grade the typed text against the played item.
    /// Normalizes case/whitespace, then reuses the standard answer path.
    func submitTyped(_ text: String) {
        let normalized = text.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !normalized.isEmpty else { return }
        select(normalized)
    }

    // MARK: - Stats (for the stats screen)

    struct CharStat: Identifiable {
        let id: Character
        var character: Character { id }
        let pattern: String
        let attempts: Int
        let accuracy: Double
        let medianTTR: TimeInterval?
        let mastered: Bool
    }

    var stageName: String { charLadder.stage.displayName }
    var activeCharacterCount: Int { engine.activeCharacters.count }

    /// Per-character performance, weakest first (unmastered, then slowest).
    var characterStats: [CharStat] {
        let window = CharacterStats.historyLimit
        let stats = engine.activeCharacters.map { ch -> CharStat in
            let s = engine.stats[ch] ?? CharacterStats(character: ch)
            return CharStat(
                id: ch,
                pattern: MorseCode.pattern(for: ch) ?? "",
                attempts: s.attempts.count,
                accuracy: s.accuracy(window: window),
                medianTTR: s.medianTTR(window: window),
                mastered: s.isMastered(ttrThreshold: settings.ttrThreshold)
            )
        }
        return stats.sorted { a, b in
            if a.mastered != b.mastered { return !a.mastered }           // unmastered first
            return (a.medianTTR ?? .infinity) > (b.medianTTR ?? .infinity) // slowest first
        }
    }

    struct ConfusionPair: Identifiable {
        let id: String
        let a: Character
        let b: Character
        let aPattern: String
        let bPattern: String
        let count: Int
    }

    /// Your most-confused character pairs (both error directions summed),
    /// strongest first — the same data the Confusion Drill mode trains on.
    var confusionPairs: [ConfusionPair] {
        engine.confusions.pairs(minCount: 1).prefix(8).map { p in
            ConfusionPair(
                id: "\(p.a)\(p.b)",
                a: p.a, b: p.b,
                aPattern: MorseCode.pattern(for: p.a) ?? "",
                bPattern: MorseCode.pattern(for: p.b) ?? "",
                count: p.count)
        }
    }

    // MARK: - Head copy flow

    /// Head copy: after hearing the word and copying it mentally, reveal the
    /// answer to self-check. The TTR clock captures recall time.
    func revealHeadCopy() {
        guard isHeadCopy, phase == .awaiting, let end = toneEndDate else { return }
        lastTTR = Date().timeIntervalSince(end)
        phase = .revealed
    }

    /// Head copy: self-grade whether you got it, record it, and move on.
    func gradeHeadCopy(_ gotIt: Bool) {
        guard isHeadCopy, phase == .revealed, let drill else { return }
        let choice = gotIt ? drill.correct : "\u{1}miss"   // a guaranteed non-match for a miss
        let outcome = source.record(choice: choice, ttr: lastTTR ?? 0)
        lastCorrect = outcome.correct
        summary = source.summary
        next()
    }

    var showsNextButton: Bool {
        guard phase == .answered else { return false }
        return lastCorrect == false || justUnlocked != nil
    }

    func next() { newDrill() }

    // MARK: - Reveal helpers

    var shouldReveal: Bool {
        guard phase == .answered else { return false }
        switch settings.reveal {
        case .never:   return false
        case .always:  return true
        case .onWrong: return lastCorrect == false
        }
    }

    // MARK: - Proficiency (characters mode)

    static func characters(for proficiency: Proficiency) -> [Character] {
        switch proficiency {
        case .none:
            return Array(MorseCode.kochOrder.prefix(2))
        case .someLetters:
            return Array(MorseCode.kochOrder.filter { $0.isLetter }.prefix(13))
        case .allLetters:
            return MorseCode.kochOrder.filter { $0.isLetter }
        case .allLettersAndNumbers:
            return MorseCode.kochOrder
        }
    }

    /// Set the starting character set without playing anything (used by the
    /// intro screen, where audio shouldn't fire yet).
    func configureProficiency(_ proficiency: Proficiency) {
        settings.proficiency = proficiency
        engine.setActiveCharacters(AppModel.characters(for: proficiency))
        charLadder.resetToSingles()   // changing the set restarts the ladder
        reconcilePunctuation()
        saveProgress()
        summary = charLadder.summary
    }

    func setProficiency(_ proficiency: Proficiency) {
        configureProficiency(proficiency)
        if mode == .characters { start() }
    }

    /// Developer aid: jump the Characters track to a stage and start drilling it.
    func previewStage(_ stage: ProgressiveCharacters.Stage) {
        charLadder.jumpToStage(stage)
        mode = .characters
        reconcilePunctuation()
        saveProgress()
        start()
    }

    private func reconcilePunctuation() {
        for entry in AppSettings.availablePunctuation {
            guard let ch = entry.symbol.first else { continue }
            if settings.selectedPunctuation.contains(entry.symbol) {
                engine.addActiveCharacter(ch)
            } else {
                engine.removeActiveCharacter(ch)
            }
        }
        if mode == .characters { summary = charLadder.summary }
    }

    // MARK: - Persistence (characters mode progress)

    private func saveProgress() {
        if let data = try? JSONEncoder().encode(charLadder.snapshot) {
            UserDefaults.standard.set(data, forKey: Self.progressKey)
        }
    }

    private func restoreProgress() {
        guard let data = UserDefaults.standard.data(forKey: Self.progressKey) else { return }
        if let snap = try? JSONDecoder().decode(ProgressiveCharacters.Snapshot.self, from: data),
           !snap.engine.activeCharacters.isEmpty {
            charLadder.restore(from: snap)
        } else if let old = try? JSONDecoder().decode(TrainerEngine.Snapshot.self, from: data),
                  !old.activeCharacters.isEmpty {
            engine.restore(from: old)   // migrate older single-stage progress
        }
    }

    func resetProgress() {
        UserDefaults.standard.removeObject(forKey: Self.progressKey)
        let fresh = TrainerEngine.Snapshot(
            activeCharacters: Array(MorseCode.kochOrder.prefix(2)), stats: [])
        charLadder.restore(from: .init(engine: fresh, stage: .singles))
        reconcilePunctuation()
        phase = .idle
        drill = nil
    }
}
