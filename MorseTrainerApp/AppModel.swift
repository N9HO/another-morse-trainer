import Foundation
import SwiftUI
import MediaPlayer

/// The ways to practice.
enum TrainingMode: String, CaseIterable, Identifiable {
    case journey, characters, words, abbreviations, qCodes, prosigns, headCopy, typed, confusion, listen, qso, contest, story, exam, qrq, rapidFire
    var id: String { rawValue }
    var title: String {
        switch self {
        case .journey:      return "Journey"
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
        case .contest:      return "Contest"
        case .story:        return "Short Stories"
        case .exam:         return "Code Exam"
        case .qrq:          return "QRQ Speed"
        case .rapidFire:    return "Rapid Fire"
        }
    }
    var icon: String {
        switch self {
        case .journey:       return "map"
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
        case .contest:       return "trophy"
        case .story:         return "book"
        case .exam:          return "checkmark.seal"
        case .qrq:           return "hare"
        case .rapidFire:     return "bolt.fill"
        }
    }
    /// In meaning-based modes the question is "what are they saying?"
    var prompt: String {
        switch self {
        case .journey:        return "What did you hear?"
        case .characters, .words, .confusion: return "What did you hear?"
        case .abbreviations:      return "What are they saying?"
        case .qCodes:             return "What does it mean?"
        case .prosigns:           return "Which prosign?"
        case .headCopy:           return "Copy it in your head…"
        case .typed:              return "Type what you hear"
        case .listen:             return "Listen…"
        case .qso:                return "Type what you copy"
        case .contest:            return "Type what you copy"
        case .story:              return "Copy the passage"
        case .exam:               return "Copy the exam transmission"
        case .qrq:                return "Type what you hear"
        case .rapidFire:          return "Copy what you hear"
        }
    }
    /// A very short descriptor shown on the mode-selection tiles (intro screen).
    /// Kept to a few words so two tiles sit side by side cleanly.
    var tagline: String {
        switch self {
        case .journey:       return "Leveled path"
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
        case .contest:       return "SST & CWT runs"
        case .story:         return "Continuous copy"
        case .exam:          return "ARRL/FCC code exam"
        case .qrq:           return "High-speed copy"
        case .rapidFire:     return "Back-to-back copy"
        }
    }

    /// A one-line explanation shown on the setup screen so the learner can pick
    /// the teaching style that fits what they want to practice.
    var blurb: String {
        switch self {
        case .journey:
            return "Climb a leveled path: each level adds two new symbols and mixes in everything before it. Fill the progress bar with correct answers to clear a level and unlock the next — but a miss drains the bar, so stay sharp. Runs letters → numbers → punctuation → prosigns → Q-codes → abbreviations → words → call signs."
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
        case .contest:
            return "Run a simulated contest against the clock. Pick the K1USN SST (slow, name + state) or the CWops CWT (fast, name + member number), call CQ, and work the pileup that answers. Authentic speeds, a live score and rate, and an end-of-run scorecard — the closest thing to being in the chair on contest day."
        case .story:
            return "Continuous copy: hear a short story sent end to end. Copy it on paper or in your head, then reveal the text to check yourself."
        case .exam:
            return "Sit a recreation of the old ARRL/FCC code-proficiency exam: a 5-minute QSO-style transmission at 5, 13, or 20 WPM. Pass with one minute of solid copy (25 characters in a row) or by answering questions about what was sent."
        case .qrq:
            return "Push your speed: hear whole words and call signs at 35 or 40 WPM and type what you copy. Too fast to count dits — this trains instant, whole-word recognition (QRQ = “send faster”)."
        case .rapidFire:
            return "Real-world copy drill: a stream of call signs, words, number groups, or state abbreviations sent back to back at whatever pace you choose. Type each one as it lands, send it back on a key, or just copy along and review the full list of what was transmitted at the end."
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
        // Contest picks its own length (the real one-hour event or a sprint) in
        // its setup card, so the generic duration picker would be redundant.
        case .exam, .story, .contest: return false
        default:                      return true
        }
    }

    /// True when starting this mode should prompt for any pre-session options.
    /// Contest always shows its setup card (which contest, how long).
    var needsSetup: Bool { usesStartingLevel || usesSessionLength || self == .contest }
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

    // Journey mode: the live level + progress-bar state the UI renders.
    @Published private(set) var journeyLevelNumber: Int = 1
    @Published private(set) var journeyLevelTitle: String = ""
    @Published private(set) var journeyLevelSection: String = ""
    @Published private(set) var journeyBarProgress: Double = 0   // 0...1
    /// Set briefly when an answer clears a level, so the UI can celebrate.
    @Published private(set) var journeyLevelCleared: Int?
    var journeyTotalLevels: Int { journeyQuiz.levels.count }

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
    let journeyQuiz: JourneyQuiz
    /// Persisted journey unlock/completion state (mirrored into `journeyQuiz`).
    private(set) var journeyProgress = JourneyProgress()
    private var wordsQuiz: PhraseQuiz   // rebuilt when the word tier changes
    private let abbrevQuiz: PhraseQuiz
    private let qCodeQuiz: PhraseQuiz
    private let prosignQuiz: PhraseQuiz
    private let headCopyQuiz: PhraseQuiz
    private let typedQuiz: PhraseQuiz
    private let qrqQuiz: PhraseQuiz
    /// Rebuilt at the start of each Rapid Fire session from the saved config.
    private var rapidFireQuiz: RapidFireQuiz
    private let confusionQuiz: ConfusionQuiz
    private let pileup = PileupEngine()
    private var examSession: ExamSession?
    private var examSampleIndex = 0

    private let player = MorsePlayer()
    private let speech = SpeechPlayer()
    private var toneEndDate: Date?
    private var advanceGeneration = 0

    // Head Copy auto-repeat / timed reveal. `headCopyCountdown` drives the
    // "Revealing in N…" label; `headCopyGeneration` cancels any in-flight
    // repeat/countdown chain when the drill, mode, or session changes.
    @Published private(set) var headCopyCountdown: Int?
    private var headCopyGeneration = 0
    /// Pause between Head Copy auto-replays (after each replay's audio finishes).
    private let headCopyRepeatGap: TimeInterval = 1.5

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
    private static let journeyKey = "MorseTrainer.journey"

    // MARK: - Practice streak (issue #20)

    /// Consecutive-days practice streak, persisted across launches. Read the
    /// live value through `currentStreak` (it self-expires once a day lapses);
    /// `streak.longest` is the all-time record.
    @Published private(set) var streak = PracticeStreak() { didSet { saveStreak() } }
    private static let streakKey = "MorseTrainer.streak"

    /// The streak as it stands today (0 once the learner has let it lapse).
    var currentStreak: Int { streak.display(on: Date()) }
    /// Best streak ever reached — kept even after a lapse.
    var longestStreak: Int { streak.longest }
    /// Set to the milestone day when one is freshly reached this session, for a
    /// one-time celebration in the summary. Cleared when the next session starts.
    @Published var newMilestone: Int?

    /// A reached streak milestone and the emoji that celebrates it.
    struct MilestoneTier { let day: Int; let emoji: String }

    /// The highest milestone reached at `days`, with its badge emoji (nil if none).
    static func milestoneTier(forDay days: Int) -> MilestoneTier? {
        guard let m = PracticeStreak.milestone(forDay: days) else { return nil }
        let emoji: String
        switch m {
        case 365: emoji = "👑"
        case 100: emoji = "🏆"
        case 60:  emoji = "💎"
        case 30:  emoji = "🏅"
        case 14:  emoji = "⚡️"
        case 7:   emoji = "⭐️"
        default:  emoji = "🔥"   // 3-day
        }
        return MilestoneTier(day: m, emoji: emoji)
    }

    // MARK: - Session history (issue #19)

    /// Completed sessions, newest first, persisted across launches.
    @Published private(set) var history = SessionHistory() { didSet { saveHistory() } }
    private static let historyKey = "MorseTrainer.history"
    /// The session just completed, for the post-summary "Session detail" link.
    @Published private(set) var lastSessionRecord: SessionRecord?

    // Per-character tallies for the current session (single-character drills only).
    private var sessionCharTotal: [Character: Int] = [:]
    private var sessionCharCorrect: [Character: Int] = [:]
    private var sessionCharTTRs: [Character: [TimeInterval]] = [:]

    init() {
        let loaded = AppSettings.load()
        self.settings = loaded
        self.engine = TrainerEngine(config: AppModel.config(from: loaded), seedCount: 2)
        self.charLadder = ProgressiveCharacters(engine: engine)
        self.journeyQuiz = JourneyQuiz(scoring: loaded.journeyDrainOnMiss ? .default : .fillOnly,
                                       config: .init(ttrThreshold: loaded.ttrThreshold,
                                                     optionCount: loaded.maxAnswerChoices))
        self.wordsQuiz = PhraseQuiz(name: "Words", items: MorseData.topWordItems(loaded.wordTier.count))
        self.abbrevQuiz = PhraseQuiz(name: "Abbreviations", items: MorseData.abbreviationItems)
        self.qCodeQuiz = PhraseQuiz(name: "Q-Codes", items: MorseData.qCodeItems)
        self.prosignQuiz = PhraseQuiz(name: "Prosigns", items: MorseData.prosignItems)
        self.headCopyQuiz = PhraseQuiz(name: "Head Copy", items: MorseData.wordAndCallSignItems)
        self.typedQuiz = PhraseQuiz(name: "Type It", items: MorseData.wordAndCallSignItems)
        self.qrqQuiz = PhraseQuiz(name: "QRQ", items: MorseData.wordAndCallSignItems)
        self.rapidFireQuiz = RapidFireQuiz(config: AppModel.rapidFireConfig(from: loaded))
        self.confusionQuiz = ConfusionQuiz(engine: engine)
        restoreProgress()
        reconcilePunctuation()
        applyPhraseConfig(from: loaded)
        restoreVoiceProfile()
        streak = AppModel.loadStreak()    // assigning in init doesn't fire didSet
        history = AppModel.loadHistory()
        summary = charLadder.summary
        // Re-arm the daily reminder in case pending requests were cleared.
        if settings.dailyReminderEnabled {
            PracticeReminders.schedule(hour: settings.dailyReminderHour)
        }
    }

    private var source: QuizSource {
        switch mode {
        case .journey:      return journeyQuiz
        case .characters:   return charLadder
        case .words:        return wordsQuiz
        case .abbreviations: return abbrevQuiz
        case .qCodes:       return qCodeQuiz
        case .prosigns:     return prosignQuiz
        case .headCopy:     return headCopyQuiz
        case .typed:        return typedQuiz
        case .confusion:    return confusionQuiz
        case .listen:       return charLadder   // unused: Listen runs its own loop
        case .qso:          return charLadder   // unused: QSO runs its own pileup loop
        case .contest:      return charLadder   // unused: Contest runs the same pileup loop
        case .story:        return charLadder   // unused: Stories run their own playback
        case .exam:         return (examSession as QuizSource?) ?? charLadder   // exam runs its own flow
        case .qrq:          return qrqQuiz
        case .rapidFire:    return rapidFireQuiz
        }
    }

    var isJourney: Bool { mode == .journey }
    var isHeadCopy: Bool { mode == .headCopy }
    var isTyped: Bool { mode == .typed }
    var isListen: Bool { mode == .listen }
    var isQSO: Bool { mode == .qso }
    /// Contest mode: the QSO simulator wired to a specific contest (SST/CWT) with
    /// authentic speeds, a contest clock, and scoring.
    var isContest: Bool { mode == .contest }
    /// Both the free-form QSO simulator and Contest mode run the same pileup engine.
    var usesPileup: Bool { isQSO || isContest }
    var isStory: Bool { mode == .story }
    var isExam: Bool { mode == .exam }
    var isQRQ: Bool { mode == .qrq }
    var isRapidFire: Bool { mode == .rapidFire }
    /// Rapid Fire's hands-off "just listen, review the list at the end" variant,
    /// which streams items on its own loop instead of waiting for an answer.
    var isRapidFireReview: Bool { isRapidFire && settings.rapidFire.response == .review }
    /// Rapid Fire's "type as you hear it" variant: the input box is live while the
    /// code plays, like the QSO simulator.
    var isRapidFireLiveType: Bool { isRapidFire && settings.rapidFire.response == .type }
    /// Rapid Fire's "head copy" variant: the box is hidden while the code plays;
    /// you type the item only after it finishes.
    var isRapidFireHeadType: Bool { isRapidFire && settings.rapidFire.response == .headCopy }
    /// Modes that take a free-typed answer rather than tapping a choice.
    var usesTypedEntry: Bool {
        mode == .typed || mode == .qso || mode == .qrq
            || isRapidFireLiveType || isRapidFireHeadType
    }
    /// Whether the learner answers by *sending* (keying the answer on a physical
    /// or on-screen Morse key) this session (Characters & Words, or Rapid Fire's
    /// "key each one"). Takes precedence over voice when both happen to be enabled.
    var usesKeyingResponse: Bool {
        (settings.keyingResponse && (mode == .characters || mode == .words))
            || (isRapidFire && settings.rapidFire.response == .key)
    }
    /// Whether the learner answers by voice this session (Characters & Words).
    var usesVoiceResponse: Bool {
        settings.voiceResponse && !usesKeyingResponse && (mode == .characters || mode == .words)
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
        // Rebuild the Words quiz when its source changes: a custom list (issue
        // #32) takes precedence over the built-in "Top N" tier.
        let desiredWordItems = s.customWords.isEmpty
            ? MorseData.topWordItems(s.wordTier.count)
            : MorseData.customWordItems(s.customWords)
        if wordsQuiz.items.map(\.id) != desiredWordItems.map(\.id) {
            wordsQuiz = PhraseQuiz(name: "Words", items: desiredWordItems)
        }
        for quiz in [wordsQuiz, abbrevQuiz, qCodeQuiz, prosignQuiz, headCopyQuiz, typedQuiz, qrqQuiz] {
            quiz.config.ttrThreshold = s.ttrThreshold
            quiz.config.optionCount = s.maxAnswerChoices
        }
        journeyQuiz.config.ttrThreshold = s.ttrThreshold
        journeyQuiz.config.optionCount = s.maxAnswerChoices
        journeyQuiz.scoring = s.journeyDrainOnMiss ? .default : .fillOnly
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
        } else if usesPileup {
            stopListening()
            startStory(active: false)
            startQSOMode()
        } else if mode == .rapidFire {
            stopListening()
            startStory(active: false)
            startRapidFire()
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

    // MARK: - QSO Simulator (MorseWalker-style pileup)

    @Published private(set) var qsoActive = false
    @Published private(set) var qsoBusy = false          // a transmission is playing
    @Published private(set) var qsoActiveCount = 0       // stations currently calling
    @Published private(set) var qsoWorkingCall: String?  // station you're working
    @Published private(set) var qsoReadyToLog = false    // exchange copied; send TU
    @Published private(set) var qsoActionLabel = "CQ"    // CQ / Send / TU
    @Published private(set) var qsoLog: [PileupEngine.LoggedQSO] = []
    @Published private(set) var qsoCount = 0
    @Published private(set) var qsoBusts = 0
    @Published private(set) var qsoAccuracy = 1.0
    @Published private(set) var qsoLastLogged: String?   // for a brief "logged!" flash
    private(set) var qsoSessionRate = 0.0                 // frozen rate for the summary
    private var qsoGeneration = 0
    private var qsoStartDate: Date?

    var qsoMode: QSOContestMode { settings.qso.mode }
    /// The exchange the pileup engine is actually running: the chosen contest's
    /// exchange in Contest mode, otherwise the QSO simulator's mode.
    var activePileupMode: QSOContestMode { isContest ? settings.contest.type.qsoMode : settings.qso.mode }

    // MARK: Contest scoring

    /// The contest being emulated this session.
    var contestType: ContestType { settings.contest.type }
    /// Distinct call signs in the log — the CWT multiplier.
    var contestMultipliers: Int { Set(qsoLog.map { $0.call }).count }
    /// Live contest score: QSOs for SST, QSOs × distinct calls for CWT.
    var contestScore: Int { contestType.score(qsoCount: qsoCount, multipliers: contestMultipliers) }

    /// Whether the "?" repeat affordance applies right now.
    var qsoCanRepeat: Bool { qsoActive && qsoActiveCount > 0 }
    /// Completed contacts per hour, for the live rate readout.
    var qsoRate: Double {
        guard let start = qsoStartDate else { return 0 }
        let mins = Date().timeIntervalSince(start) / 60
        return mins > 0.1 ? Double(qsoCount) / mins * 60 : 0
    }

    private func startQSOMode() {
        qsoGeneration += 1
        pileup.reset(config: isContest ? contestConfig() : qsoConfig())
        qsoActive = true
        qsoBusy = false
        qsoStartDate = Date()
        phase = .idle
        refreshQSO()
        summary = pileup.summary
    }

    /// Build the engine config from the saved QSO settings + the operator tone.
    private func qsoConfig() -> PileupConfig {
        let q = settings.qso
        var c = PileupConfig()
        c.mode = q.mode
        c.maxStations = q.mode.isPileup ? max(1, q.maxStations) : 1
        c.minWPM = q.minWPM; c.maxWPM = q.maxWPM
        c.toneSpread = q.toneSpread
        c.minVolume = Float(q.minVolume); c.maxVolume = Float(q.maxVolume)
        c.minDelay = q.minDelay; c.maxDelay = q.maxDelay
        c.qsbEnabled = q.qsbEnabled
        c.qrnLevel = q.qrn.amplitude
        c.cutNumbersEnabled = q.cutNumbersEnabled
        c.cutDigits = Set(q.cutDigits.compactMap { $0.first })
        c.rstRequired = q.rstRequired
        c.bustBehavior = q.bustBehavior
        c.giveUpEnabled = q.giveUpEnabled
        c.formats = q.formats.isEmpty ? CallsignFormat.commonDefaults : Array(q.formats)
        c.usOnly = q.usOnly
        return c
    }

    /// Contest config: start from the shared realism preferences (signals,
    /// callsign shapes, cut numbers) but pin the contest's own exchange and its
    /// authentic speed band, and never require RST (SST/CWT carry none).
    private func contestConfig() -> PileupConfig {
        let contest = settings.contest.type
        var c = qsoConfig()
        c.mode = contest.qsoMode
        c.minWPM = contest.minWPM
        c.maxWPM = contest.maxWPM
        c.maxStations = max(1, settings.qso.maxStations)
        c.rstRequired = false
        return c
    }

    // The single smart box: one action drives CQ / Send / TU by phase. Each turn
    // plays YOUR transmission first (your tone & speed), then the stations reply.
    /// Send the QSO input. Returns whether the input box should be cleared:
    /// normally yes, but with "keep partial call" on (issue #29) a still-hunting
    /// send (no station worked yet) leaves the partial in place so the user can
    /// send "?" and add to it.
    @discardableResult
    func qsoPrimaryAction(_ text: String) -> Bool {
        guard usesPileup else { return true }
        if qsoReadyToLog {
            let action = pileup.logCurrent()
            perform(selfText: "TU \(settings.qso.myCall)", action: action)
            return true
        }
        let pre = pileup.phase
        let action = pileup.send(text)
        perform(selfText: selfSendText(input: text, pre: pre, post: pileup.phase, action: action),
                action: action)
        // `perform` refreshes the published QSO state synchronously, so we can
        // read it here. Still hunting = no station being worked and not ready to
        // log → keep the partial if the user opted in.
        let stillHunting = qsoWorkingCall == nil && !qsoReadyToLog
        return !(settings.qso.keepPartialCall && stillHunting)
    }

    func qsoCQ() {
        guard usesPileup else { return }
        let action = pileup.callCQ()
        perform(selfText: selfCQText(), action: action)
    }

    func qsoRepeat() {
        guard usesPileup else { return }
        perform(selfText: "AGN?", action: pileup.repeatRequest())
    }

    /// Apply an engine action's state effects, then play your side followed by
    /// the stations' reply.
    private func perform(selfText: String?, action: PileupEngine.Action) {
        var loggedContact = false
        if case .logged(let call) = action {
            qsoLastLogged = call
            sessionAttempts += 1
            sessionCorrect += 1
            Haptics.success()
            loggedContact = true
        }
        refreshQSO()

        var response: [MorsePlayer.PileupVoice]
        if case .play(let v) = action { response = v.map(mapVoice) } else { response = [] }
        // After logging a contact, keep the run going: any stations still waiting
        // in the pileup call again on their own, right after your TU, so you can
        // work the next one without having to send AGN first (issue #35).
        if loggedContact, pileup.activeCount > 0,
           case .play(let v) = pileup.repeatRequest() {
            response = v.map(mapVoice)
        }
        let mine = selfText.flatMap { $0.isEmpty ? nil : selfVoice($0) }

        guard mine != nil || !response.isEmpty else { qsoBusy = false; return }
        qsoGeneration += 1
        let gen = qsoGeneration
        qsoBusy = true

        let playReply: () -> Void = { [weak self] in
            guard let self, self.qsoGeneration == gen else { return }
            guard !response.isEmpty else { self.qsoBusy = false; return }
            self.player.playPileup(response, qrn: self.settings.qso.qrn.amplitude) { [weak self] in
                guard let self, self.qsoGeneration == gen else { return }
                self.qsoBusy = false
            }
        }
        if let mine {
            player.playPileup([mine], qrn: 0) { [weak self] in
                guard let self, self.qsoGeneration == gen else { return }
                playReply()
            }
        } else {
            playReply()
        }
    }

    private func mapVoice(_ v: PileupEngine.Voice) -> MorsePlayer.PileupVoice {
        let q = settings.qso
        let timing = q.farnsworth
            ? MorseTiming(characterWpm: v.wpm, effectiveWpm: min(v.wpm, settings.effectiveWpm))
            : MorseTiming(wpm: v.wpm)
        return MorsePlayer.PileupVoice(
            text: v.text,
            frequency: max(200, settings.toneFrequency + v.toneOffset),
            timing: timing,
            gain: v.volume,
            startDelay: v.delay,
            qsbRate: v.qsb ? 0.33 : nil)
    }

    /// Your own transmission: your tone, your speed, full volume, no fading.
    private func selfVoice(_ text: String) -> MorsePlayer.PileupVoice {
        MorsePlayer.PileupVoice(
            text: text,
            frequency: settings.toneFrequency,
            timing: MorseTiming(wpm: settings.wpm),
            gain: 1.0,
            startDelay: 0.05,
            qsbRate: nil)
    }

    private func selfCQText() -> String {
        let me = settings.qso.myCall
        switch activePileupMode {
        case .pota:         return "CQ POTA DE \(me) \(me) K"
        case .basicContest: return "CQ TEST \(me) \(me)"
        case .cwt:          return "CQ CWT \(me)"
        case .sst:          return "CQ SST \(me)"
        case .fieldDay:     return "CQ FD \(me) \(me)"
        case .singleCaller: return "CQ CQ DE \(me) \(me) K"
        }
    }

    /// What you put on the air for a given smart-box send.
    private func selfSendText(input: String,
                              pre: PileupEngine.Phase,
                              post: PileupEngine.Phase,
                              action: PileupEngine.Action) -> String? {
        let t = input.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if PileupEngine.isQRS(t) { return "QRS PSE" }
        if PileupEngine.isQRQ(t) { return "QRQ" }
        if t.isEmpty || PileupEngine.isRepeat(t) { return "AGN?" }
        // You just nailed a full call -> you call them and send your report.
        if case .working = post, !Self.isWorking(pre) {
            return "\(pileup.workingStation?.call ?? PileupEngine.fragment(t)) 5NN"
        }
        // Still working (a miss) -> ask again.
        if case .working = pre, case .working = post { return "AGN?" }
        // Copied it -> a quick roger, no real on-air needed.
        if case .readyToLog = post { return "R" }
        // A partial query into the pileup -> send it with a query mark.
        return t.hasSuffix("?") ? t : t + "?"
    }

    private static func isWorking(_ p: PileupEngine.Phase) -> Bool {
        if case .working = p { return true }
        if case .readyToLog = p { return true }
        return false
    }

    /// Mirror the engine's state onto the published properties the UI watches.
    private func refreshQSO() {
        qsoActiveCount = pileup.activeCount
        qsoLog = pileup.log.reversed()
        qsoCount = pileup.qsoCount
        qsoBusts = pileup.bustCount
        qsoAccuracy = pileup.accuracy
        switch pileup.phase {
        case .idle:
            qsoWorkingCall = nil; qsoReadyToLog = false; qsoActionLabel = "CQ"
        case .pileup:
            qsoWorkingCall = nil; qsoReadyToLog = false; qsoActionLabel = "Send"
        case .working:
            qsoWorkingCall = pileup.workingStation?.call; qsoReadyToLog = false; qsoActionLabel = "Send"
        case .readyToLog:
            qsoWorkingCall = pileup.workingStation?.call; qsoReadyToLog = true; qsoActionLabel = "TU"
        }
        summary = pileup.summary
    }

    // MARK: - Rapid Fire (back-to-back copy)

    /// One transmitted item in a Rapid Fire run, shown in the end-of-session
    /// list. `typed`/`correct` are nil in "just listen" review runs.
    struct RapidFireResult: Identifiable {
        let id = UUID()
        let text: String       // what was sent (and the answer)
        let typed: String?     // what the learner entered (copy/key responses)
        let correct: Bool?
    }

    /// The transmitted list for the current/just-finished Rapid Fire session,
    /// surfaced in the session summary so the learner can check their copy.
    @Published private(set) var rapidFireTranscript: [RapidFireResult] = []
    private var rapidFireGeneration = 0

    /// Build the engine config from the saved Rapid Fire settings.
    private static func rapidFireConfig(from s: AppSettings) -> RapidFireQuiz.Config {
        let r = s.rapidFire
        return RapidFireQuiz.Config(
            content: r.content,
            callsignFormats: r.callsignFormats.isEmpty
                ? CallsignFormat.commonDefaults : Array(r.callsignFormats),
            callsignUSOnly: r.callsignUSOnly,
            wordMinLength: r.wordMinLength,
            wordMaxLength: r.wordMaxLength,
            numberCount: r.numberCount)
    }

    /// Set up a Rapid Fire session: rebuild the generator from current settings,
    /// clear the transcript, then either stream (review) or hand out the first
    /// per-item drill (type / key).
    private func startRapidFire() {
        rapidFireGeneration += 1
        rapidFireTranscript = []
        rapidFireQuiz = RapidFireQuiz(config: AppModel.rapidFireConfig(from: settings))
        if isRapidFireReview {
            startRapidFireReviewLoop()
        } else {
            newDrill()
        }
    }

    /// Hands-off review loop: play one item → reveal it → wait the pace gap →
    /// repeat. Each hop re-checks the generation so ending the session (or
    /// switching modes) cancels the chain cleanly.
    private func startRapidFireReviewLoop() {
        rapidFireGeneration += 1
        rapidFireStep(gen: rapidFireGeneration)
    }

    private func rapidFireStep(gen: Int) {
        guard isRapidFire, gen == rapidFireGeneration, !sessionEnded else { return }
        let item = rapidFireQuiz.nextDrill()
        drill = item
        summary = rapidFireQuiz.summary
        lastTTR = nil
        phase = .playing
        player.play(playable: item.playable,
                    frequency: settings.toneFrequency,
                    timing: timing) { [weak self] in
            guard let self, gen == self.rapidFireGeneration,
                  self.isRapidFire, !self.sessionEnded else { return }
            self.rapidFireTranscript.append(
                RapidFireResult(text: item.correct, typed: nil, correct: nil))
            self.markPracticedToday()
            self.sessionAttempts += 1
            self.phase = .revealed
            let gap = self.settings.rapidFire.pace.seconds
            DispatchQueue.main.asyncAfter(deadline: .now() + gap) {
                guard gen == self.rapidFireGeneration,
                      self.isRapidFire, !self.sessionEnded else { return }
                self.rapidFireStep(gen: gen)
            }
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
        pauseSessionTimer()     // freeze the countdown while paused (issue #37)
        updateNowPlaying()
    }

    func resumeListening() {
        guard isListening, listenPaused else { return }
        listenPaused = false
        resumeSessionTimer()
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
        sessionCharTotal = [:]
        sessionCharCorrect = [:]
        sessionCharTTRs = [:]
        lastSessionRecord = nil
        newMilestone = nil
        sessionTimer?.invalidate()
        sessionTimer = nil

        // Contest mode runs its own clock (the real one-hour event or a sprint);
        // every other mode uses the generic practice-duration picker.
        let sessionSeconds = (learningMode == .contest)
            ? settings.contest.length.seconds
            : settings.practiceDuration.seconds
        if let secs = sessionSeconds {
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

    /// Freeze the practice countdown (e.g. when Listen & Learn is paused) so the
    /// remaining time doesn't bleed away while nothing is playing. No-op for an
    /// untimed session. Resumed by `resumeSessionTimer()` (issue #37).
    private func pauseSessionTimer() {
        guard let end = sessionEndDate, sessionTimer != nil else { return }
        sessionRemaining = max(0, end.timeIntervalSinceNow)
        sessionTimer?.invalidate()
        sessionTimer = nil
        sessionEndDate = nil    // nil while paused; rebuilt on resume
    }

    /// Resume a frozen countdown from the time that was left when it paused.
    private func resumeSessionTimer() {
        guard !sessionEnded, sessionEndDate == nil, sessionTimer == nil,
              settings.practiceDuration.seconds != nil,
              let remaining = sessionRemaining, remaining > 0 else { return }
        sessionEndDate = Date().addingTimeInterval(remaining)
        let timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.sessionTick() }
        }
        sessionTimer = timer
    }

    /// Start (or restart) the countdown with `seconds` left. Shared by
    /// `startSession` and the in-session timer controls.
    private func startCountdown(seconds: TimeInterval) {
        sessionTimer?.invalidate()
        sessionEndDate = Date().addingTimeInterval(seconds)
        sessionRemaining = seconds
        let timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.sessionTick() }
        }
        sessionTimer = timer
    }

    /// Mid-session timer control (issue #41): add time to the running countdown,
    /// or start one if the session was open-ended.
    func addSessionTime(_ seconds: TimeInterval) {
        guard !sessionEnded, seconds > 0 else { return }
        if let end = sessionEndDate {
            let newEnd = end.addingTimeInterval(seconds)
            sessionEndDate = newEnd
            sessionRemaining = max(0, newEnd.timeIntervalSinceNow)
        } else {
            startCountdown(seconds: seconds)
        }
    }

    /// Mid-session timer control (issue #41): shorten the running countdown.
    /// Ends the session if that takes the remaining time to zero.
    func reduceSessionTime(_ seconds: TimeInterval) {
        guard !sessionEnded, seconds > 0, let end = sessionEndDate else { return }
        let newEnd = end.addingTimeInterval(-seconds)
        let remaining = newEnd.timeIntervalSinceNow
        if remaining <= 0 { sessionRemaining = 0; endSession(); return }
        sessionEndDate = newEnd
        sessionRemaining = remaining
    }

    /// Mid-session timer control (issue #41): drop the time limit and keep
    /// practicing open-ended.
    func makeSessionOpenEnded() {
        guard !sessionEnded else { return }
        sessionTimer?.invalidate()
        sessionTimer = nil
        sessionEndDate = nil
        sessionRemaining = nil
    }

    /// True when the running session has a countdown that can be shortened or
    /// removed (vs. an open-ended one, which can only have time added).
    var sessionIsTimed: Bool { sessionRemaining != nil }

    /// Stop the session, cancel any pending auto-advance, and show the summary.
    func endSession() {
        sessionTimer?.invalidate()
        sessionTimer = nil
        sessionEndDate = nil
        advanceGeneration += 1   // cancel any pending auto-advance
        cancelHeadCopyAuto()     // cancel any pending head-copy repeat/reveal
        resetVoiceRound()
        stopListening()
        storyGeneration += 1     // cancel any story playback
        if isStory { player.stop() }
        storyActive = false
        storyPlaying = false
        examGeneration += 1      // cancel any exam playback
        if isExam { player.stop() }
        examPlaying = false
        qsoGeneration += 1       // cancel any pileup playback
        if usesPileup {
            qsoSessionRate = qsoRate   // freeze the rate for the summary
            player.stop()
        }
        qsoBusy = false
        qsoActive = false
        rapidFireGeneration += 1   // cancel any pending Rapid Fire stream
        if isRapidFire { player.stop() }
        phase = .idle
        if let record = buildSessionRecord() {
            history.add(record)            // triggers saveHistory()
            lastSessionRecord = record
        }
        sessionEnded = true
    }

    /// Assemble a `SessionRecord` from the session just finished, or nil if
    /// nothing was answered (don't log empty sessions).
    private func buildSessionRecord() -> SessionRecord? {
        guard sessionAttempts > 0 else { return nil }
        let summary = sessionSummary
        let chars: [SessionRecord.CharResult] = sessionCharTotal.keys.map { ch in
            let ttrs = (sessionCharTTRs[ch] ?? []).sorted()
            let median: TimeInterval?
            if ttrs.isEmpty {
                median = nil
            } else {
                let mid = ttrs.count / 2
                median = ttrs.count % 2 == 0 ? (ttrs[mid - 1] + ttrs[mid]) / 2 : ttrs[mid]
            }
            return SessionRecord.CharResult(
                character: String(ch),
                attempts: sessionCharTotal[ch] ?? 0,
                correct: sessionCharCorrect[ch] ?? 0,
                medianTTR: median)
        }
        // Only attach the active set when we actually have per-character data, so
        // word/QSO/etc. sessions don't render an all-blank chart.
        let active = sessionCharTotal.isEmpty ? [] : engine.activeCharacters.map(String.init)
        let t = timing
        return SessionRecord(
            id: UUID(),
            date: Date(),
            mode: mode.rawValue,
            characterWPM: Int(t.wpm.rounded()),
            effectiveWPM: Int((settings.farnsworth ? settings.effectiveWpm : t.wpm).rounded()),
            attempts: summary.attempts,
            correct: summary.correct,
            fastestTTR: summary.fastest,
            medianTTR: summary.medianTTR,
            durationSeconds: settings.practiceDuration.seconds,
            characters: chars,
            activeCharacters: active)
    }

    /// Add one answered drill to the running session tally. `target` is the
    /// correct answer; when it's a single character we also keep per-character
    /// tallies for the session's recognition-time chart (#19).
    private func noteSessionResult(correct: Bool, ttr: TimeInterval, target: String = "") {
        markPracticedToday()   // any answered drill counts as practicing today
        sessionAttempts += 1
        if correct { sessionCorrect += 1 }
        if ttr > 0 {
            sessionTTRs.append(ttr)
            if correct { sessionFastest = min(sessionFastest ?? .infinity, ttr) }
        }
        if target.count == 1, let ch = target.first {
            sessionCharTotal[ch, default: 0] += 1
            if correct {
                sessionCharCorrect[ch, default: 0] += 1
                if ttr > 0 { sessionCharTTRs[ch, default: []].append(ttr) }
            }
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
        cancelHeadCopyAuto()     // cancel any pending repeat/reveal from the last item
        resetVoiceRound()
        justUnlocked = nil
        lastCorrect = nil
        lastSelected = nil
        lastTTR = nil
        if isJourney { journeyLevelCleared = nil; syncJourneyState() }
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
                else if self.isHeadCopy { self.startHeadCopyAuto() }
            }
        }
    }

    /// Replay without disturbing the TTR clock (optional replay button). Returns
    /// the replayed sound's duration so callers can chain another replay after it.
    @discardableResult
    func replay() -> TimeInterval {
        guard let drill else { return 0 }
        return player.replaySound(playable: drill.playable,
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
        let levelBefore = isJourney ? journeyQuiz.levelNumber : 0
        let outcome = source.record(choice: choice, ttr: ttr)

        lastSelected = choice
        lastCorrect = outcome.correct
        lastTTR = ttr
        justUnlocked = outcome.unlocked
        summary = source.summary
        if isJourney {
            // record() may have advanced to the next level; reflect the new bar/level.
            syncJourneyState()
            if outcome.unlocked != nil {
                journeyLevelCleared = levelBefore
                journeyProgress.clear(level: levelBefore, totalLevels: journeyTotalLevels)
                journeyProgress.currentLevel = journeyQuiz.levelNumber
            }
        }
        phase = .answered
        noteSessionResult(correct: outcome.correct, ttr: ttr, target: drill?.correct ?? "")
        saveProgress()

        // Rapid Fire keeps streaming regardless of right/wrong: log the item and
        // auto-advance at the chosen pace, so the rhythm never stalls. The miss
        // is captured in the transcript for the end-of-session review.
        if isRapidFire {
            rapidFireTranscript.append(
                RapidFireResult(text: drill?.correct ?? "", typed: choice, correct: outcome.correct))
            advanceGeneration += 1
            let token = advanceGeneration
            let gap = settings.rapidFire.pace.seconds
            DispatchQueue.main.asyncAfter(deadline: .now() + gap) { [weak self] in
                guard let self, self.advanceGeneration == token,
                      self.phase == .answered else { return }
                self.next()
            }
            return
        }

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

    /// The characters the learner has studied so far (the active Koch ladder) —
    /// the pool a sending-practice sheet should draw from.
    var studiedCharacters: [Character] { engine.activeCharacters }

    /// Per-character difficulty weights for a personalized sending drill: higher
    /// means "drill this more." Weak (low accuracy) and slow (high TTR relative to
    /// the goal) characters score higher; comfortable ones sit near the floor.
    func sendingDrillWeights() -> [Character: Double] {
        var weights: [Character: Double] = [:]
        let goal = max(0.3, settings.ttrThreshold)
        for stat in characterStats {
            let missPenalty = (1.0 - stat.accuracy) * 4.0          // 0…4
            let slowPenalty = stat.medianTTR.map { min($0 / goal, 3.0) } ?? 1.0
            weights[stat.character] = max(0.5, 0.5 + missPenalty + slowPenalty)
        }
        return weights
    }

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

    // MARK: - Brag sheet (lifetime highlights)

    /// One day in the current week, for the streak strip on the Brag Sheet.
    struct StreakDay: Identifiable {
        let id: Int           // 0 = Monday … 6 = Sunday
        let label: String     // "M", "T", …
        let practiced: Bool
        let isToday: Bool
        let isFuture: Bool
    }

    /// Aggregated lifetime totals and personal bests for the Brag Sheet, derived
    /// from the same persisted session history and per-character stats the rest
    /// of the app uses so the numbers can never drift from reality.
    struct BragStats {
        var currentStreak: Int
        var longestStreak: Int
        var totalSessions: Int
        var totalAnswered: Int
        var accuracy: Double                 // 0…1 over all attempts ever
        var practiceSeconds: TimeInterval
        var fastestCopy: TimeInterval?       // best single recognition time
        var bestSessionAccuracy: Double?     // best "real" session (≥10 drills)
        var biggestSession: Int?             // most drills answered in one sitting
        var charactersMastered: Int
        var charactersTotal: Int
    }

    var bragStats: BragStats {
        let sessions = history.sessions
        let answered = sessions.reduce(0) { $0 + $1.attempts }
        let correct  = sessions.reduce(0) { $0 + $1.correct }
        let seconds  = sessions.reduce(0.0) { $0 + ($1.durationSeconds ?? 0) }
        // A best-accuracy badge from a 3-question session is meaningless, so only
        // count sessions with a meaningful number of drills behind them.
        let realSessions = sessions.filter { $0.attempts >= 10 }
        let mastered = MorseCode.kochOrder.filter {
            engine.stats[$0]?.isMastered(ttrThreshold: settings.ttrThreshold) ?? false
        }.count
        return BragStats(
            currentStreak: currentStreak,
            longestStreak: longestStreak,
            totalSessions: sessions.count,
            totalAnswered: answered,
            accuracy: answered == 0 ? 0 : Double(correct) / Double(answered),
            practiceSeconds: seconds,
            fastestCopy: sessions.compactMap(\.fastestTTR).min(),
            bestSessionAccuracy: realSessions.map(\.accuracy).max(),
            biggestSession: sessions.map(\.attempts).max(),
            charactersMastered: mastered,
            charactersTotal: MorseCode.kochOrder.count)
    }

    /// The current week's practice strip (Mon…Sun) for the streak card: which
    /// days have at least one recorded session, plus today / future markers.
    var streakWeek: [StreakDay] {
        var cal = Calendar.current
        cal.firstWeekday = 2                              // Monday
        let today = cal.startOfDay(for: Date())
        let weekday = cal.component(.weekday, from: today)  // 1=Sun…7=Sat
        let daysFromMonday = (weekday + 5) % 7
        guard let monday = cal.date(byAdding: .day, value: -daysFromMonday, to: today) else { return [] }
        let practiced = Set(history.sessions.map { cal.startOfDay(for: $0.date) })
        let labels = ["M", "T", "W", "T", "F", "S", "S"]
        return (0..<7).compactMap { i in
            guard let day = cal.date(byAdding: .day, value: i, to: monday) else { return nil }
            return StreakDay(id: i, label: labels[i],
                             practiced: practiced.contains(day),
                             isToday: day == today,
                             isFuture: day > today)
        }
    }

    // MARK: - Head copy flow

    /// Head copy: after hearing the word and copying it mentally, reveal the
    /// answer to self-check. The TTR clock captures recall time.
    func revealHeadCopy() {
        guard isHeadCopy, phase == .awaiting, let end = toneEndDate else { return }
        cancelHeadCopyAuto()
        lastTTR = Date().timeIntervalSince(end)
        phase = .revealed
    }

    /// Stop any pending auto-repeat or reveal countdown and clear the label.
    private func cancelHeadCopyAuto() {
        headCopyGeneration += 1
        headCopyCountdown = nil
    }

    /// Kick off Head Copy's structured re-hearing once the first play finishes:
    /// auto-replay the prompt `headCopyRepeats` times, then count down to the
    /// reveal. Each step is guarded by a generation token so an early manual
    /// reveal/repeat, the next drill, or ending the session cancels it cleanly.
    private func startHeadCopyAuto() {
        headCopyGeneration += 1
        headCopyCountdown = nil
        scheduleHeadCopyReplay(remaining: settings.headCopyRepeats, gen: headCopyGeneration)
    }

    private func scheduleHeadCopyReplay(remaining: Int, gen: Int) {
        guard remaining >= 1 else { beginHeadCopyReveal(gen: gen); return }
        DispatchQueue.main.asyncAfter(deadline: .now() + headCopyRepeatGap) { [weak self] in
            guard let self, self.headCopyIsLive(gen) else { return }
            let duration = self.replay()
            DispatchQueue.main.asyncAfter(deadline: .now() + duration) { [weak self] in
                guard let self, self.headCopyIsLive(gen) else { return }
                self.scheduleHeadCopyReplay(remaining: remaining - 1, gen: gen)
            }
        }
    }

    private func beginHeadCopyReveal(gen: Int) {
        let seconds = Int(settings.headCopyRevealSeconds.rounded())
        guard seconds > 0 else { headCopyCountdown = nil; return }   // manual reveal only
        headCopyCountdown = seconds
        countdownToReveal(gen: gen)
    }

    private func countdownToReveal(gen: Int) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            guard let self, self.headCopyIsLive(gen), let n = self.headCopyCountdown else { return }
            if n <= 1 {
                self.headCopyCountdown = nil
                self.revealHeadCopy()
            } else {
                self.headCopyCountdown = n - 1
                self.countdownToReveal(gen: gen)
            }
        }
    }

    /// Replay the prompt now (manual Repeat button): cancel the pending chain,
    /// play it again, then restart the reveal countdown from the top.
    func headCopyRepeatNow() {
        guard isHeadCopy, phase == .awaiting else { return }
        headCopyGeneration += 1
        let gen = headCopyGeneration
        headCopyCountdown = nil
        let duration = replay()
        DispatchQueue.main.asyncAfter(deadline: .now() + duration) { [weak self] in
            guard let self, self.headCopyIsLive(gen) else { return }
            self.beginHeadCopyReveal(gen: gen)
        }
    }

    /// True while a given Head Copy auto chain is still the current one and the
    /// user is still in the copying phase.
    private func headCopyIsLive(_ gen: Int) -> Bool {
        headCopyGeneration == gen && isHeadCopy && phase == .awaiting
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
        if isRapidFire { return false }   // Rapid Fire auto-advances at its pace
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
        if let data = try? JSONEncoder().encode(journeyProgress) {
            UserDefaults.standard.set(data, forKey: Self.journeyKey)
        }
    }

    /// Mirror the quiz's live level/bar into the published UI state.
    private func syncJourneyState() {
        journeyLevelNumber = journeyQuiz.levelNumber
        journeyLevelTitle = journeyQuiz.level.title
        journeyLevelSection = journeyQuiz.level.section
        journeyBarProgress = journeyQuiz.progress
    }

    /// Start the journey on a specific (unlocked) level — used by the level map.
    func selectJourneyLevel(_ number: Int) {
        guard journeyProgress.isUnlocked(level: number),
              let index = journeyQuiz.levels.firstIndex(where: { $0.number == number }) else { return }
        journeyQuiz.select(levelIndex: index)
        journeyProgress.currentLevel = number
        journeyLevelCleared = nil
        syncJourneyState()
        saveProgress()
    }

    private func restoreProgress() {
        if let data = UserDefaults.standard.data(forKey: Self.progressKey) {
            if let snap = try? JSONDecoder().decode(ProgressiveCharacters.Snapshot.self, from: data),
               !snap.engine.activeCharacters.isEmpty {
                charLadder.restore(from: snap)
            } else if let old = try? JSONDecoder().decode(TrainerEngine.Snapshot.self, from: data),
                      !old.activeCharacters.isEmpty {
                engine.restore(from: old)   // migrate older single-stage progress
            }
        }
        if let data = UserDefaults.standard.data(forKey: Self.journeyKey),
           let prog = try? JSONDecoder().decode(JourneyProgress.self, from: data) {
            journeyProgress = prog
            if let index = journeyQuiz.levels.firstIndex(where: { $0.number == prog.currentLevel }) {
                journeyQuiz.select(levelIndex: index)
            }
        }
        syncJourneyState()
    }

    // MARK: - Persistence (practice streak)

    private func saveStreak() {
        if let data = try? JSONEncoder().encode(streak) {
            UserDefaults.standard.set(data, forKey: Self.streakKey)
        }
    }

    private static func loadStreak() -> PracticeStreak {
        guard let data = UserDefaults.standard.data(forKey: streakKey),
              let s = try? JSONDecoder().decode(PracticeStreak.self, from: data) else {
            return PracticeStreak()
        }
        return s
    }

    /// Count today toward the practice streak. Idempotent within a day, so it's
    /// cheap to call on every answered drill.
    private func markPracticedToday() {
        var s = streak
        let before = s.current
        if s.record(on: Date()) {            // only mutate (and persist) on the day's first practice
            streak = s
            if s.current > before, PracticeStreak.isMilestone(s.current) {
                newMilestone = s.current     // celebrated in the session summary
                Haptics.success()
            }
        }
    }

    // MARK: - Daily reminder (issue #20 follow-up)

    /// Turn the daily streak reminder on/off. Enabling asks for notification
    /// permission first; if it's denied we leave the setting off.
    func setDailyReminder(enabled: Bool) {
        if enabled {
            PracticeReminders.requestAuthorization { [weak self] granted in
                guard let self else { return }
                self.settings.dailyReminderEnabled = granted   // didSet persists
                if granted { PracticeReminders.schedule(hour: self.settings.dailyReminderHour) }
            }
        } else {
            settings.dailyReminderEnabled = false
            PracticeReminders.cancel()
        }
    }

    /// Change the hour the reminder fires, rescheduling if it's enabled.
    func setDailyReminderHour(_ hour: Int) {
        settings.dailyReminderHour = hour
        if settings.dailyReminderEnabled { PracticeReminders.schedule(hour: hour) }
    }

    // MARK: - Persistence (session history)

    private func saveHistory() {
        if let data = try? JSONEncoder().encode(history) {
            UserDefaults.standard.set(data, forKey: Self.historyKey)
        }
    }

    private static func loadHistory() -> SessionHistory {
        guard let data = UserDefaults.standard.data(forKey: historyKey),
              let h = try? JSONDecoder().decode(SessionHistory.self, from: data) else {
            return SessionHistory()
        }
        return h
    }

    func resetProgress() {
        UserDefaults.standard.removeObject(forKey: Self.progressKey)
        UserDefaults.standard.removeObject(forKey: Self.journeyKey)
        let fresh = TrainerEngine.Snapshot(
            activeCharacters: Array(MorseCode.kochOrder.prefix(2)), stats: [])
        charLadder.restore(from: .init(engine: fresh, stage: .singles))
        journeyProgress = JourneyProgress()
        journeyQuiz.select(levelIndex: 0)
        syncJourneyState()
        reconcilePunctuation()
        phase = .idle
        drill = nil
    }
}
