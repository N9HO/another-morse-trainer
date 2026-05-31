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
    var wpm: Double = 33

    // Learning
    var proficiency: Proficiency = .none
    var ttrThreshold: Double = 1.0      // seconds; "fast enough" bar for mastery
    var distractorsFromFullAlphabet: Bool = true
    /// Optional punctuation the user has opted into studying (e.g. ",", "/", ".").
    var selectedPunctuation: Set<String> = []

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
        case toneFrequency, wpm, proficiency, ttrThreshold
        case distractorsFromFullAlphabet, selectedPunctuation
        case showCorrectness, reveal, allowReplay
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        var s = AppSettings()
        s.toneFrequency = try c.decodeIfPresent(Double.self, forKey: .toneFrequency) ?? s.toneFrequency
        s.wpm = try c.decodeIfPresent(Double.self, forKey: .wpm) ?? s.wpm
        s.proficiency = try c.decodeIfPresent(Proficiency.self, forKey: .proficiency) ?? s.proficiency
        s.ttrThreshold = try c.decodeIfPresent(Double.self, forKey: .ttrThreshold) ?? s.ttrThreshold
        s.distractorsFromFullAlphabet = try c.decodeIfPresent(Bool.self, forKey: .distractorsFromFullAlphabet) ?? s.distractorsFromFullAlphabet
        s.selectedPunctuation = try c.decodeIfPresent(Set<String>.self, forKey: .selectedPunctuation) ?? s.selectedPunctuation
        s.showCorrectness = try c.decodeIfPresent(Bool.self, forKey: .showCorrectness) ?? s.showCorrectness
        s.reveal = try c.decodeIfPresent(RevealMode.self, forKey: .reveal) ?? s.reveal
        s.allowReplay = try c.decodeIfPresent(Bool.self, forKey: .allowReplay) ?? s.allowReplay
        self = s
    }
}
