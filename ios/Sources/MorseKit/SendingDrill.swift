import Foundation

/// Generates printable *sending* practice sheets: pages of random character
/// groups you read aloud on the air or key on a paddle. This is the send-side
/// companion to the recognition quizzes — the app can't grade your fist, so the
/// value is a fresh, well-mixed sheet drawn from exactly the characters you've
/// studied (optionally weighted toward your weak ones), ready to share or print.
///
/// Mirrors the "Sending Drills" feature on cwsignals.com — basic drills from the
/// letters you know, a personalized drill that leans on your weak spots, and a
/// numbers-and-punctuation drill — but built from this app's own progress.
public struct SendingDrill: Sendable, Equatable {

    /// Which pool the groups are drawn from.
    public enum Kind: String, CaseIterable, Sendable, Identifiable {
        /// Even mix of the letters/characters you've studied.
        case studied
        /// Studied characters, weighted toward the ones you answer slowest or
        /// least accurately, so practice lands where it helps most.
        case personalized
        /// Digits and common CW punctuation — the characters drills usually skip.
        case numbersAndPunctuation

        public var id: String { rawValue }
        public var title: String {
            switch self {
            case .studied:               return "Studied"
            case .personalized:          return "Personalized"
            case .numbersAndPunctuation: return "Numbers & punctuation"
            }
        }
        public var blurb: String {
            switch self {
            case .studied:
                return "An even mix of every character you've learned so far."
            case .personalized:
                return "Weighted toward the characters you're slowest or least accurate on."
            case .numbersAndPunctuation:
                return "Digits and the punctuation that recognition drills tend to skip."
            }
        }
    }

    /// The digits and punctuation used by the numbers-and-punctuation drill —
    /// the marks an operator actually sends (period, comma, query, slash, the
    /// `=` break, plus/`AR`), kept to what's worth practising.
    public static let numberPunctuationPool: [Character] =
        Array("0123456789") + Array(".,?/=+")

    /// One generated sheet: rows of space-separated groups, plus the parameters
    /// used so the header can describe it.
    public let kind: Kind
    public let groupSize: Int
    public let rows: [String]

    public init(kind: Kind, groupSize: Int, rows: [String]) {
        self.kind = kind
        self.groupSize = groupSize
        self.rows = rows
    }

    /// Build a sheet.
    ///
    /// - Parameters:
    ///   - kind: which pool to draw from.
    ///   - studied: the characters the learner has studied (used by `.studied`
    ///     and `.personalized`).
    ///   - weights: per-character difficulty weights (higher = drill more), used
    ///     only by `.personalized`. Missing characters default to weight 1.
    ///   - groupCount: how many groups to emit (e.g. 50).
    ///   - groupSize: characters per group (classic CW practice uses 5).
    ///   - groupsPerRow: how many groups to lay out per line.
    ///   - rng: random source (injectable for testing).
    public static func generate(kind: Kind,
                                studied: [Character],
                                weights: [Character: Double] = [:],
                                groupCount: Int = 50,
                                groupSize: Int = 5,
                                groupsPerRow: Int = 5,
                                using rng: inout some RandomNumberGenerator) -> SendingDrill {
        let pool: [Character]
        switch kind {
        case .studied, .personalized:
            pool = studied.isEmpty ? Array("ETIANMSURWDKGO") : studied
        case .numbersAndPunctuation:
            pool = numberPunctuationPool
        }

        // For personalized drills, build a weighted bag so weak characters appear
        // proportionally more often; otherwise every character is equally likely.
        let weighted = kind == .personalized
            ? pool.map { ($0, max(0.1, weights[$0] ?? 1)) }
            : pool.map { ($0, 1.0) }

        func pick() -> Character {
            let total = weighted.reduce(0) { $0 + $1.1 }
            var t = Double.random(in: 0..<total, using: &rng)
            for (ch, w) in weighted {
                t -= w
                if t < 0 { return ch }
            }
            return weighted.last?.0 ?? "E"
        }

        let size = max(1, groupSize)
        var groups: [String] = []
        groups.reserveCapacity(max(0, groupCount))
        for _ in 0..<max(0, groupCount) {
            groups.append(String((0..<size).map { _ in pick() }))
        }

        let perRow = max(1, groupsPerRow)
        var rows: [String] = []
        var i = 0
        while i < groups.count {
            rows.append(groups[i..<min(i + perRow, groups.count)].joined(separator: " "))
            i += perRow
        }

        return SendingDrill(kind: kind, groupSize: size, rows: rows)
    }

    /// Convenience overload using the system random generator.
    public static func generate(kind: Kind,
                                studied: [Character],
                                weights: [Character: Double] = [:],
                                groupCount: Int = 50,
                                groupSize: Int = 5,
                                groupsPerRow: Int = 5) -> SendingDrill {
        var rng = SystemRandomNumberGenerator()
        return generate(kind: kind, studied: studied, weights: weights,
                        groupCount: groupCount, groupSize: groupSize,
                        groupsPerRow: groupsPerRow, using: &rng)
    }

    /// The whole sheet as one plain-text block — a title, a description line, the
    /// group rows, and a footer — suitable for the share sheet or printing.
    public func plainText(title: String = "CW Sending Practice",
                          subtitle: String? = nil) -> String {
        var lines: [String] = [title]
        if let subtitle { lines.append(subtitle) }
        lines.append(kind.title + " · " + "\(rows.count) lines · groups of \(groupSize)")
        lines.append("")
        lines.append(contentsOf: rows)
        lines.append("")
        lines.append("Read each group and key it on your paddle. — Another Morse Trainer")
        return lines.joined(separator: "\n")
    }
}
