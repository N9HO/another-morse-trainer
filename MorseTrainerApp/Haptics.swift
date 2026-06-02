import UIKit

/// Lightweight wrapper around UIKit's feedback generators so the UI can add
/// tactile confirmation without sprinkling generator boilerplate everywhere.
/// All calls are no-ops on devices without a Taptic Engine.
enum Haptics {
    /// A correct answer — the success "ta-da" tap.
    static func success() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    /// A wrong answer — the gentle error buzz.
    static func error() {
        UINotificationFeedbackGenerator().notificationOccurred(.error)
    }

    /// A light tick for selections (mode tiles, choices).
    static func selection() {
        UISelectionFeedbackGenerator().selectionChanged()
    }

    /// A soft tap for primary taps like Start / Reveal.
    static func tap() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }
}
