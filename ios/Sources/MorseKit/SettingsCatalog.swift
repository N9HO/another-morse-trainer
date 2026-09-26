// SettingsCatalog.swift
// The map of the Settings screen (#236): the categories its root lists, the
// sections each category's sub-screen shows, and the search table the root's
// search field filters. Pure data plus one matching rule, so the harness can
// pin it; the rows themselves are SwiftUI in `SettingsView.swift`.
//
// The Android port carries its own copy (`morsekit/SettingsCatalog.kt`), and
// `fixtures/settings-catalog.json` pins both to the same category names and
// order, the same category for every setting the two apps share, and the
// same answers to a set of search queries. Nothing is shared but that file.
//
// Adding a setting: put its row in the right section of `SettingsView`, then
// add one `SettingsSearchEntry` below, in that section's place in the list.
// If it is on both apps, add its id and category to the fixture too. A new
// section is a new `SettingsSection` case, placed where it should appear in
// its category.

import Foundation

/// The rows on the Settings root, in the order they are listed. Titles are
/// behaviour: the guide and support answers name them, so they match the
/// Android app word for word.
public enum SettingsCategory: String, CaseIterable, Sendable, Identifiable, Hashable {
    case sound, speed, characters, practice, keys, qso, reminders, display, leaderboard, about

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .sound: return "Sound"
        case .speed: return "Speed & Timing"
        case .characters: return "Characters & Lessons"
        case .practice: return "Practice & Feedback"
        case .keys: return "Keys & Sending"
        case .qso: return "QSO & Pileups"
        case .reminders: return "Reminders"
        case .display: return "Display"
        case .leaderboard: return "Leaderboard & Buddy"
        case .about: return "Help & About"
        }
    }
}

/// One titled group of rows on a category's sub-screen. Case order is the
/// order within the category; a search result scrolls to its section.
public enum SettingsSection: String, CaseIterable, Sendable, Hashable {
    case sound
    case speed, farnsworth
    case proficiency, newCharacters, trackStage, punctuation, previewStage, reset
    case learning, feedback, headCopy
    case hardwareKey
    case yourStation, pileupRunner, qsoSignals, qsoRealism, qsoCallsigns
    case reminders
    case display
    case leaderboard, buddy
    case bugReports, about

    public var category: SettingsCategory {
        switch self {
        case .sound: return .sound
        case .speed, .farnsworth: return .speed
        case .proficiency, .newCharacters, .trackStage, .punctuation, .previewStage, .reset: return .characters
        case .learning, .feedback, .headCopy: return .practice
        case .hardwareKey: return .keys
        case .yourStation, .pileupRunner, .qsoSignals, .qsoRealism, .qsoCallsigns: return .qso
        case .reminders: return .reminders
        case .display: return .display
        case .leaderboard, .buddy: return .leaderboard
        case .bugReports, .about: return .about
        }
    }

    /// The sections of one category, in screen order.
    public static func sections(in category: SettingsCategory) -> [SettingsSection] {
        allCases.filter { $0.category == category }
    }
}

/// One searchable setting: what the result row says, the other words a
/// person might type for it, and the section it lives in.
public struct SettingsSearchEntry: Sendable, Identifiable, Hashable {
    public let id: String
    public let title: String
    public let keywords: [String]
    public let section: SettingsSection

    public var category: SettingsCategory { section.category }

    public init(_ id: String, _ title: String, _ keywords: [String], _ section: SettingsSection) {
        self.id = id
        self.title = title
        self.keywords = keywords
        self.section = section
    }
}

public enum SettingsCatalog {
    /// Every searchable setting, in category then section order — the order
    /// results are listed in within a ranking tier.
    public static let entries: [SettingsSearchEntry] = [
        // Sound
        .init("sidetone", "Side tone", ["sidetone", "pitch", "frequency", "hz", "tone", "note"], .sound),
        .init("previewTone", "Preview tone", ["play", "test", "listen", "hear", "sample", "paris"], .sound),
        .init("bluetoothKeepAlive", "Keep Bluetooth audio awake",
              ["earbuds", "headphones", "headset", "airpods", "clipping", "first character", "silence", "keep alive"], .sound),
        .init("bandNoise", "Band noise", ["qrn", "static", "background", "hiss", "noise", "realism"], .sound),

        // Speed & Timing
        .init("speed", "Speed", ["wpm", "words per minute", "character speed", "qrq", "fast", "slow", "koch"], .speed),
        .init("farnsworth", "Farnsworth spacing", ["spacing", "gaps", "slow", "effective", "stretch"], .farnsworth),
        .init("effectiveSpeed", "Effective speed", ["farnsworth", "wpm", "spacing", "overall"], .farnsworth),

        // Characters & Lessons
        .init("proficiency", "Proficiency",
              ["starting level", "i already know", "beginner", "experience", "level", "skip"], .proficiency),
        .init("introduceNew", "Introduce new characters", ["new letter", "first time", "lesson", "meet"], .newCharacters),
        .init("trackStage", "Track stage", ["stage", "pairs", "triples", "words", "hold", "pin", "go back"], .trackStage),
        .init("punctuation", "Punctuation", ["period", "full stop", "comma", "slash", "symbols", "marks"], .punctuation),
        .init("previewStage", "Preview stage (developer)", ["developer", "jump", "stage", "test", "debug"], .previewStage),
        .init("resetProgress", "Reset all progress", ["erase", "clear", "start over", "wipe", "delete", "stats"], .reset),

        // Practice & Feedback
        .init("recognizeWithin", "Recognize within",
              ["recognition target", "time", "seconds", "ttr", "mastered", "threshold", "new letter"], .learning),
        .init("answerChoices", "Answer choices", ["buttons", "options", "multiple choice"], .learning),
        .init("showCorrectness", "Show right / wrong",
              ["correct", "incorrect", "colour", "color", "feedback", "mistakes"], .feedback),
        .init("reveal", "Reveal the letter", ["reveal answer", "show answer", "correct answer"], .feedback),
        .init("showReplay", "Show replay button", ["repeat", "again", "hear again"], .feedback),
        .init("haptics", "Haptic feedback", ["vibration", "vibrate", "buzz", "taptic"], .feedback),
        .init("headCopyRepeats", "Head Copy auto-repeats", ["head copy", "replay", "repeat", "again"], .headCopy),
        .init("headCopyReveal", "Head Copy auto-reveal", ["head copy", "countdown", "reveal", "answer", "seconds"], .headCopy),

        // Keys & Sending
        .init("keyerMode", "Keyer mode",
              ["vail", "adapter", "paddle", "iambic", "straight key", "hardware key", "midi", "key", "bug", "cootie", "mode a", "mode b"],
              .hardwareKey),

        // QSO & Pileups
        .init("myCall", "Your callsign", ["call sign", "call", "station", "my call", "w1aw"], .yourStation),
        .init("exchange", "Pileup Runner mode",
              ["exchange", "pota", "contest", "sprint", "cwt", "sst", "single caller", "mode"], .pileupRunner),
        .init("maxCallers", "Max callers", ["pileup size", "stations", "callers", "how many"], .pileupRunner),
        .init("callerMinSpeed", "Callers' min speed", ["caller speed", "wpm", "slowest"], .pileupRunner),
        .init("callerMaxSpeed", "Callers' max speed", ["caller speed", "wpm", "fastest"], .pileupRunner),
        .init("callerFarnsworth", "Callers' Farnsworth spacing", ["farnsworth", "spacing", "callers"], .pileupRunner),
        .init("toneSpread", "Tone spread", ["zero beat", "pitch", "frequency", "spread"], .pileupRunner),
        .init("qsb", "QSB (fading)", ["fading", "fade", "signal strength", "propagation"], .qsoSignals),
        .init("qsoQrn", "QRN (noise)", ["static", "noise", "crashes", "atmospheric"], .qsoSignals),
        .init("minWait", "Min wait", ["delay", "pause", "reply", "answer time"], .qsoSignals),
        .init("maxWait", "Max wait", ["delay", "pause", "reply"], .qsoSignals),
        .init("rstRequired", "Copy RST too", ["rst", "signal report", "599", "report"], .qsoRealism),
        .init("keepPartialCall", "Keep partial call in box", ["partial", "question mark", "fill"], .qsoRealism),
        .init("keyMySide", "Key my side in Morse", ["transmit", "send", "my side", "cq", "silent"], .qsoRealism),
        .init("autoRecall", "Pileup re-calls after TU", ["recall", "call again", "tu", "agn"], .qsoRealism),
        .init("bustBehavior", "On a busted call", ["bust", "wrong call", "mistake", "correction"], .qsoRealism),
        .init("giveUp", "Callers can give up", ["impatient", "give up", "drop out"], .qsoRealism),
        .init("missedCallerFeedback", "Tell me who got away", ["missed", "lost", "summary"], .qsoRealism),
        .init("cutNumbers", "Cut numbers", ["cut", "abbreviated numbers", "digits", "t for 0", "n for 9"], .qsoRealism),
        .init("usOnly", "US callsigns only", ["dx", "prefix", "foreign", "us", "usa"], .qsoCallsigns),
        .init("callsignFormats", "Callsign formats", ["shape", "1x1", "1x2", "2x3", "format", "prefix"], .qsoCallsigns),

        // Reminders
        .init("dailyReminder", "Daily reminder", ["notification", "nudge", "alert", "streak", "reminder"], .reminders),
        .init("reminderTime", "Remind me at", ["reminder time", "time", "when", "hour", "alarm"], .reminders),

        // Display
        .init("slashedZero", "Slashed zero", ["0", "zero", "letter o", "slash", "font"], .display),

        // Leaderboard & Buddy
        .init("shareScores", "Share scores to the leaderboard",
              ["leaderboard", "post", "upload", "ranking", "high scores", "opt in"], .leaderboard),
        .init("displayName", "Display name", ["name", "callsign", "nickname", "handle"], .leaderboard),
        .init("deleteScores", "Delete my scores", ["remove", "erase", "privacy", "data"], .leaderboard),
        .init("buddyStreak", "Buddy streak", ["buddy", "friend", "partner", "pair", "invite", "join", "code", "streak"], .buddy),

        // Help & About
        .init("copyDiagnostics", "Copy diagnostic info",
              ["bug report", "debug", "support", "version", "logs", "problem"], .bugReports),
        .init("supportProject", "Support the project", ["donate", "tip", "coffee", "support"], .about),
        .init("discord", "Join the Discord", ["community", "chat", "help"], .about),
        .init("sourceCode", "Source on GitHub", ["source code", "github", "open source"], .about),
        .init("licenses", "Licenses", ["licence", "gpl", "mit", "legal", "open source", "notices"], .about),
    ]

    /// The search rule, the same on both apps (and written out in the
    /// fixture's `derivation`): the query and each entry are folded to
    /// lowercase without accents and split into words at anything that is not
    /// a letter or digit. An entry matches when every query word begins some
    /// word of its title, its keywords or its category title. Entries whose
    /// title alone covers every query word come first; otherwise catalog
    /// order. A query with no words matches nothing.
    public static func search(_ query: String,
                              in entries: [SettingsSearchEntry] = SettingsCatalog.entries) -> [SettingsSearchEntry] {
        let tokens = words(query)
        guard !tokens.isEmpty else { return [] }
        func covers(_ haystack: [String]) -> Bool {
            tokens.allSatisfy { token in haystack.contains { $0.hasPrefix(token) } }
        }
        var titleHits: [SettingsSearchEntry] = []
        var otherHits: [SettingsSearchEntry] = []
        for entry in entries {
            let title = words(entry.title)
            if covers(title) {
                titleHits.append(entry)
            } else if covers(title + entry.keywords.flatMap(words) + words(entry.category.title)) {
                otherHits.append(entry)
            }
        }
        return titleHits + otherHits
    }

    /// Lowercased, accent-folded words: runs of letters and digits.
    static func words(_ text: String) -> [String] {
        let folded = text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil).lowercased()
        var out: [String] = []
        var current = ""
        for ch in folded {
            if ch.isLetter || ch.isNumber {
                current.append(ch)
            } else if !current.isEmpty {
                out.append(current)
                current = ""
            }
        }
        if !current.isEmpty { out.append(current) }
        return out
    }
}
