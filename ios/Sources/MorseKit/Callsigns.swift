import Foundation

/// Amateur callsign shapes, expressed as "(prefix letters) × (suffix letters)".
/// US calls are `prefix + single region digit + suffix`, e.g. `K1ABC` is 1×3.
public enum CallsignFormat: String, Codable, CaseIterable, Identifiable, Sendable {
    case oneByOne      // K1A      (special event)
    case oneByTwo      // K1AB
    case twoByOne      // AB1C
    case oneByThree    // K1ABC
    case twoByTwo      // AB1CD
    case twoByThree    // AB1CDE

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .oneByOne:   return "1×1"
        case .oneByTwo:   return "1×2"
        case .twoByOne:   return "2×1"
        case .oneByThree: return "1×3"
        case .twoByTwo:   return "2×2"
        case .twoByThree: return "2×3"
        }
    }

    var prefixLen: Int {
        switch self {
        case .oneByOne, .oneByTwo, .oneByThree: return 1
        case .twoByOne, .twoByTwo, .twoByThree: return 2
        }
    }

    var suffixLen: Int {
        switch self {
        case .oneByOne, .twoByOne:   return 1
        case .oneByTwo, .twoByTwo:   return 2
        case .oneByThree, .twoByThree: return 3
        }
    }

    /// A sensible default set: the everyday formats on, the rare ones off.
    public static let commonDefaults: [CallsignFormat] = [.oneByTwo, .twoByOne, .oneByThree, .twoByTwo]
}

/// Procedurally generates realistic callsigns so pileups vary widely (and
/// substring matching is meaningful). Pure logic — seedable for tests.
public enum CallsignGenerator {
    private static let letters = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    private static let digits  = Array("0123456789")
    // US single-letter prefixes actually issued.
    private static let usSingle = Array("KNW")
    // US two-letter prefix first letters.
    private static let usFirst  = Array("AKNW")
    // A few common DX prefixes, for the optional worldwide pool.
    private static let dxPrefixes = ["DL","G","GM","F","ON","PA","EA","I","SM","LA","OE","HB","JA",
                                     "VK","ZL","VE","SP","OK","LZ","YO","UR","RA","UA","9A","OZ","EI"]

    /// One random callsign in one of the allowed `formats`. `usOnly` restricts
    /// to US-style calls; otherwise DX prefixes are mixed in.
    public static func generate<R: RandomNumberGenerator>(
        formats: [CallsignFormat],
        usOnly: Bool,
        using rng: inout R
    ) -> String {
        let fmt = formats.randomElement(using: &rng) ?? .oneByTwo
        if usOnly || Bool.random(using: &rng) {
            return usCall(fmt, using: &rng)
        } else {
            return dxCall(fmt, using: &rng)
        }
    }

    private static func usCall<R: RandomNumberGenerator>(_ fmt: CallsignFormat, using rng: inout R) -> String {
        var s = ""
        if fmt.prefixLen == 1 {
            s.append(usSingle.randomElement(using: &rng)!)
        } else {
            let first = usFirst.randomElement(using: &rng)!
            s.append(first)
            // 'A' prefixes only run AA–AL; the rest take any second letter.
            let second = first == "A" ? Array("ABCDEFGHIJKL").randomElement(using: &rng)!
                                       : letters.randomElement(using: &rng)!
            s.append(second)
        }
        s.append(digits.randomElement(using: &rng)!)
        for _ in 0..<fmt.suffixLen { s.append(letters.randomElement(using: &rng)!) }
        return s
    }

    private static func dxCall<R: RandomNumberGenerator>(_ fmt: CallsignFormat, using rng: inout R) -> String {
        let prefix = dxPrefixes.randomElement(using: &rng)!
        var s = prefix
        s.append(digits.randomElement(using: &rng)!)
        // DX suffixes run 1–3 letters; reuse the format's suffix length.
        for _ in 0..<max(2, fmt.suffixLen) { s.append(letters.randomElement(using: &rng)!) }
        return s
    }
}

/// Contest "cut numbers": numerals shortened to letters (T=0, N=9, …) to save
/// time. We always grade against the real digits, accepting either the digit or
/// its cut letter typed back.
public enum CutNumbers {
    /// Standard digit → cut-letter map. Only the digits in the active set are
    /// actually substituted when sending.
    public static let map: [Character: Character] = [
        "0": "T", "1": "A", "2": "U", "3": "V", "5": "E", "7": "G", "8": "D", "9": "N"
    ]
    /// Reverse map for normalizing typed input back to digits.
    public static let reverse: [Character: Character] = [
        "T": "0", "A": "1", "U": "2", "V": "3", "E": "5", "G": "7", "D": "8", "N": "9"
    ]
    /// The digits that can be cut (for the settings UI).
    public static let cuttableDigits: [Character] = ["0", "1", "2", "3", "5", "7", "8", "9"]
    /// A reasonable default: the two everyone actually uses on the air.
    public static let commonDefaults: Set<Character> = ["0", "9"]

    /// Replace each digit in `text` with its cut letter when that digit is in
    /// `enabled`. Non-digits pass through untouched.
    public static func encode(_ text: String, enabled: Set<Character>) -> String {
        String(text.map { ch in
            (enabled.contains(ch) ? map[ch] : nil) ?? ch
        })
    }

    /// Normalize typed input for numeric comparison: cut letters become digits.
    public static func decodeDigits(_ text: String) -> String {
        String(text.uppercased().compactMap { ch -> Character? in
            if ch.isNumber { return ch }
            if let d = reverse[ch] { return d }
            return nil
        })
    }
}
