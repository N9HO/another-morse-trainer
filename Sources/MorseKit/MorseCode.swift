import Foundation

/// The Morse code alphabet and helpers for turning characters into the
/// sequence of dits (·) and dahs (−) that get played as sound.
public enum MorseCode {

    /// A single timed element of a Morse character.
    public enum Element: Sendable, Equatable {
        case dit   // short tone  (1 unit)
        case dah   // long tone   (3 units)
    }

    /// Character → element string, using "." for a dit and "-" for a dah.
    /// Letters, digits, and "?" — the base set every learner works through.
    /// ("?" is common enough on the air to belong in the core curriculum.)
    public static let table: [Character: String] = [
        "A": ".-",    "B": "-...",  "C": "-.-.",  "D": "-..",
        "E": ".",     "F": "..-.",  "G": "--.",   "H": "....",
        "I": "..",    "J": ".---",  "K": "-.-",   "L": ".-..",
        "M": "--",    "N": "-.",    "O": "---",   "P": ".--.",
        "Q": "--.-",  "R": ".-.",   "S": "...",   "T": "-",
        "U": "..-",   "V": "...-",  "W": ".--",   "X": "-..-",
        "Y": "-.--",  "Z": "--..",
        "0": "-----", "1": ".----", "2": "..---", "3": "...--",
        "4": "....-", "5": ".....", "6": "-....",  "7": "--...",
        "8": "---..", "9": "----.",
        "?": "..--.."
    ]

    /// Punctuation a learner can opt into beyond the base set. Kept separate so
    /// it only appears when the user explicitly chooses to study it.
    public static let optionalPunctuation: [Character: String] = [
        ",": "--..--",   // comma
        "/": "-..-.",    // slash
        ".": ".-.-.-",   // period
        "=": "-...-"     // the BT prosign / "double dash" used as a CW section
                         // separator (kept out of the UI punctuation picker, but
                         // sendable so exam passages can key authentic BT breaks)
    ]

    /// Pattern lookup across both the base table and optional punctuation.
    private static let allPatterns: [Character: String] =
        table.merging(optionalPunctuation) { base, _ in base }

    /// The classic Koch-method teaching order. New characters are introduced
    /// from the front of this list, one at a time, as the learner speeds up.
    public static let kochOrder: [Character] =
        Array("KMRSUAPTLOWINJEF0YVG5Q9ZH38B?427C1D6X")
            .filter { table[$0] != nil }

    /// All trainable characters (sorted for stable, testable ordering).
    public static let alphabet: [Character] = table.keys.sorted()

    /// The pattern string ("-..-") for a character, or nil if unknown.
    public static func pattern(for character: Character) -> String? {
        allPatterns[Character(String(character).uppercased())]
    }

    /// The timed elements (dit/dah) for a character.
    public static func elements(for character: Character) -> [Element] {
        guard let pattern = pattern(for: character) else { return [] }
        return pattern.map { $0 == "." ? .dit : .dah }
    }
}
