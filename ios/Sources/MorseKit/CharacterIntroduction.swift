import Foundation

/// What to show a learner the first time the Characters track presents an
/// item (issue #162): the character or prosign on its own, its pattern, and how
/// that pattern is voiced — before it is ever mixed into a drill, where the
/// only way to learn a new sound was to guess wrong at it.
public struct CharacterIntroduction: Sendable, Equatable {
    /// "K" or "<AR>" — what the drill's `correct` answer will be.
    public let id: String
    /// What to show large.
    public let display: String
    /// The dot-dash pattern, run together for a prosign.
    public let pattern: String
    /// A prosign's meaning; nil for a plain character.
    public let meaning: String?
    /// What to sound out, as the drill will send it.
    public let playable: MorseItem.Playable

    public var isProsign: Bool { meaning != nil }
    /// "dah-di-dah".
    public var spokenPattern: String { Self.spokenPattern(pattern) }
    /// "− · −".
    public var symbolPattern: String { Self.symbolPattern(pattern) }

    public init(id: String, display: String, pattern: String, meaning: String?,
                playable: MorseItem.Playable) {
        self.id = id
        self.display = display
        self.pattern = pattern
        self.meaning = meaning
        self.playable = playable
    }

    /// The introduction `drill` calls for, or nil when there is nothing to
    /// introduce: a group, a word, or a single item `isMet` vouches for. Only
    /// single characters and prosigns are ever introduced — they are the only
    /// things the track presents that have a sound of their own to learn.
    public static func forDrill(_ drill: Drill,
                                isMet: (String) -> Bool) -> CharacterIntroduction? {
        let intro: CharacterIntroduction
        if drill.correct.count == 1, let ch = drill.correct.first,
           let pattern = MorseCode.pattern(for: ch) {
            let s = String(ch).uppercased()
            intro = CharacterIntroduction(id: s, display: s, pattern: pattern,
                                          meaning: nil, playable: .text(s))
        } else if let prosign = MorseData.prosigns.first(where: { $0.name == drill.correct }) {
            intro = CharacterIntroduction(id: prosign.name, display: prosign.name,
                                          pattern: prosign.pattern, meaning: prosign.meaning,
                                          playable: .pattern(prosign.pattern))
        } else {
            return nil
        }
        return isMet(intro.id) ? nil : intro
    }

    /// The pattern as an operator says it: every dit but the last is "di", the
    /// last is "dit", a dah is always "dah" — so K is "dah-di-dah" and S is
    /// "di-di-dit". Pinned for both ports in `fixtures/introduction.json`.
    public static func spokenPattern(_ pattern: String) -> String {
        let elements = Array(pattern)
        return elements.enumerated().map { index, element -> String in
            if element == "-" { return "dah" }
            return index == elements.count - 1 ? "dit" : "di"
        }.joined(separator: "-")
    }

    /// The pattern spaced out for reading, with a real minus sign (U+2212) so
    /// a dah does not render as a hyphen next to a dot.
    public static func symbolPattern(_ pattern: String) -> String {
        pattern.map { $0 == "-" ? "\u{2212}" : "\u{00B7}" }.joined(separator: " ")
    }
}
