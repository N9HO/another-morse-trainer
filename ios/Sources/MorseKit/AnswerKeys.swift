import Foundation

/// Which keyboard key answers which multiple-choice option (issue #69), and
/// the rule that keeps a digit you *heard* from being read as a position
/// (android#30).
///
/// The original scheme had two modes: when every option was a single
/// character, each option was answered by its own key; otherwise the options
/// were numbered 1–9 by position. The mode was chosen for the drill as a
/// whole, so a single multi-character distractor — a prosign, an
/// abbreviation, anything from a later Journey level's cumulative pool —
/// flipped the *entire* grid to positional numbering. In a drill that sent
/// numbers that was silently wrong: you heard `4`, pressed `4`, and answered
/// whatever happened to sit fourth in the list. With the options in order
/// that reads as a consistent off-by-one.
///
/// So the mode is now per option, not per drill, and value always beats
/// position: an option that *is* a single character is answered by that
/// character, and only the options that have no character of their own fall
/// back to a digit. Digits already spoken for by a single-character option
/// are never handed out as positions, so no two options share a key.
public enum AnswerKeys {

    /// Digits offered as positional answers, in the order they're handed out.
    private static let positionDigits: [Character] = Array("123456789")

    /// The key that answers each option, aligned with `options`. `nil` means
    /// the option has no key — there were more options than free digits.
    ///
    /// Keys are lowercase; match against a pressed key with ``option(for:in:)``.
    public static func assign(_ options: [String]) -> [Character?] {
        var keys = [Character?](repeating: nil, count: options.count)
        var taken = Set<Character>()

        // Pass 1: an option that is one character answers to its own key.
        for (index, option) in options.enumerated() {
            guard option.count == 1, let key = option.lowercased().first else { continue }
            if taken.insert(key).inserted { keys[index] = key }
        }

        // Pass 2: everything else takes the next digit nobody has claimed.
        var free = positionDigits.filter { !taken.contains($0) }
        for index in options.indices where keys[index] == nil {
            guard !free.isEmpty else { break }
            keys[index] = free.removeFirst()
        }
        return keys
    }

    /// The index of the option answered by `key`, or `nil` when the key
    /// answers nothing in this drill. Case-insensitive.
    public static func option(for key: Character, in options: [String]) -> Int? {
        guard let wanted = key.lowercased().first else { return nil }
        return assign(options).firstIndex(of: wanted)
    }

    /// True when `options[index]` is answered by a positional digit rather
    /// than by its own text — the only case where the 1–9 hint is worth
    /// showing, since a single-character option already *is* its own key.
    public static func needsPositionHint(_ options: [String], at index: Int) -> Bool {
        guard options.indices.contains(index) else { return false }
        return options[index].count != 1
    }
}
