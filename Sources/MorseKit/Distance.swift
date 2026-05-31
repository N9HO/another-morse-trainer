import Foundation

/// Measures how "sonically close" two Morse characters are, so the trainer
/// can offer plausible wrong answers (distractors) next to the correct one.
///
/// The default metric is a weighted edit distance on the dot/dash patterns:
///   - changing one element to another (dit↔dah) costs 1
///   - inserting or removing an element (a length difference) costs 1.5
///
/// Because length differences cost more, characters of the *same length* that
/// differ by a single element come out "closest" — which matches the mistakes
/// real CW operators actually make (e.g. X `-..-` vs B `-...`, or X vs Y `-.--`).
public enum MorseDistance {

    /// Weighted edit distance between two Morse pattern strings.
    public static func distance(
        _ a: String,
        _ b: String,
        substitutionCost: Double = 1.0,
        indelCost: Double = 1.5
    ) -> Double {
        let aChars = Array(a), bChars = Array(b)
        let n = aChars.count, m = bChars.count
        if n == 0 { return Double(m) * indelCost }
        if m == 0 { return Double(n) * indelCost }

        var prev = (0...m).map { Double($0) * indelCost }
        var curr = [Double](repeating: 0, count: m + 1)

        for i in 1...n {
            curr[0] = Double(i) * indelCost
            for j in 1...m {
                let sub = prev[j - 1] + (aChars[i - 1] == bChars[j - 1] ? 0 : substitutionCost)
                let del = prev[j] + indelCost
                let ins = curr[j - 1] + indelCost
                curr[j] = min(sub, del, ins)
            }
            swap(&prev, &curr)
        }
        return prev[m]
    }

    /// Distance between two characters by their Morse patterns.
    public static func distance(_ a: Character, _ b: Character) -> Double {
        guard let pa = MorseCode.pattern(for: a),
              let pb = MorseCode.pattern(for: b) else { return .infinity }
        return distance(pa, pb)
    }

    /// The `count` characters that sound closest to `target`, drawn from
    /// `pool`, nearest first. The target itself is never included.
    public static func nearestNeighbors(
        to target: Character,
        in pool: [Character],
        count: Int
    ) -> [Character] {
        let scored: [(char: Character, dist: Double)] = pool
            .filter { $0 != target }
            .map { (char: $0, dist: distance(target, $0)) }
        // Sort by distance, then alphabetically so ties are deterministic.
        let sorted = scored.sorted { a, b in
            a.dist != b.dist ? a.dist < b.dist : a.char < b.char
        }
        return sorted.prefix(count).map { $0.char }
    }
}
