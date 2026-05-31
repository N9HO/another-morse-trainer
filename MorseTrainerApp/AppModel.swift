import Foundation
import SwiftUI
import MediaPlayer

/// The ways to practice.
enum TrainingMode: String, CaseIterable, Identifiable {
    case characters, words, abbreviations, prosigns, headCopy, typed, confusion, listen, qso
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
        case .listen:       return "Listen & Learn"
        case .qso:          return "QSO Simulator"
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
        case .listen:        return "headphones"
        case .qso:           return "person.wave.2"
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
        case .listen:             return "Listen…"
        case .qso:                return "Type what you copy"
        }
    }
    /// A one-line explanation shown on the setup screen so the learner can pick
    /// the teaching style that fits what they want to practice.
    var blurb: String {
        switch self {
        case .characters:
            return "The core Koch drill: hear one character at full speed and tap it from four sound-alikes. Grows into pairs, triples, then words as you improve."
        case .words:
            return "Copy whole common ham-radio words and pick the right one from four look-alikes."
        case .abbreviations:
            return "Hear a CW abbreviation or Q-code (like ES or QTH) and choose what it means."
        case .prosigns:
            return "Recognize run-together prosigns such as <AR> and <SK> by their rhythm."
        case .headCopy:
            return "Listen to a word, copy it in your head, then reveal and self-grade — no buttons. Builds true head-copy."
        case .typed:
            return "Hear a word or call sign and type exactly what you heard. Free recall — the closest thing to real copying."
        case .confusion:
            return "Targeted review of the exact character pairs you mix up most, drilled head-to-head until they stick."
        case .listen:
            return "Hands-free: hear the code, then the answer spoken aloud — no tapping. Keeps playing with the screen locked, so you can learn while driving or walking."
        case .qso:
            return "Work a simulated POTA contact: you call CQ, a station answers, and you type what you copy — their callsign, then their state. One contact at a time."
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

    // Session timer & running tally (for the timed-practice feature).
    @Published private(set) var sessionRemaining: TimeInterval?   // nil = no limit
    @Published private(set) var sessionEnded = false
    private var sessionEndDate: Date?
    private var sessionTimer: Timer?
    private var sessionAttempts = 0
    private var sessionCorrect = 0
    private var sessionFastest: TimeInterval?
    private var sessionTTRs: [TimeInterval] = []

    private let engine: TrainerEngine
    private let charLadder: ProgressiveCharacters
    private let wordsQuiz: PhraseQuiz
    private let abbrevQuiz: PhraseQuiz
    private let prosignQuiz: PhraseQuiz
    private let headCopyQuiz: PhraseQuiz
    private let typedQuiz: PhraseQuiz
    private let confusionQuiz: ConfusionQuiz
    private let qsoSim = QSOSimulator()

    private let player = MorsePlayer()
    private let speech = SpeechPlayer()
    private var toneEndDate: Date?
    private var advanceGeneration = 0

    // Hands-free "Listen & Learn" loop.
    @Published private(set) var isListening = false
    @Published private(set) var listenPaused = false
    @Published private(set) var listenDisplay = ""     // current item, shown on screen
    private var listenGeneration = 0
    private var remoteCommandsWired = false

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
        case .listen:       return charLadder   // unused: Listen runs its own loop
        case .qso:          return qsoSim
        }
    }

    var isHeadCopy: Bool { mode == .headCopy }
    var isTyped: Bool { mode == .typed }
    var isListen: Bool { mode == .listen }
    var isQSO: Bool { mode == .qso }
    /// Modes that take a free-typed answer rather than tapping a choice.
    var usesTypedEntry: Bool { mode == .typed || mode == .qso }

    /// The teaching style chosen on the setup screen (mirrors `settings`).
    var learningMode: TrainingMode {
        get { TrainingMode(rawValue: settings.learningMode) ?? .characters }
        set { settings.learningMode = newValue.rawValue }
    }

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
        if mode == .listen {
            startListening()
        } else {
            stopListening()
            newDrill()
        }
    }

    // MARK: - Listen & Learn (hands-free)

    private struct ListenItem {
        let playable: MorseItem.Playable
        let display: String   // shown on screen
        let spoken: String    // announced via TTS
    }

    /// Begin the hands-free loop: play code → wait the chosen gap → speak the
    /// answer → repeat. Keeps going with the screen locked (background audio).
    private func startListening() {
        listenGeneration += 1
        isListening = true
        listenPaused = false
        wireRemoteCommandsIfNeeded()
        listenStep()
    }

    /// Stop the loop entirely (mode change, session end, reset).
    func stopListening() {
        guard isListening else { return }
        isListening = false
        listenPaused = false
        listenGeneration += 1
        speech.stop()
        listenDisplay = ""
        phase = .idle
        clearNowPlaying()
    }

    func pauseListening() {
        guard isListening, !listenPaused else { return }
        listenPaused = true
        listenGeneration += 1   // cancel the in-flight chain
        speech.stop()
        phase = .idle
        updateNowPlaying()
    }

    func resumeListening() {
        guard isListening, listenPaused else { return }
        listenPaused = false
        listenStep()
    }

    func toggleListening() {
        listenPaused ? resumeListening() : pauseListening()
    }

    /// One play → gap → speak → schedule-next cycle. Each async hop re-checks the
    /// generation so a pause/stop/mode-change cleanly cancels the chain.
    private func listenStep() {
        let gen = listenGeneration
        guard isListening, !listenPaused else { return }

        let item = nextListenItem()
        listenDisplay = ""             // hide the answer while the code plays
        phase = .playing
        updateNowPlaying()

        player.play(playable: item.playable,
                    frequency: settings.toneFrequency,
                    timing: timing) { [weak self] in
            guard let self, self.listenGeneration == gen, self.isListening, !self.listenPaused else { return }
            let gap = self.settings.listenGap.seconds
            DispatchQueue.main.asyncAfter(deadline: .now() + gap) {
                guard self.listenGeneration == gen, self.isListening, !self.listenPaused else { return }
                self.listenDisplay = item.display
                self.phase = .revealed
                self.updateNowPlaying()
                self.speech.speak(item.spoken) {
                    guard self.listenGeneration == gen, self.isListening, !self.listenPaused else { return }
                    self.sessionAttempts += 1   // count items announced this session
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
                        guard self.listenGeneration == gen, self.isListening, !self.listenPaused else { return }
                        self.listenStep()
                    }
                }
            }
        }
    }

    private func nextListenItem() -> ListenItem {
        switch settings.listenContent {
        case .characters:
            let ch = engine.activeCharacters.randomElement() ?? "E"
            return ListenItem(playable: .text(String(ch)),
                              display: String(ch),
                              spoken: spokenName(for: ch))
        case .words:
            let item = MorseData.wordItems.randomElement()
                ?? MorseItem(id: "THE", playable: .text("THE"), answer: "THE", display: "THE")
            return ListenItem(playable: item.playable, display: item.display, spoken: item.answer)
        case .abbreviations:
            let item = MorseData.abbreviationItems.randomElement()
                ?? MorseItem(id: "ES", playable: .text("ES"), answer: "and", display: "ES")
            let spelled = item.display.map(String.init).joined(separator: " ")
            return ListenItem(playable: item.playable,
                              display: "\(item.display) — \(item.answer)",
                              spoken: "\(spelled). \(item.answer)")
        }
    }

    /// Human-readable name for a single character so TTS says it clearly.
    private func spokenName(for ch: Character) -> String {
        switch ch {
        case "?": return "question mark"
        case ",": return "comma"
        case ".": return "period"
        case "/": return "slash"
        case "=": return "equals"
        case "+": return "plus"
        default:  return String(ch)
        }
    }

    // MARK: Lock-screen / remote controls

    private func wireRemoteCommandsIfNeeded() {
        guard !remoteCommandsWired else { return }
        remoteCommandsWired = true
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.resumeListening() }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.pauseListening() }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.toggleListening() }
            return .success
        }
    }

    private func updateNowPlaying() {
        var info: [String: Any] = [:]
        info[MPMediaItemPropertyTitle] = listenDisplay.isEmpty ? "Listening…" : listenDisplay
        info[MPMediaItemPropertyArtist] = "Morse Trainer · Listen & Learn"
        info[MPNowPlayingInfoPropertyPlaybackRate] = listenPaused ? 0.0 : 1.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func clearNowPlaying() {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    // MARK: - Practice session (timed)

    struct SessionSummary {
        let attempts: Int
        let correct: Int
        let accuracy: Double
        let fastest: TimeInterval?
        let medianTTR: TimeInterval?
        let duration: PracticeDuration
        let mode: TrainingMode
    }

    /// Begin a fresh practice session: apply the chosen learning style, reset
    /// the running tally, start the countdown (if any), and hand out a drill.
    func startSession() {
        mode = learningMode
        sessionEnded = false
        sessionAttempts = 0
        sessionCorrect = 0
        sessionFastest = nil
        sessionTTRs = []
        sessionTimer?.invalidate()
        sessionTimer = nil

        if let secs = settings.practiceDuration.seconds {
            sessionEndDate = Date().addingTimeInterval(secs)
            sessionRemaining = secs
            let timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.sessionTick() }
            }
            sessionTimer = timer
        } else {
            sessionEndDate = nil
            sessionRemaining = nil
        }
        start()
    }

    private func sessionTick() {
        guard let end = sessionEndDate else { return }
        let remaining = end.timeIntervalSinceNow
        if remaining <= 0 {
            sessionRemaining = 0
            endSession()
        } else {
            sessionRemaining = remaining
        }
    }

    /// Stop the session, cancel any pending auto-advance, and show the summary.
    func endSession() {
        sessionTimer?.invalidate()
        sessionTimer = nil
        sessionEndDate = nil
        advanceGeneration += 1   // cancel any pending auto-advance
        stopListening()
        phase = .idle
        sessionEnded = true
    }

    /// Add one answered drill to the running session tally.
    private func noteSessionResult(correct: Bool, ttr: TimeInterval) {
        sessionAttempts += 1
        if correct { sessionCorrect += 1 }
        if ttr > 0 {
            sessionTTRs.append(ttr)
            if correct { sessionFastest = min(sessionFastest ?? .infinity, ttr) }
        }
    }

    var sessionSummary: SessionSummary {
        let accuracy = sessionAttempts == 0 ? 0 : Double(sessionCorrect) / Double(sessionAttempts)
        let median: TimeInterval?
        if sessionTTRs.isEmpty {
            median = nil
        } else {
            let sorted = sessionTTRs.sorted()
            let mid = sorted.count / 2
            median = sorted.count % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid]
        }
        return SessionSummary(
            attempts: sessionAttempts,
            correct: sessionCorrect,
            accuracy: accuracy,
            fastest: sessionFastest,
            medianTTR: median,
            duration: settings.practiceDuration,
            mode: mode)
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
        noteSessionResult(correct: outcome.correct, ttr: ttr)
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
        noteSessionResult(correct: outcome.correct, ttr: lastTTR ?? 0)
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
