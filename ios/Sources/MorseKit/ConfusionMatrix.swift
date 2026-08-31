import Foundation

/// Tracks which character the learner *actually picked* when they got one
/// wrong — e.g. they heard `X` but answered `Y`. These directed tallies are
/// the raw material for the confusion-pair drills: the characters you mix up
/// in practice are exactly the ones worth drilling head-to-head.
///
/// Counts are directional ("heard X, picked Y" is tracked separately from
/// "heard Y, picked X") because the two error directions can differ in
/// strength, but `pairs` collapses them into unordered pairs for display.
public struct ConfusionMatrix: Codable, Sendable, Equatable {

    /// Directed counts keyed by "target‹US›chosen" (US = unit separator, so the
    /// key is unambiguous even though both parts are single characters today).
    private var counts: [String: Int]

    public init() { counts = [:] }

    private static func key(_ target: Character, _ chosen: Character) -> String {
        "\(target)\u{1}\(chosen)"
    }

    private static func parse(_ key: String) -> (Character, Character)? {
        let parts = key.split(separator: "\u{1}", omittingEmptySubsequences: false)
        guard parts.count == 2, let a = parts[0].first, let b = parts[1].first else { return nil }
        return (a, b)
    }

    /// Note that `target` was played but `chosen` was answered.
    public mutating func record(target: Character, chosen: Character) {
        guard target != chosen else { return }
        counts[Self.key(target, chosen), default: 0] += 1
    }

    /// A correct recognition eases a previously-confused pairing (one direction),
    /// so pairs the learner has sorted out gradually drop out of rotation.
    public mutating func ease(target: Character, chosen: Character) {
        let k = Self.key(target, chosen)
        guard let v = counts[k] else { return }
        if v <= 1 { counts[k] = nil } else { counts[k] = v - 1 }
    }

    /// How many times `target` was answered as `chosen`.
    public func count(target: Character, chosen: Character) -> Int {
        counts[Self.key(target, chosen)] ?? 0
    }

    public var isEmpty: Bool { counts.isEmpty }

    /// Directed confusions, strongest first (ties broken deterministically).
    public func entries(minCount: Int = 1) -> [(target: Character, chosen: Character, count: Int)] {
        counts
            .compactMap { (k, v) -> (Character, Character, Int)? in
                guard v >= minCount, let (t, c) = Self.parse(k) else { return nil }
                return (t, c, v)
            }
            .sorted { a, b in
                a.2 != b.2 ? a.2 > b.2 : (a.0 != b.0 ? a.0 < b.0 : a.1 < b.1)
            }
            .map { (target: $0.0, chosen: $0.1, count: $0.2) }
    }

    /// Unordered confused pairs with both directions summed, strongest first.
    /// Handy for a "your most-confused pairs" display.
    public func pairs(minCount: Int = 1) -> [(a: Character, b: Character, count: Int)] {
        var summed: [String: (Character, Character, Int)] = [:]
        for e in entries() {
            let lo = min(e.target, e.chosen), hi = max(e.target, e.chosen)
            let key = Self.key(lo, hi)
            if let existing = summed[key] {
                summed[key] = (existing.0, existing.1, existing.2 + e.count)
            } else {
                summed[key] = (lo, hi, e.count)
            }
        }
        return summed.values
            .filter { $0.2 >= minCount }
            .sorted { a, b in
                a.2 != b.2 ? a.2 > b.2 : (a.0 != b.0 ? a.0 < b.0 : a.1 < b.1)
            }
            .map { (a: $0.0, b: $0.1, count: $0.2) }
    }
}
