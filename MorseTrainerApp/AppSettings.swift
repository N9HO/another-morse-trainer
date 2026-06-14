import Foundation

/// When to reveal the correct character after an answer.
enum RevealMode: String, Codable, CaseIterable, Identifiable {
    case never      // never show it
    case onWrong    // only when you miss (the default)
    case always     // always show it

    var id: String { rawValue }
    var label: String {
        switch self {
        case .never:   return "Never"
        case .onWrong: return "Only when wrong"
        case .always:  return "Always"
        }
    }
}

/// How much Morse the learner already knows — sets the starting characters.
/// How long a practice session lasts before the trainer stops and shows a
/// summary. `seconds == nil` means open-ended ("until I stop").
enum PracticeDuration: String, Codable, CaseIterable, Identifiable {
    var id: String { rawValue }
    case oneMin, fiveMin, tenMin, fifteenMin, thirtyMin, untilStop

    var seconds: TimeInterval? {
        switch self {
        case .oneMin:    return 60
        case .fiveMin:   return 300
        case .tenMin:    return 600
        case .fifteenMin: return 900
        case .thirtyMin: return 1800
        case .untilStop: return nil
        }
    }

    var label: String {
        switch self {
        case .oneMin:    return "1 minute"
        case .fiveMin:   return "5 minutes"
        case .tenMin:    return "10 minutes"
        case .fifteenMin: return "15 minutes"
        case .thirtyMin: return "30 minutes"
        case .untilStop: return "Until I stop"
        }
    }
}

/// Hands-free "Listen & Learn" delay between the Morse code and the spoken
/// English answer. Mirrors Morse Code Ninja's tiers (Standard → ICR-Territory).
enum AnswerGap: String, Codable, CaseIterable, Identifiable {
    case standard, rapidFire, warp, icr
    var id: String { rawValue }

    var seconds: TimeInterval {
        switch self {
        case .standard:  return 1.3
        case .rapidFire: return 1.0
        case .warp:      return 0.5
        case .icr:       return 0.2
        }
    }

    var label: String {
        switch self {
        case .standard:  return "Standard (1.3 s)"
        case .rapidFire: return "Rapid Fire (1.0 s)"
        case .warp:      return "Warp (0.5 s)"
        case .icr:       return "ICR-Territory (0.2 s)"
        }
    }
}

/// How many of the ranked (ham-weighted, frequency-ordered) words to draw from
/// in Words mode — the QRQ "Top N" tiers.
enum WordTier: String, Codable, CaseIterable, Identifiable {
    case top100, top300, top500
    var id: String { rawValue }
    var count: Int {
        switch self {
        case .top100: return 100
        case .top300: return 300
        case .top500: return 500
        }
    }
    var label: String {
        switch self {
        case .top100: return "Top 100 words"
        case .top300: return "Top 300 words"
        case .top500: return "Top 500 words"
        }
    }
}

/// Character speed for QRQ ("send faster") high-speed copy practice. Above
/// these rates you can no longer count dits — you copy whole words by sound.
enum QrqSpeed: String, Codable, CaseIterable, Identifiable {
    case wpm35, wpm40
    var id: String { rawValue }
    var wpm: Double {
        switch self {
        case .wpm35: return 35
        case .wpm40: return 40
        }
    }
    var label: String {
        switch self {
        case .wpm35: return "35 WPM"
        case .wpm40: return "40 WPM"
        }
    }
}

/// What the hands-free "Listen & Learn" mode announces.
enum ListenContent: String, Codable, CaseIterable, Identifiable {
    case characters, words, abbreviations
    var id: String { rawValue }
    var label: String {
        switch self {
        case .characters:    return "Characters"
        case .words:         return "Words"
        case .abbreviations: return "Abbreviations & Q-codes"
        }
    }
}

enum Proficiency: String, Codable, CaseIterable, Identifiable {
    case none                  // I know nothing
    case someLetters           // I know some of the letters
    case allLetters            // I know all the letters
    case allLettersAndNumbers  // I know all the letters and numbers

    var id: String { rawValue }
    var label: String {
        switch self {
        case .none:                 return "I know nothing"
        case .someLetters:          return "I know some of the letters"
        case .allLetters:           return "I know all the letters"
        case .allLettersAndNumbers: return "I know all the letters and numbers"
        }
    }
}

/// Atmospheric noise (QRN) level for the QSO simulator.
enum QRNLevel: String, Codable, CaseIterable, Identifiable {
    case off, normal, moderate, heavy
    var id: String { rawValue }
    /// White-noise amplitude added across the band.
    var amplitude: Float {
        switch self {
        case .off:      return 0
        case .normal:   return 0.04
        case .moderate: return 0.10
        case .heavy:    return 0.20
        }
    }
    var label: String {
        switch self {
        case .off:      return "Off"
        case .normal:   return "Normal"
        case .moderate: return "Moderate"
        case .heavy:    return "Heavy"
        }
    }
}

/// QSO / contest simulator preferences (MorseWalker-style). Persisted as part of
/// AppSettings; every field has a default so older saves upgrade cleanly.
struct QSOSettings: Codable, Equatable {
    /// Your callsign — sent when you call CQ, work a station, and say TU.
    var myCall: String = "W1AW"
    var mode: QSOContestMode = .pota
    var maxStations: Int = 4
    var minWPM: Double = 18
    var maxWPM: Double = 28
    var farnsworth: Bool = false
    /// Hz of pitch spread across callers (0 = all zero-beat on your tone).
    var toneSpread: Double = 250
    var minVolume: Double = 0.5
    var maxVolume: Double = 1.0
    var minDelay: Double = 0.2
    var maxDelay: Double = 1.5
    var qsbEnabled: Bool = false
    var qrn: QRNLevel = .off
    var cutNumbersEnabled: Bool = false
    var cutDigits: Set<String> = ["0", "9"]
    var rstRequired: Bool = false
    var bustBehavior: BustBehavior = .forgiving
    var giveUpEnabled: Bool = false
    var formats: Set<CallsignFormat> = Set(CallsignFormat.commonDefaults)
    var usOnly: Bool = true
}

// Resilient decoding so adding new QSO fields never wipes a user's saved
// settings (each missing key falls back to its default).
extension QSOSettings {
    enum CodingKeys: String, CodingKey {
        case myCall, mode, maxStations, minWPM, maxWPM, farnsworth, toneSpread
        case minVolume, maxVolume, minDelay, maxDelay, qsbEnabled, qrn
        case cutNumbersEnabled, cutDigits, rstRequired, bustBehavior, giveUpEnabled
        case formats, usOnly
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        var s = QSOSettings()
        s.myCall = try c.decodeIfPresent(String.self, forKey: .myCall) ?? s.myCall
        s.mode = try c.decodeIfPresent(QSOContestMode.self, forKey: .mode) ?? s.mode
        s.maxStations = try c.decodeIfPresent(Int.self, forKey: .maxStations) ?? s.maxStations
        s.minWPM = try c.decodeIfPresent(Double.self, forKey: .minWPM) ?? s.minWPM
        s.maxWPM = try c.decodeIfPresent(Double.self, forKey: .maxWPM) ?? s.maxWPM
        s.farnsworth = try c.decodeIfPresent(Bool.self, forKey: .farnsworth) ?? s.farnsworth
        s.toneSpread = try c.decodeIfPresent(Double.self, forKey: .toneSpread) ?? s.toneSpread
        s.minVolume = try c.decodeIfPresent(Double.self, forKey: .minVolume) ?? s.minVolume
        s.maxVolume = try c.decodeIfPresent(Double.self, forKey: .maxVolume) ?? s.maxVolume
        s.minDelay = try c.decodeIfPresent(Double.self, forKey: .minDelay) ?? s.minDelay
        s.maxDelay = try c.decodeIfPresent(Double.self, forKey: .maxDelay) ?? s.maxDelay
        s.qsbEnabled = try c.decodeIfPresent(Bool.self, forKey: .qsbEnabled) ?? s.qsbEnabled
        s.qrn = try c.decodeIfPresent(QRNLevel.self, forKey: .qrn) ?? s.qrn
        s.cutNumbersEnabled = try c.decodeIfPresent(Bool.self, forKey: .cutNumbersEnabled) ?? s.cutNumbersEnabled
        s.cutDigits = try c.decodeIfPresent(Set<String>.self, forKey: .cutDigits) ?? s.cutDigits
        s.rstRequired = try c.decodeIfPresent(Bool.self, forKey: .rstRequired) ?? s.rstRequired
        s.bustBehavior = try c.decodeIfPresent(BustBehavior.self, forKey: .bustBehavior) ?? s.bustBehavior
        s.giveUpEnabled = try c.decodeIfPresent(Bool.self, forKey: .giveUpEnabled) ?? s.giveUpEnabled
        s.formats = try c.decodeIfPresent(Set<CallsignFormat>.self, forKey: .formats) ?? s.formats
        s.usOnly = try c.decodeIfPresent(Bool.self, forKey: .usOnly) ?? s.usOnly
        self = s
    }
}

/// All user-adjustable preferences. Persisted as JSON in UserDefaults.
struct AppSettings: Codable, Equatable {
    // Audio
    var toneFrequency: Double = 600     // Hz, adjustable live
    var wpm: Double = 33                // character speed
    /// Farnsworth: stretch the spacing between characters (multi-character
    /// content only) down to this effective WPM, while characters stay at `wpm`.
    var farnsworth: Bool = false
    var effectiveWpm: Double = 18

    // Reminders
    /// A daily local notification nudging the learner to keep their streak alive.
    var dailyReminderEnabled: Bool = false
    /// Local hour (0–23) the reminder fires.
    var dailyReminderHour: Int = 19

    // Learning
    var proficiency: Proficiency = .none
    var ttrThreshold: Double = 1.0      // seconds; "fast enough" bar for mastery
    /// The most answer choices to ever show. Choices are always limited to
    /// characters the learner has already met, and the count grows with that
    /// set up to this cap. Baseline is 4; clamped to `answerChoiceRange`.
    var maxAnswerChoices: Int = 4
    /// Optional punctuation the user has opted into studying (e.g. ",", "/", ".").
    var selectedPunctuation: Set<String> = []

    // Session setup
    /// Chosen teaching style for the next session (a TrainingMode rawValue).
    var learningMode: String = "characters"
    /// How long a practice session runs before it stops and shows a summary.
    var practiceDuration: PracticeDuration = .fiveMin

    // Listen & Learn (hands-free)
    /// What the hands-free mode announces.
    var listenContent: ListenContent = .characters
    /// Delay between the code and the spoken answer in hands-free mode.
    var listenGap: AnswerGap = .standard

    /// How big a word pool Words mode (and Listen words) draws from.
    var wordTier: WordTier = .top100

    /// Character speed for QRQ high-speed copy practice (35 or 40 WPM).
    var qrqSpeed: QrqSpeed = .wpm35

    /// Answer by speaking instead of tapping (Characters & Words modes). A
    /// per-session choice made on the setup screen.
    var voiceResponse: Bool = false

    /// Answer by *sending* — keying the answer on a physical (Vail Adapter / BLE
    /// MIDI) or on-screen Morse key, decoded back to text (Characters & Words).
    var keyingResponse: Bool = false

    // Code Exam (ARRL/FCC-style proficiency exam)
    /// License-tied exam speed (5 / 13 / 20 WPM).
    var examSpeed: ExamSpeed = .general13
    /// How the exam is graded: solid copy or content questions.
    var examGrading: ExamGrading = .questions
    /// Use a bundled (ready-made) passage instead of a freshly generated one.
    var examUseBundled: Bool = false

    /// QSO / contest pileup simulator settings.
    var qso = QSOSettings()

    // Feedback (defaults per spec: show right/wrong, reveal only on miss, no replay)
    var showCorrectness: Bool = true
    var reveal: RevealMode = .onWrong
    var allowReplay: Bool = false

    // Head Copy
    /// How many times Head Copy automatically replays the prompt after the first
    /// play, so you can re-hear it without mentally replaying. 0 = no auto-repeat
    /// (a manual Repeat button is always available). Clamped to `headCopyRepeatRange`.
    var headCopyRepeats: Int = 2
    /// Seconds Head Copy waits, after the (auto-)repeats finish, before it reveals
    /// the answer for you to self-check. 0 = manual reveal only (no countdown).
    var headCopyRevealSeconds: Double = 5

    /// Allowed range for `maxAnswerChoices`. The upper bound is a hard stop so
    /// the choice grid stays usable on a phone.
    static let answerChoiceRange: ClosedRange<Int> = 4...6

    /// Allowed range for Head Copy auto-repeats and the reveal countdown.
    static let headCopyRepeatRange: ClosedRange<Int> = 0...3
    static let headCopyRevealRange: ClosedRange<Double> = 0...10

    static let storageKey = "MorseTrainer.settings"

    static func load() -> AppSettings {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode(AppSettings.self, from: data)
        else { return AppSettings() }
        return decoded
    }

    func save() {
        if let data = try? JSONEncoder().encode(self) {
            UserDefaults.standard.set(data, forKey: Self.storageKey)
        }
    }

    /// Optional punctuation offered in Settings: (character, display name).
    static let availablePunctuation: [(symbol: String, name: String)] = [
        (",", "Comma"),
        ("/", "Slash"),
        (".", "Period")
    ]
}

// Resilient decoding: any key missing from older saved data falls back to its
// default, so adding new settings never wipes a user's existing preferences.
// (Declared in an extension to keep the automatic memberwise initializer.)
extension AppSettings {
    enum CodingKeys: String, CodingKey {
        case toneFrequency, wpm, farnsworth, effectiveWpm, proficiency, ttrThreshold
        case dailyReminderEnabled, dailyReminderHour
        case maxAnswerChoices, selectedPunctuation
        case learningMode, practiceDuration
        case listenContent, listenGap, wordTier, voiceResponse, keyingResponse
        case qrqSpeed
        case examSpeed, examGrading, examUseBundled
        case qso
        case showCorrectness, reveal, allowReplay
        case headCopyRepeats, headCopyRevealSeconds
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        var s = AppSettings()
        s.toneFrequency = try c.decodeIfPresent(Double.self, forKey: .toneFrequency) ?? s.toneFrequency
        s.wpm = try c.decodeIfPresent(Double.self, forKey: .wpm) ?? s.wpm
        s.farnsworth = try c.decodeIfPresent(Bool.self, forKey: .farnsworth) ?? s.farnsworth
        s.effectiveWpm = try c.decodeIfPresent(Double.self, forKey: .effectiveWpm) ?? s.effectiveWpm
        s.proficiency = try c.decodeIfPresent(Proficiency.self, forKey: .proficiency) ?? s.proficiency
        s.ttrThreshold = try c.decodeIfPresent(Double.self, forKey: .ttrThreshold) ?? s.ttrThreshold
        s.dailyReminderEnabled = try c.decodeIfPresent(Bool.self, forKey: .dailyReminderEnabled) ?? s.dailyReminderEnabled
        let drh = try c.decodeIfPresent(Int.self, forKey: .dailyReminderHour) ?? s.dailyReminderHour
        s.dailyReminderHour = min(max(drh, 0), 23)
        let mac = try c.decodeIfPresent(Int.self, forKey: .maxAnswerChoices) ?? s.maxAnswerChoices
        s.maxAnswerChoices = min(max(mac, AppSettings.answerChoiceRange.lowerBound),
                                 AppSettings.answerChoiceRange.upperBound)
        s.selectedPunctuation = try c.decodeIfPresent(Set<String>.self, forKey: .selectedPunctuation) ?? s.selectedPunctuation
        s.learningMode = try c.decodeIfPresent(String.self, forKey: .learningMode) ?? s.learningMode
        s.practiceDuration = try c.decodeIfPresent(PracticeDuration.self, forKey: .practiceDuration) ?? s.practiceDuration
        s.listenContent = try c.decodeIfPresent(ListenContent.self, forKey: .listenContent) ?? s.listenContent
        s.listenGap = try c.decodeIfPresent(AnswerGap.self, forKey: .listenGap) ?? s.listenGap
        s.wordTier = try c.decodeIfPresent(WordTier.self, forKey: .wordTier) ?? s.wordTier
        s.qrqSpeed = try c.decodeIfPresent(QrqSpeed.self, forKey: .qrqSpeed) ?? s.qrqSpeed
        s.voiceResponse = try c.decodeIfPresent(Bool.self, forKey: .voiceResponse) ?? s.voiceResponse
        s.keyingResponse = try c.decodeIfPresent(Bool.self, forKey: .keyingResponse) ?? s.keyingResponse
        s.examSpeed = try c.decodeIfPresent(ExamSpeed.self, forKey: .examSpeed) ?? s.examSpeed
        s.examGrading = try c.decodeIfPresent(ExamGrading.self, forKey: .examGrading) ?? s.examGrading
        s.examUseBundled = try c.decodeIfPresent(Bool.self, forKey: .examUseBundled) ?? s.examUseBundled
        s.qso = try c.decodeIfPresent(QSOSettings.self, forKey: .qso) ?? s.qso
        s.showCorrectness = try c.decodeIfPresent(Bool.self, forKey: .showCorrectness) ?? s.showCorrectness
        s.reveal = try c.decodeIfPresent(RevealMode.self, forKey: .reveal) ?? s.reveal
        s.allowReplay = try c.decodeIfPresent(Bool.self, forKey: .allowReplay) ?? s.allowReplay
        let hcr = try c.decodeIfPresent(Int.self, forKey: .headCopyRepeats) ?? s.headCopyRepeats
        s.headCopyRepeats = min(max(hcr, AppSettings.headCopyRepeatRange.lowerBound),
                                AppSettings.headCopyRepeatRange.upperBound)
        let hcrs = try c.decodeIfPresent(Double.self, forKey: .headCopyRevealSeconds) ?? s.headCopyRevealSeconds
        s.headCopyRevealSeconds = min(max(hcrs, AppSettings.headCopyRevealRange.lowerBound),
                                      AppSettings.headCopyRevealRange.upperBound)
        self = s
    }
}
