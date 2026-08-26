import UIKit

/// Lightweight wrapper around UIKit's feedback generators so the UI can add
/// tactile confirmation without sprinkling generator boilerplate everywhere.
/// All calls are no-ops on devices without a Taptic Engine.
enum Haptics {
    /// The user's "Haptic feedback" setting, mirrored here by AppModel so every
    /// call site stays a one-liner. Off silences all four feedbacks.
    static var enabled = true

    /// A correct answer — the success "ta-da" tap.
    static func success() {
        guard enabled else { return }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    /// A wrong answer — the gentle error buzz.
    static func error() {
        guard enabled else { return }
        UINotificationFeedbackGenerator().notificationOccurred(.error)
    }

    /// A light tick for selections (mode tiles, choices).
    static func selection() {
        guard enabled else { return }
        UISelectionFeedbackGenerator().selectionChanged()
    }

    /// A soft tap for primary taps like Start / Reveal.
    static func tap() {
        guard enabled else { return }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }
}
