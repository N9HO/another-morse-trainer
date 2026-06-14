import Foundation

/// Turns keyed timing back into text — the inverse of `MorsePlayer`.
///
/// A real key (or the Vail Adapter, or the on-screen key) gives us *tone*
/// durations (key down → key up) and *gap* durations (key up → next key down).
/// This classifies each tone as a dit or dah and each gap as an
/// intra-character, letter, or word boundary, then looks the assembled elements
/// up in `MorseCode`.
///
/// The unit (dit) length is **adaptive**: it's seeded from a nominal WPM and
/// nudged toward what the operator is actually sending, so a human who speeds up
/// or slows down mid-session still decodes. Classic CW proportions are assumed:
/// dit = 1 unit, dah = 3, element gap = 1, letter gap = 3, word gap = 7.
public final class MorseDecoder {

    /// Text decoded so far (finalized characters only — the in-progress
    /// character isn't shown until its letter gap completes or `submit()` runs).
    public private(set) var text: String = ""

    /// Called on the main thread whenever `text` changes.
    public var onUpdate: ((String) -> Void)?

    /// Marker appended when a keyed character doesn't match any known symbol —
    /// so garbled sending visibly fails to match the expected answer.
    public static let unknownMarker: Character = "#"

    private var elements: [MorseCode.Element] = []
    private var unitMs: Double

    // Boundaries, in units. A tone shorter than `ditDahSplit` is a dit; a gap of
    // at least `letterGapUnits` ends the character, `wordGapUnits` adds a space.
    private let ditDahSplit: Double = 2.0
    private let letterGapUnits: Double = 2.0
    private let wordGapUnits: Double = 5.0

    public init(wpm: Double = 20) {
        unitMs = 1200.0 / max(5, min(60, wpm))
    }

    /// The current adaptive dit length in milliseconds. Exposed so the owner can
    /// drive its letter-gap / word-gap silence timers off the same estimate.
    public var ditMs: Double { unitMs }
    public var letterGapMs: Double { unitMs * 3 }
    public var wordGapMs: Double { unitMs * 7 }

    /// True when a character is mid-entry (elements buffered, not yet finalized).
    public var hasPendingElements: Bool { !elements.isEmpty }

    public func reset() {
        text = ""
        elements.removeAll()
        onUpdate?(text)
    }

    // MARK: - Streaming input

    /// Record one keyed tone (key-down duration). Classifies dit vs dah.
    public func ingestTone(_ durationMs: Double) {
        guard durationMs > 0 else { return }
        let units = durationMs / unitMs
        let element: MorseCode.Element = units < ditDahSplit ? .dit : .dah
        elements.append(element)
        adaptUnit(toneMs: durationMs, element: element)
    }

    /// Record a gap (silence between tones). Ends a character on a letter gap and
    /// appends a space on a word gap; intra-character gaps are ignored.
    public func ingestGap(_ durationMs: Double) {
        guard hasPendingElements || !text.isEmpty else { return }
        let units = durationMs / unitMs
        if units >= wordGapUnits {
            finishCharacter()
            if !text.isEmpty, text.last != " " {
                text.append(" ")
                onUpdate?(text)
            }
        } else if units >= letterGapUnits {
            finishCharacter()
        }
    }

    /// Decode the buffered elements into one character. Called when a letter gap
    /// elapses or the operator submits. No-op when nothing is buffered.
    @discardableResult
    public func finishCharacter() -> Character? {
        guard hasPendingElements else { return nil }
        let decoded = MorseCode.character(for: elements)
        elements.removeAll()
        text.append(decoded ?? Self.unknownMarker)
        onUpdate?(text)
        return decoded
    }

    /// Flush any in-progress character and return the full decoded text. Used by
    /// the Submit button in sending practice.
    @discardableResult
    public func submit() -> String {
        finishCharacter()
        return text
    }

    private func adaptUnit(toneMs: Double, element: MorseCode.Element) {
        // Nudge the dit estimate toward the observed tone (a dit ≈ 1 unit, a dah
        // ≈ 3). Light EMA so one sloppy element doesn't swing the estimate, and
        // clamp to a 5–60 WPM sanity range.
        let implied = element == .dit ? toneMs : toneMs / 3.0
        let clamped = min(max(implied, 20), 240)
        let alpha = 0.3
        unitMs = (1 - alpha) * unitMs + alpha * clamped
    }
}

public extension MorseDecoder {
    /// Decode a batch of (tone, trailing-gap) pairs in one shot. Convenience for
    /// tests and offline decoding. Each pair's gap follows its tone; the final
    /// character is flushed automatically.
    func decodeTimings(_ pairs: [(tone: Double, gap: Double)]) -> String {
        for p in pairs {
            ingestTone(p.tone)
            ingestGap(p.gap)
        }
        finishCharacter()
        return text
    }
}
