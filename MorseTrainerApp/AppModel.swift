import Foundation
import SwiftUI
import MediaPlayer

/// The ways to practice.
enum TrainingMode: String, CaseIterable, Identifiable {
    case characters, words, abbreviations, qCodes, prosigns, headCopy, typed, confusion, listen, qso, story, exam, qrq
    var id: String { rawValue }
    var title: String {
        switch self {
        case .characters:   return "Characters"
        case .words:        return "Words"
        case .abbreviations: return "Abbreviations"
        case .qCodes:       return "Q-Codes"
        case .prosigns:     return "Prosigns"
        case .headCopy:     return "Head Copy"
        case .typed:        return "Type It"
        case .confusion:    return "Confusion Drill"
        case .listen:       return "Listen & Learn"
        case .qso:          return "QSO Simulator"
        case .story:        return "Short Stories"
        case .exam:         return "Code Exam"
        case .qrq:          return "QRQ Speed"
        }
    }
    var icon: String {
        switch self {
        case .characters:    return "character"
        case .words:         return "textformat"
        case .abbreviations: return "text.bubble"
        case .qCodes:        return "questionmark.bubble"
        case .prosigns:      return "antenna.radiowaves.left.and.right"
        case .headCopy:      return "brain.head.profile"
        case .typed:         return "keyboard"
        case .confusion:     return "arrow.left.arrow.right"
        case .listen:        return "headphones"
        case .qso:           return "person.wave.2"
        case .story:         return "book"
        case .exam:          return "checkmark.seal"
        case .qrq:           return "hare"
        }
    }
    /// In meaning-based modes the question is "what are they saying?"
    var prompt: String {
        switch self {
        case .characters, .words, .confusion: return "What did you hear?"
        case .abbreviations:      return "What are they saying?"
        case .qCodes:             return "What does it mean?"
        case .prosigns:           return "Which prosign?"
        case .headCopy:           return "Copy it in your head…"
        case .typed:              return "Type what you hear"
        case .listen:             return "Listen…"
        case .qso:                return "Type what you copy"
        case .story:              return "Copy the passage"
        case .exam:               return "Copy the exam transmission"
        case .qrq:                return "Type what you hear"
        }
    }
    /// A very short descriptor shown on the mode-selection tiles (intro screen).
    /// Kept to a few words so two tiles sit side by side cleanly.
    var tagline: String {
        switch self {
        case .characters:    return "Core Koch drill"
        case .words:         return "Whole ham words"
        case .abbreviations: return "CW abbreviations"
        case .qCodes:        return "Q-signal shorthand"
        case .prosigns:      return "Run-together signals"
        case .headCopy:      return "Copy in your head"
        case .typed:         return "Free-recall typing"
        case .confusion:     return "Drill your mix-ups"
        case .listen:        return "Hands-free, eyes-free"
        case .qso:           return "Simulated contact"
        case .story:         return "Continuous copy"
        case .exam:          return "ARRL/FCC code exam"
        case .qrq:           return "High-speed copy"
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
            return "Hear a CW abbreviation (like ES or FB) and choose what it means."
        case .qCodes:
            return "Hear a three-letter Q-code (like QTH or QRL) and choose what it means — the shorthand hams use to ask and answer on CW."
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
        case .story:
            return "Continuous copy: hear a short story sent end to end. Copy it on paper or in your head, then reveal the text to check yourself."
        case .exam:
            return "Sit a recreation of the old ARRL/FCC code-proficiency exam: a 5-minute QSO-style transmission at 5, 13, or 20 WPM. Pass with one minute of solid copy (25 characters in a row) or by answering questions about what was sent."
        case .qrq:
            return "Push your speed: hear whole words and call signs at 35 or 40 WPM and type what you copy. Too fast to count dits — this trains instant, whole-word recognition (QRQ = “send faster”)."
        }
    }

    /// Whether a Koch starting-level ("what do you already know") choice changes
    /// this mode's drill. Only the modes that draw from the progressive character
    /// ladder care — the rest use fixed content pools, so asking would be noise.
    var usesStartingLevel: Bool {
        switch self {
        case .characters, .confusion: return true
        default:                      return false
        }
    }

    /// Whether a session-length choice applies. Exam (a fixed-format proficiency
    /// run) and Story (one passage played end to end) are self-contained, so a
    /// "how long?" question is meaningless for them.
    var usesSessionLength: Bool {
        switch self {
        case .exam, .story: return false
        default:            return true
        }
    }

    /// True when starting this mode should prompt for any pre-session options.
    var needsSetup: Bool { usesStartingLevel || usesSessionLength }
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
    private var wordsQuiz: PhraseQuiz   // rebuilt when the word tier changes
    private let abbrevQuiz: PhraseQuiz
    private let qCodeQuiz: PhraseQuiz
    private let prosignQuiz: PhraseQuiz
    private let headCopyQuiz: PhraseQuiz
    private let typedQuiz: PhraseQuiz
    private let qrqQuiz: PhraseQuiz
    private let confusionQuiz: ConfusionQuiz
    private let qsoSim = QSOSimulator()
    private var examSession: ExamSession?
    private var examSampleIndex = 0

    private let player = MorsePlayer()
    private let speech = SpeechPlayer()
    private var toneEndDate: Date?
    private var advanceGeneration = 0

    // Voice response (Characters & Words modes): answer by speaking.
    enum VoiceState: Equatable { case inactive, listening, confirming, fallback }
    @Published private(set) var voiceState: VoiceState = .inactive
    @Published private(set) var voiceHeardText: String?      // raw best transcript
    @Published private(set) var voiceGuess: String?          // token to confirm
    @Published private(set) var voiceFallbackOptions: [String] = []  // closest-to-input
    private let voiceRecognizer = VoiceRecognizer()
    private var voiceMatcher = VoiceMatcher()
    private var voiceProfile = VoiceProfile() { didSet { saveVoiceProfile() } }
    private var voiceOnsetDate: Date?
    private var voiceTranscripts: [String] = []
    private static let voiceProfileKey = "MorseTrainer.voiceProfile"

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
        self.wordsQuiz = PhraseQuiz(name: "Words", items: MorseData.topWordItems(loaded.wordTier.count))
        self.abbrevQuiz = PhraseQuiz(name: "Abbreviations", items: MorseData.abbreviationItems)
        self.qCodeQuiz = PhraseQuiz(name: "Q-Codes", items: MorseData.qCodeItems)
        self.prosignQuiz = PhraseQuiz(name: "Prosigns", items: MorseData.prosignItems)
        self.headCopyQuiz = PhraseQuiz(name: "Head Copy", items: MorseData.wordAndCallSignItems)
        self.typedQuiz = PhraseQuiz(name: "Type It", items: MorseData.wordAndCallSignItems)
        self.qrqQuiz = PhraseQuiz(name: "QRQ", items: MorseData.wordAndCallSignItems)
        self.confusionQuiz = ConfusionQuiz(engine: engine)
        restoreProgress()
        reconcilePunctuation()
        applyPhraseConfig(from: loaded)
        restoreVoiceProfile()
        summary = charLadder.summary
    }

    private var source: QuizSource {
        switch mode {
        case .characters:   return charLadder
        case .words:        return wordsQuiz
        case .abbreviations: return abbrevQuiz
        case .qCodes:       return qCodeQuiz
        case .prosigns:     return prosignQuiz
        case .headCopy:     return headCopyQuiz
        case .typed:        return typedQuiz
        case .confusion:    return confusionQuiz
        case .listen:       return charLadder   // unused: Listen runs its own loop
        case .qso:          return qsoSim
        case .story:        return charLadder   // unused: Stories run their own playback
        case .exam:         return (examSession as QuizSource?) ?? charLadder   // exam runs its own flow
        case .qrq:          return qrqQuiz
        }
    }

    var isHeadCopy: Bool { mode == .headCopy }
    var isTyped: Bool { mode == .typed }
    var isListen: Bool { mode == .listen }
    var isQSO: Bool { mode == .qso }
    var isStory: Bool { mode == .story }
    var isExam: Bool { mode == .exam }
    var isQRQ: Bool { mode == .qrq }
    /// Modes that take a free-typed answer rather than tapping a choice.
    var usesTypedEntry: Bool { mode == .typed || mode == .qso || mode == .qrq }
    /// Whether the learner answers by voice this session (Characters & Words).
    var usesVoiceResponse: Bool {
        settings.voiceResponse && (mode == .characters || mode == .words)
    }
    /// True when mic/speech permission was refused, so the UI can fall back.
    var voicePermissionDenied: Bool { voiceRecognizer.authorization == .denied }

    /// The teaching style chosen on the setup screen (mirrors `settings`).
    var learningMode: TrainingMode {
        get { TrainingMode(rawValue: settings.learningMode) ?? .characters }
        set { settings.learningMode = newValue.rawValue }
    }

    private static func config(from s: AppSettings) -> TrainerEngine.Config {
        TrainerEngine.Config(
            wpm: s.wpm,
            ttrThreshold: s.ttrThreshold,
            optionCount: s.maxAnswerChoices
        )
    }

    private func applyPhraseConfig(from s: AppSettings) {
        // Rebuild the Words quiz if the chosen "Top N" tier changed.
        if wordsQuiz.items.count != s.wordTier.count {
            wordsQuiz = PhraseQuiz(name: "Words", items: MorseData.topWordItems(s.wordTier.count))
        }
        for quiz in [wordsQuiz, abbrevQuiz, qCodeQuiz, prosignQuiz, headCopyQuiz, typedQuiz, qrqQuiz] {
            quiz.config.ttrThreshold = s.ttrThreshold
            quiz.config.optionCount = s.maxAnswerChoices
        }
    }

    private func applySettings() {
        engine.config = AppModel.config(from: settings)
        applyPhraseConfig(from: settings)
        reconcilePunctuation()
    }

    var timing: MorseTiming {
        // QRQ overrides the global WPM with its high-speed (35/40) character rate.
        if mode == .qrq { return MorseTiming(wpm: settings.qrqSpeed.wpm) }
        return settings.farnsworth
            ? MorseTiming(characterWpm: settings.wpm, effectiveWpm: settings.effectiveWpm)
            : MorseTiming(wpm: settings.wpm)
    }

    // MARK: - Mode switching

    /// Switching modes ends the current session and surfaces its summary — it
    /// does not restart one. The chosen mode becomes the selection for the next
    /// session, which only begins on an explicit start ("Practice again" or a
    /// fresh start from setup).
    func setMode(_ newMode: TrainingMode) {
        guard newMode != mode else { return }
        learningMode = newMode
        endSession()
    }

    // MARK: - Game loop

    func start() {
        resetVoiceRound()
        storyGeneration += 1   // cancel any in-flight story playback
        storyPlaying = false
        if mode == .listen {
            startStory(active: false)
            startListening()
        } else if mode == .story {
            stopListening()
            startStoryMode()
        } else if mode == .exam {
            stopListening()
            startStory(active: false)
            startExamMode()
        } else {
            stopListening()
            startStory(active: false)
            newDrill()
        }
    }

    // MARK: - Short Stories (continuous copy)

    @Published private(set) var storyActive = false
    @Published private(set) var storyPlaying = false
    @Published private(set) var storyRevealed = false
    @Published private(set) var storyTitle = ""
    @Published private(set) var storyText = ""
    private var storyGeneration = 0
    private var storyIndex = 0

    /// Set up story mode (pick a passage, wait for the Play tap).
    private func startStoryMode() {
        storyGeneration += 1
        storyActive = true
        storyPlaying = false
        storyRevealed = false
        pickStory()
        phase = .idle
    }

    /// Toggle the `storyActive` flag (used to tear down when leaving the mode).
    private func startStory(active: Bool) {
        if !active {
            storyActive = false
            storyPlaying = false
            storyRevealed = false
        }
    }

    private func pickStory() {
        let stories = MorseData.stories
        guard !stories.isEmpty else { storyTitle = ""; storyText = ""; return }
        let n = stories.count
        let s = stories[((storyIndex % n) + n) % n]
        storyTitle = s.title
        storyText = s.text
        summary = "Story \((((storyIndex % n) + n) % n) + 1) of \(n)"
    }

    /// Send the whole passage as one continuous transmission.
    func playStory() {
        guard isStory, !storyText.isEmpty else { return }
        storyGeneration += 1
        let gen = storyGeneration
        storyRevealed = false
        storyPlaying = true
        phase = .playing
        player.play(playable: .text(storyText),
                    frequency: settings.toneFrequency,
                    timing: timing) { [weak self] in
            guard let self, self.storyGeneration == gen else { return }
            self.storyPlaying = false
            self.phase = .awaiting
            self.sessionAttempts += 1
        }
    }

    /// Stop sending without revealing (cancels the completion via generation).
    func stopStory() {
        guard isStory else { return }
        storyGeneration += 1
        player.stop()
        storyPlaying = false
        phase = .idle
    }

    /// Show the passage text to check your copy.
    func revealStory() {
        guard isStory else { return }
        storyRevealed = true
    }

    /// Advance to the next passage (does not auto-play).
    func nextStory() {
        storyGeneration += 1
        player.stop()
        storyIndex += 1
        storyPlaying = false
        storyRevealed = false
        pickStory()
        phase = .idle
    }

    // MARK: - Code Exam (ARRL/FCC-style proficiency exam)

    /// Where we are in one exam: waiting to start, sending the passage, taking
    /// a typed copy, answering questions, or showing the result.
    enum ExamStage { case ready, playing, copy, question, results }

    @Published private(set) var examStage: ExamStage = .ready
    @Published private(set) var examPlaying = false
    @Published private(set) var examRevealed = false
    // Solid-copy grading.
    @Published private(set) var examCopyResult: ExamCopyResult?
    // Question grading.
    @Published private(set) var examQuestion: ExamQuestion?
    @Published private(set) var examQuestionNumber = 0   // 1-based, for display
    @Published private(set) var examQuestionCount = 0
    @Published private(set) var examSelected: String?
    @Published private(set) var examAnswerCorrect: Bool?
    @Published private(set) var examCorrectCount = 0
    private var examGeneration = 0

    var examSpeed: ExamSpeed { examSession?.speed ?? settings.examSpeed }
    var examGrading: ExamGrading { examSession?.grading ?? settings.examGrading }
    /// Pretty, prosign-annotated passage text for the reveal screen.
    var examPassageText: String { examSession?.passage.displayText ?? "" }
    var examRequiredRun: Int { ExamSession.requiredRun }

    /// Timing for the exam comes from its license speed (Farnsworth at 5 WPM),
    /// overriding the global WPM setting.
    private var examTiming: MorseTiming { examSession?.speed.timing ?? timing }

    private func makeExamSession() -> ExamSession {
        let speed = settings.examSpeed
        let grading = settings.examGrading
        if settings.examUseBundled {
            let samples = MorseData.examSamples(for: speed)
            if !samples.isEmpty {
                let n = samples.count
                let sample = samples[((examSampleIndex % n) + n) % n]
                return ExamSession(speed: speed, grading: grading, passage: sample.passage)
            }
        }
        return ExamSession(speed: speed, grading: grading)
    }

    /// Set up an exam (build the session, wait for the Start/Play tap).
    private func startExamMode() {
        examGeneration += 1
        let session = makeExamSession()
        examSession = session
        examStage = .ready
        examPlaying = false
        examRevealed = false
        examCopyResult = nil
        examSelected = nil
        examAnswerCorrect = nil
        examCorrectCount = 0
        examQuestion = nil
        examQuestionNumber = 0
        examQuestionCount = session.questions.count
        phase = .idle
        summary = session.summary
    }

    /// Send the whole exam transmission, then move on to copy or questions.
    func playExam() {
        guard isExam, let session = examSession else { return }
        examGeneration += 1
        let gen = examGeneration
        examRevealed = false
        examPlaying = true
        examStage = .playing
        phase = .playing
        player.play(playable: .text(session.passage.sentText),
                    frequency: settings.toneFrequency,
                    timing: examTiming) { [weak self] in
            guard let self, self.examGeneration == gen else { return }
            self.examPlaying = false
            self.sessionAttempts += 1
            switch session.grading {
            case .solidCopy:
                self.examStage = .copy
                self.phase = .awaiting
            case .questions:
                self.beginExamQuestions()
            }
        }
    }

    /// Stop sending the passage without grading (cancels via generation).
    func stopExam() {
        guard isExam else { return }
        examGeneration += 1
        player.stop()
        examPlaying = false
        examStage = .ready
        phase = .idle
    }

    // MARK: Solid copy

    /// Grade the typed copy: pass needs 25 correct characters in a row.
    func submitExamCopy(_ text: String) {
        guard isExam, let session = examSession, examStage == .copy else { return }
        let result = session.gradeSolidCopy(text)
        examCopyResult = result
        _ = session.record(choice: text, ttr: 0)
        noteSessionResult(correct: result.passed, ttr: 0)
        examRevealed = true
        examStage = .results
        phase = .idle
    }

    // MARK: Questions

    private func beginExamQuestions() {
        guard examSession != nil else { return }
        examStage = .question
        loadExamQuestion()
    }

    private func loadExamQuestion() {
        guard let session = examSession else { return }
        examSelected = nil
        examAnswerCorrect = nil
        let idx = min(session.questionIndex, max(0, session.questions.count - 1))
        examQuestion = session.questions.isEmpty ? nil : session.questions[idx]
        examQuestionNumber = session.questionIndex + 1
        summary = session.summary
        phase = .awaiting
    }

    /// Record an answer to the current question (no auto-advance — the learner
    /// taps Next to continue, so they can read the feedback).
    func answerExamQuestion(_ choice: String) {
        guard isExam, let session = examSession, examStage == .question,
              examAnswerCorrect == nil else { return }
        let outcome = session.record(choice: choice, ttr: 0)
        examSelected = choice
        examAnswerCorrect = outcome.correct
        examCorrectCount = session.correctCount
        noteSessionResult(correct: outcome.correct, ttr: 0)
        phase = .answered
    }

    /// Advance to the next question, or to the results when finished.
    func nextExamQuestion() {
        guard isExam, let session = examSession, examStage == .question else { return }
        if session.isComplete {
            examRevealed = true
            examStage = .results
            phase = .idle
        } else {
            loadExamQuestion()
        }
    }

    /// Show the full passage text on the results screen.
    func revealExam() {
        guard isExam else { return }
        examRevealed = true
    }

    /// Start a fresh exam (new passage / next bundled sample).
    func newExam() {
        examGeneration += 1
        player.stop()
        examSampleIndex += 1
        startExamMode()
    }

    /// "7 / 10" style score for question mode.
    var examScoreText: String {
        guard let session = examSession else { return "" }
        return "\(session.correctCount) / \(session.questions.count)"
    }

    /// Whether the exam was passed by the historical rule for its grading mode.
    var examPassed: Bool {
        guard let session = examSession else { return false }
        switch session.grading {
        case .solidCopy:
            return examCopyResult?.passed ?? false
        case .questions:
            // Historically ~10 questions with a 7-of-10 (≈74%) passing bar.
            let total = session.questions.count
            return total > 0 && Double(session.correctCount) / Double(total) >= 0.7
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
            let item = MorseData.topWordItems(settings.wordTier.count).randomElement()
                ?? MorseItem(id: "THE", playable: .text("THE"), answer: "THE", display: "THE")
            return ListenItem(playable: item.playable, display: item.display, spoken: item.answer)
        case .abbreviations:
            let item = (MorseData.abbreviationItems + MorseData.qCodeItems).randomElement()
                ?? MorseItem(id: "ES", playable: .text("ES"), answer: "and", display: "ES")
            // Spell the token in lowercase letters so TTS says "q t h", not
            // "capital Q…", then the meaning.
            let spelled = item.display.lowercased().map(String.init).joined(separator: " ")
            return ListenItem(playable: item.playable,
                              display: "\(item.display) — \(item.answer)",
                              spoken: "\(spelled). \(item.answer)")
        }
    }

    /// Human-readable name for a single character so TTS says just the letter
    /// (lowercased so the synthesizer doesn't announce "capital A").
    private func spokenName(for ch: Character) -> String {
        switch ch {
        case "?": return "question mark"
        case ",": return "comma"
        case ".": return "period"
        case "/": return "slash"
        case "=": return "equals"
        case "+": return "plus"
        default:  return String(ch).lowercased()
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

        // Voice response: get permission up front and warm up the custom
        // language model so the first drill doesn't stall.
        if usesVoiceResponse {
            voiceRecognizer.requestAuthorization()
            voiceRecognizer.prepareCustomLanguageModel(phrases: voiceUniversePhrases())
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
        resetVoiceRound()
        stopListening()
        storyGeneration += 1     // cancel any story playback
        if isStory { player.stop() }
        storyActive = false
        storyPlaying = false
        examGeneration += 1      // cancel any exam playback
        if isExam { player.stop() }
        examPlaying = false
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
        resetVoiceRound()
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
            if self.phase == .playing {
                self.phase = .awaiting
                if self.usesVoiceResponse { self.beginVoiceListening() }
            }
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
        commitAnswer(choice, ttr: Date().timeIntervalSince(end))
    }

    /// Record an answer, show feedback, and auto-advance on a clean correct.
    /// Shared by tap-to-choose, voice, and typed entry.
    private func commitAnswer(_ choice: String, ttr: TimeInterval) {
        guard drill != nil else { return }
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

    // MARK: - Voice response

    /// Cancel any in-flight listening and clear the per-round voice state.
    private func resetVoiceRound() {
        voiceRecognizer.stop()
        voiceState = .inactive
        voiceHeardText = nil
        voiceGuess = nil
        voiceFallbackOptions = []
        voiceOnsetDate = nil
        voiceTranscripts = []
    }

    /// Start listening for the spoken answer (called when the tone finishes).
    private func beginVoiceListening() {
        guard let drill else { return }
        voiceState = .listening
        voiceOnsetDate = nil
        voiceTranscripts = []
        voiceMatcher.profile = voiceProfile
        voiceRecognizer.start(
            contextualStrings: voiceMatcher.contextualStrings(for: drill.options),
            onOnset: { [weak self] in self?.markVoiceOnset() },
            onResult: { [weak self] transcripts in self?.handleVoiceResult(transcripts) }
        )
    }

    /// Stamp the time-to-recognize clock at the *start* of the spoken answer.
    private func markVoiceOnset() {
        guard voiceState == .listening, voiceOnsetDate == nil else { return }
        voiceOnsetDate = Date()
    }

    private func handleVoiceResult(_ transcripts: [String]) {
        guard usesVoiceResponse, phase == .awaiting, voiceState == .listening,
              let drill else { return }
        voiceTranscripts = transcripts
        voiceHeardText = transcripts.first

        // No usable audio (silence, or permission denied): fall back to tapping
        // the standard choices so the learner is never stuck.
        guard !transcripts.isEmpty else {
            presentFallback(using: Array(drill.options.prefix(4)))
            return
        }

        let interpretation = voiceMatcher.interpret(transcripts, candidates: drill.options)
        if let token = interpretation.token, interpretation.isConfident {
            gradeVoiceAnswer(token)
        } else if let guess = interpretation.token {
            voiceGuess = guess
            voiceState = .confirming
        } else {
            presentFallback(using: rankedFallback())
        }
    }

    /// Respond to the "Did you say X?" prompt.
    func confirmVoiceGuess(_ yes: Bool) {
        guard voiceState == .confirming, let guess = voiceGuess else { return }
        if yes {
            if let heard = voiceHeardText { learnVoice(heard: heard, answer: guess) }
            gradeVoiceAnswer(guess)
        } else {
            // Offer the answers that sound closest to what they actually said.
            presentFallback(using: rankedFallback())
        }
    }

    /// Pick one of the closest-sounding answers after rejecting the guess.
    func selectVoiceFallback(_ choice: String) {
        guard voiceState == .fallback else { return }
        if let heard = voiceHeardText { learnVoice(heard: heard, answer: choice) }
        gradeVoiceAnswer(choice)
    }

    private func presentFallback(using options: [String]) {
        voiceRecognizer.stop()
        voiceFallbackOptions = options
        voiceState = .fallback
    }

    /// The answers closest to what was heard, drawn from the whole answer pool
    /// plus the drill's own options (so the right answer can still appear).
    private func rankedFallback() -> [String] {
        guard let drill else { return [] }
        var pool = voiceAnswerPool()
        for option in drill.options where !pool.contains(option) { pool.append(option) }
        let ranked = voiceMatcher.rankedCandidates(voiceTranscripts, pool: pool, limit: 4)
        return ranked.isEmpty ? Array(drill.options.prefix(4)) : ranked
    }

    /// The universe of valid answers for the current voice-enabled mode.
    private func voiceAnswerPool() -> [String] {
        switch mode {
        case .characters: return engine.activeCharacters.map { String($0) }
        case .words:      return wordsQuiz.items.map { $0.answer }
        default:          return drill?.options ?? []
        }
    }

    /// Every spoken form of every possible answer — used to bias recognition.
    private func voiceUniversePhrases() -> [String] {
        voiceMatcher.contextualStrings(for: voiceAnswerPool())
    }

    private func gradeVoiceAnswer(_ choice: String) {
        guard phase == .awaiting, let end = toneEndDate else { return }
        voiceRecognizer.stop()
        voiceState = .inactive
        let onset = voiceOnsetDate ?? Date()
        commitAnswer(choice, ttr: max(0, onset.timeIntervalSince(end)))
    }

    private func learnVoice(heard: String, answer: String) {
        voiceProfile.record(heard: heard, answer: answer)
        voiceMatcher.profile = voiceProfile
    }

    private func saveVoiceProfile() {
        if let data = try? JSONEncoder().encode(voiceProfile) {
            UserDefaults.standard.set(data, forKey: Self.voiceProfileKey)
        }
    }

    private func restoreVoiceProfile() {
        guard let data = UserDefaults.standard.data(forKey: Self.voiceProfileKey),
              let profile = try? JSONDecoder().decode(VoiceProfile.self, from: data) else { return }
        voiceProfile = profile
        voiceMatcher.profile = profile
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
        let chars = AppModel.characters(for: proficiency)
        engine.setActiveCharacters(chars)
        // A declared proficiency front-loads its characters as "already met" so
        // the learner sees a full set of choices right away. A true beginner
        // ("I know nothing") starts from a single option and builds up as each
        // new character is introduced.
        engine.setExposedCharacters(proficiency == .none ? [] : chars)
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
