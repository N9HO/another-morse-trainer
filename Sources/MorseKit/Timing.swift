import Foundation

/// Converts a words-per-minute (WPM) speed into the precise tone and gap
/// durations used to key Morse code.
///
/// The standard reference word is "PARIS", which is exactly 50 time units
/// long. So at a given WPM, there are `50 * WPM` units per minute, which
/// makes one unit (one "dit") = `1200 / WPM` milliseconds.
///
/// Standard ("PARIS") timing, in dit units:
///   - dit tone .............. 1
///   - dah tone .............. 3
///   - gap between elements .. 1   (inside a single character)
///   - gap between characters  3
///   - gap between words ..... 7
public struct MorseTiming: Sendable, Equatable {
    public let wpm: Double

    public init(wpm: Double) {
        self.wpm = wpm
    }

    /// Duration of one dit, in seconds.
    public var unit: TimeInterval { (1200.0 / wpm) / 1000.0 }

    public var dit: TimeInterval { unit }
    public var dah: TimeInterval { 3 * unit }
    public var elementGap: TimeInterval { unit }       // between dits/dahs in a char
    public var characterGap: TimeInterval { 3 * unit }
    public var wordGap: TimeInterval { 7 * unit }

    /// Total time to play a single character, from the start of its first
    /// tone to the end of its last tone (no trailing gap).
    public func duration(of character: Character) -> TimeInterval {
        let elements = MorseCode.elements(for: character)
        guard !elements.isEmpty else { return 0 }
        let toneTime = elements.reduce(0.0) { $0 + ($1 == .dit ? dit : dah) }
        let gapTime = Double(elements.count - 1) * elementGap
        return toneTime + gapTime
    }
}
