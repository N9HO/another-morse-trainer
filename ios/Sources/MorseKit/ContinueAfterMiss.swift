import Foundation

/// "Continue with the key after a miss" (issues #352, #347): the decision logic.
///
/// The trap. When a keyed answer is wrong the screen holds the correction —
/// "it was D" — and waits for a Next tap, so a learner on a Vail adapter, a BLE
/// key or the on-screen paddles has to let go of the key and reach for the
/// screen after every miss.
///
/// The design. A miss puts the screen in an *acknowledge* state. Keying the
/// correct answer again — which is exactly what the correction invites — acts as
/// Next. Why this and not retry-until-correct or "any key after a pause": the
/// re-key is the useful practice (you send the thing you just got wrong), a
/// wrong re-key costs nothing and changes nothing (no second miss is counted, so
/// fumbling the retry cannot drag accuracy down twice for one error), and a stray
/// touch cannot skip the correction the way "any key" would. Next and the tap
/// still work; this only adds a way forward.
///
/// The debounce. The keyer is still decoding when the miss lands — the last
/// dits of the wrong answer, or the sidetone replay's key-up, can arrive right
/// after feedback is drawn. Anything keyed in the first `debounceMs` after the
/// miss is ignored (right or wrong), so the learner never advances by accident
/// before they have seen the correction. The caller clears its keyer on every
/// non-`.advance` result so a wrong attempt does not stay in the decoder and
/// spoil the next one.
///
/// Pure: no clock, no keyer, no settings. The caller passes times in and applies
/// the outcome. Pinned by `fixtures/continue-after-miss.json`. Twin of
/// ContinueAfterMiss.kt in the android and desktop trees — keep in step.
public struct ContinueAfterMiss: Equatable, Sendable {

    public enum Outcome: String, Equatable, Sendable {
        /// Not acknowledging a miss; the input is not this type's business.
        case inactive
        /// The correct answer was keyed: behave as Next.
        case advance
        /// A wrong re-key: ignore it, stay on the miss, count nothing.
        case wrong
        /// Keyed too soon after the miss feedback: ignore it.
        case debounced
    }

    /// Ignore keying this long after the miss feedback appears.
    public static let debounceMs: Int64 = 600

    private var target: String?
    private var missAtMs: Int64 = 0

    public init() {}

    /// True between a miss that armed this and the answer, `reset()`, or a new miss.
    public var isAwaiting: Bool { target != nil }

    /// A keyed answer was just graded wrong. Arms the acknowledge state when the
    /// setting is `enabled` and `target` has something to key; otherwise it is
    /// (left) idle.
    public mutating func miss(target: String, atMs: Int64, enabled: Bool) {
        let wanted = Self.normalize(target)
        guard enabled, !wanted.isEmpty else {
            self.target = nil
            return
        }
        self.target = wanted
        self.missAtMs = atMs
    }

    /// What to do with `text`, decoded from the key at `atMs`.
    public mutating func input(_ text: String, atMs: Int64) -> Outcome {
        guard let wanted = target else { return .inactive }
        if atMs - missAtMs < Self.debounceMs { return .debounced }
        if Self.normalize(text) == wanted {
            target = nil
            return .advance
        }
        return .wrong
    }

    /// Next was pressed by hand, or a new drill began.
    public mutating func reset() { target = nil }

    private static func normalize(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }
}
