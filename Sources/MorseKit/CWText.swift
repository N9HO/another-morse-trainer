import Foundation

/// Turns arbitrary "real world" text (news headlines, RSS summaries, pasted
/// prose) into text this trainer can actually key: uppercase letters, digits,
/// and the small set of punctuation in `MorseCode`. The sanitized string is
/// both what gets sent and what gets revealed, so the learner's copy can be
/// checked character for character against what was played.
public enum CWText {

    // MARK: - Sendability

    /// True when the player can key this character (space separates words and
    /// counts as sendable). Safe on any character, including ones like "ß"
    /// whose uppercase form is more than one letter.
    public static func isSendable(_ ch: Character) -> Bool {
        if ch == " " { return true }
        let up = String(ch).uppercased()
        guard up.count == 1, let u = up.first else { return false }
        return MorseCode.pattern(for: u) != nil
    }

    /// True when every character of `text` can be keyed as-is.
    public static func isFullySendable(_ text: String) -> Bool {
        text.allSatisfy { isSendable($0) }
    }

    // MARK: - HTML stripping (RSS descriptions)

    /// Removes markup tags and decodes character entities, leaving plain prose.
    /// RSS descriptions arrive either as escaped HTML ("&lt;p&gt;…") or as raw
    /// tags inside CDATA, so entities are decoded both before and after the
    /// tags are dropped.
    public static func strippedHTML(_ raw: String) -> String {
        var s = decodedEntities(raw)
        var out = ""
        out.reserveCapacity(s.count)
        var inTag = false
        for ch in s {
            if ch == "<" { inTag = true; continue }
            if ch == ">" { if inTag { inTag = false; out.append(" ") } else { out.append(ch) }; continue }
            if !inTag { out.append(ch) }
        }
        s = decodedEntities(out)
        return s
    }

    /// The named entities that actually show up in news feeds, plus numeric
    /// forms ("&#8217;", "&#x2019;"). Unknown entities are left untouched.
    private static let namedEntities: [String: String] = [
        "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'",
        "nbsp": " ", "ndash": "\u{2013}", "mdash": "\u{2014}",
        "hellip": "\u{2026}", "lsquo": "\u{2018}", "rsquo": "\u{2019}",
        "ldquo": "\u{201C}", "rdquo": "\u{201D}",
        "copy": "", "reg": "", "trade": "", "shy": ""
    ]

    private static func decodedEntities(_ s: String) -> String {
        guard s.contains("&") else { return s }
        var out = ""
        out.reserveCapacity(s.count)
        var i = s.startIndex
        while i < s.endIndex {
            if s[i] == "&",
               let semi = s[i..<s.endIndex].firstIndex(of: ";"),
               s.distance(from: i, to: semi) <= 9 {
                let body = String(s[s.index(after: i)..<semi])
                if let replacement = namedEntities[body.lowercased()] {
                    out += replacement
                    i = s.index(after: semi)
                    continue
                }
                if body.hasPrefix("#") {
                    let digits = body.dropFirst()
                    let value: UInt32? = digits.hasPrefix("x") || digits.hasPrefix("X")
                        ? UInt32(digits.dropFirst(), radix: 16)
                        : UInt32(digits)
                    if let v = value, let scalar = Unicode.Scalar(v) {
                        out.append(Character(scalar))
                        i = s.index(after: semi)
                        continue
                    }
                }
            }
            out.append(s[i])
            i = s.index(after: i)
        }
        return out
    }

    // MARK: - Sanitizing to the sendable set

    /// Typographic characters folded to plain ASCII before the main pass.
    private static let typographic: [Character: String] = [
        "\u{2018}": "'", "\u{2019}": "'", "\u{201A}": "'", "\u{02BC}": "'", "`": "'", "\u{00B4}": "'",
        "\u{201C}": "\"", "\u{201D}": "\"", "\u{201E}": "\"", "\u{00AB}": "\"", "\u{00BB}": "\"",
        "\u{2013}": "-", "\u{2014}": "-", "\u{2015}": "-", "\u{2212}": "-",
        "\u{2026}": "...",
        "\u{00A0}": " ", "\u{2007}": " ", "\u{2009}": " ", "\u{200A}": " ", "\u{202F}": " ",
        "\u{200B}": "", "\u{FEFF}": "",
        // Letters that diacritic folding leaves alone.
        "\u{00D8}": "O", "\u{00F8}": "o", "\u{00C6}": "AE", "\u{00E6}": "ae",
        "\u{0152}": "OE", "\u{0153}": "oe", "\u{00DF}": "ss",
        "\u{00D0}": "D", "\u{00F0}": "d", "\u{00DE}": "TH", "\u{00FE}": "th",
        "\u{0141}": "L", "\u{0142}": "l", "\u{0110}": "D", "\u{0111}": "d"
    ]

    /// Reduces `raw` to uppercase text made only of characters the player can
    /// key (letters, digits, space, and . , / ? =). Symbols with a spoken CW
    /// convention are spelled out (& → AND); everything unmappable becomes a
    /// word break. Idempotent on already-clean text apart from uppercasing.
    public static func sanitized(_ raw: String) -> String {
        // 1) Typographic → ASCII, then fold accents (é → e) and uppercase.
        var pre = ""
        pre.reserveCapacity(raw.count)
        for ch in raw {
            if let mapped = typographic[ch] { pre += mapped } else { pre.append(ch) }
        }
        let folded = pre
            .folding(options: [.diacriticInsensitive, .widthInsensitive],
                     locale: Locale(identifier: "en_US_POSIX"))
            .uppercased()

        // 2) Character-by-character mapping into the sendable set.
        let chars = Array(folded)
        var out = ""
        out.reserveCapacity(chars.count)
        for (i, ch) in chars.enumerated() {
            switch ch {
            case "A"..."Z", "0"..."9":
                out.append(ch)
            case ",":
                // "1,234" is keyed as 1234 — drop the thousands separator.
                let prevDigit = i > 0 && chars[i - 1].isNumber
                let nextDigit = i + 1 < chars.count && chars[i + 1].isNumber
                if !(prevDigit && nextDigit) { out.append(ch) }
            case ".", "/", "?", "=":
                out.append(ch)
            case "'":
                break                       // DON'T → DONT, house style
            case ":", ";":
                out.append(",")
            case "!":
                out.append(".")
            case "&":
                out += " AND "
            case "+":
                out += " PLUS "
            case "%":
                out += " PERCENT "
            case "@":
                out += " AT "
            case "$", "\u{00A3}", "\u{20AC}", "\u{00A5}":
                break                       // currency marks read fine without
            default:
                out.append(" ")             // hyphens, quotes, anything else
            }
        }

        // 3) Tidy up: collapse spaces, reattach punctuation, squash runs.
        var t = out.split(separator: " ", omittingEmptySubsequences: true)
            .joined(separator: " ")
        for p in [".", ",", "?"] {
            while t.contains(" " + p) {
                t = t.replacingOccurrences(of: " " + p, with: p)
            }
        }
        for run in ["..", ",,", ",.", ".,", "?.", "?,"] {
            while t.contains(run) {
                t = t.replacingOccurrences(of: run, with: String(run.first!))
            }
        }
        while let first = t.first, first == "." || first == "," {
            t = String(t.dropFirst()).trimmingCharacters(in: .whitespaces)
        }
        return t.trimmingCharacters(in: .whitespaces)
    }

    /// Clips `text` to at most `maxWords` words, preferring to end at a
    /// sentence boundary so a trimmed news summary still reads whole.
    public static func clipped(_ text: String, maxWords: Int) -> String {
        let words = text.split(separator: " ")
        guard words.count > maxWords else { return text }
        let kept = words.prefix(maxWords)
        // Cut back to the last sentence end, if one lands in the kept range.
        if let lastStop = kept.lastIndex(where: { $0.hasSuffix(".") || $0.hasSuffix("?") }),
           kept.distance(from: kept.startIndex, to: lastStop) >= maxWords / 3 {
            return kept[...lastStop].joined(separator: " ")
        }
        var body = kept.joined(separator: " ")
        while let last = body.last, last == "." || last == "," { body.removeLast() }
        return body + "."
    }
}
