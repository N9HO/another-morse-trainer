import SwiftUI

/// Big press-and-hold Morse key for the live repeater screen. `keyPress`
/// reports the press and the release as they happen (a plain TapGesture only
/// fires on release, which can't key Morse), even inside this screen's
/// scroll view.
struct RepeaterTouchKeyView: View {
    @EnvironmentObject var model: RepeaterModel
    @State private var isPressed = false

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                .fill(isPressed ? Theme.teal : Theme.navyRaised)
            RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                .strokeBorder(isPressed ? Theme.tealBright : Theme.hairline,
                              lineWidth: isPressed ? 2 : 1)

            VStack(spacing: 6) {
                Image(systemName: "dot.radiowaves.left.and.right")
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(isPressed ? Theme.navy : Theme.teal)
                Text(isPressed ? "TRANSMITTING" : "HOLD TO KEY")
                    .font(.system(size: 13, weight: .bold))
                    .tracking(1.5)
                    .foregroundStyle(isPressed ? Theme.navy : Theme.textSecondary)
                Text(model.breakInEnabled ? "Break-in LIVE · TX \(midiNoteName(model.txTone))"
                                          : "Break-in OFF · sidetone only")
                    .font(.caption2)
                    .foregroundStyle(isPressed ? Color.white.opacity(0.85) : Theme.textSecondary)
            }
        }
        .scaleEffect(isPressed ? 0.98 : 1)
        .animation(.easeOut(duration: 0.06), value: isPressed)
        .keyPress { down, ms in
            guard isPressed != down else { return }
            isPressed = down
            // Key first, buzz after: the tone is what has to be on time.
            model.touchKey(isDown: down, atMs: ms)
            if down { Haptics.tap() }
        }
        .accessibilityLabel("Morse key")
        .accessibilityHint("Press and hold to send a tone")
    }

    private func midiNoteName(_ note: Int) -> String {
        let names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
        let octave = (note / 12) - 1
        return "\(names[note % 12])\(octave)"
    }
}
