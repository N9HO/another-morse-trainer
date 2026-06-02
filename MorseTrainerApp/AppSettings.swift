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

/// All user-adjustable preferences. Persisted as JSON in UserDefaults.
struct AppSettings: Codable, Equatable {
    // Audio
    var toneFrequency: Double = 600     // Hz, adjustable live
    var wpm: Double = 33                // character speed
    /// Farnsworth: stretch the spacing between characters (multi-character
    /// content only) down to this effective WPM, while characters stay at `wpm`.
    var farnsworth: Bool = false
    var effectiveWpm: Double = 18

    // Learning
    var proficiency: Proficiency = .none
    var ttrThreshold: Double = 1.0      // seconds; "fast enough" bar for mastery
    var distractorsFromFullAlphabet: Bool = true
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

    /// Answer by speaking instead of tapping (Characters & Words modes). A
    /// per-session choice made on the setup screen.
    var voiceResponse: Bool = false

    // Feedback (defaults per spec: show right/wrong, reveal only on miss, no replay)
    var showCorrectness: Bool = true
    var reveal: RevealMode = .onWrong
    var allowReplay: Bool = false

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
        case distractorsFromFullAlphabet, selectedPunctuation
        case learningMode, practiceDuration
        case listenContent, listenGap, wordTier, voiceResponse
        case showCorrectness, reveal, allowReplay
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
        s.distractorsFromFullAlphabet = try c.decodeIfPresent(Bool.self, forKey: .distractorsFromFullAlphabet) ?? s.distractorsFromFullAlphabet
        s.selectedPunctuation = try c.decodeIfPresent(Set<String>.self, forKey: .selectedPunctuation) ?? s.selectedPunctuation
        s.learningMode = try c.decodeIfPresent(String.self, forKey: .learningMode) ?? s.learningMode
        s.practiceDuration = try c.decodeIfPresent(PracticeDuration.self, forKey: .practiceDuration) ?? s.practiceDuration
        s.listenContent = try c.decodeIfPresent(ListenContent.self, forKey: .listenContent) ?? s.listenContent
        s.listenGap = try c.decodeIfPresent(AnswerGap.self, forKey: .listenGap) ?? s.listenGap
        s.wordTier = try c.decodeIfPresent(WordTier.self, forKey: .wordTier) ?? s.wordTier
        s.voiceResponse = try c.decodeIfPresent(Bool.self, forKey: .voiceResponse) ?? s.voiceResponse
        s.showCorrectness = try c.decodeIfPresent(Bool.self, forKey: .showCorrectness) ?? s.showCorrectness
        s.reveal = try c.decodeIfPresent(RevealMode.self, forKey: .reveal) ?? s.reveal
        s.allowReplay = try c.decodeIfPresent(Bool.self, forKey: .allowReplay) ?? s.allowReplay
        self = s
    }
}
