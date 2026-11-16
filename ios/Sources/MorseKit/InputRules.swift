import Foundation

/// Small pure rules behind the input settings that every app must agree on,
/// pinned by `fixtures/gap-input-settings-rules.json`. Kept as plain values so
/// each tree's settings screen can lean on one definition.
public enum InputRules {

    // MARK: Side tone and tone spread (parity row 18)

    public static let sidetoneMin = 300.0
    public static let sidetoneMax = 1000.0
    public static let sidetoneStep = 10.0

    public static let toneSpreadMin = 0.0
    public static let toneSpreadMax = 500.0
    public static let toneSpreadStep = 10.0

    /// A stored side tone pulled into the slider's range.
    public static func clampSidetone(_ hz: Double) -> Double {
        min(max(hz, sidetoneMin), sidetoneMax)
    }

    public static func clampToneSpread(_ hz: Double) -> Double {
        min(max(hz, toneSpreadMin), toneSpreadMax)
    }

    /// The slider reads "Zero-beat" below one step.
    public static func isZeroBeat(_ spread: Double) -> Bool { spread < toneSpreadStep }

    /// Tone spread's value label.
    public static func toneSpreadLabel(_ spread: Double) -> String {
        isZeroBeat(spread) ? "Zero-beat" : "±\(Int(spread)) Hz"
    }

    // MARK: Pileup speed and wait (parity row 19)

    public static let callerWpmMin = 12.0
    public static let callerWpmMax = 60.0
    public static let minWaitMax = 3.0
    public static let maxWaitMax = 4.0

    public static func clampCallerWpm(_ wpm: Double) -> Double {
        min(max(wpm, callerWpmMin), callerWpmMax)
    }
    public static func clampMinWait(_ s: Double) -> Double { min(max(s, 0), minWaitMax) }
    public static func clampMaxWait(_ s: Double) -> Double { min(max(s, 0), maxWaitMax) }

    /// The speed band callers are drawn from: the pair in sorted order.
    public static func effectiveSpeedRange(min a: Double, max b: Double) -> (lo: Double, hi: Double) {
        (Swift.min(a, b), Swift.max(a, b))
    }

    /// The reply-wait window: min wait to the larger of the two.
    public static func effectiveWaitRange(min a: Double, max b: Double) -> (lo: Double, hi: Double) {
        (a, Swift.max(a, b))
    }

    // MARK: Your callsign (parity row 20)

    public static let callsignPlaceholder = "W1AW"
    public static let callsignMaxLength = 12

    /// What the callsign field keeps of what was typed: upper-cased, only
    /// A-Z, 0-9 and "/", at most 12 characters. May be empty.
    public static func normalizeCallsign(_ input: String) -> String {
        let kept = input.uppercased().unicodeScalars.filter { s in
            (s.value >= 65 && s.value <= 90) || (s.value >= 48 && s.value <= 57) || s == "/"
        }
        return String(String.UnicodeScalarView(kept.prefix(callsignMaxLength)))
    }

    /// The value stored for what was typed: `normalizeCallsign`, with a blank
    /// result becoming W1AW.
    public static func storedCallsign(_ input: String) -> String {
        let n = normalizeCallsign(input)
        return n.isEmpty ? callsignPlaceholder : n
    }
}
