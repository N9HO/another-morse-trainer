import SwiftUI

/// Answer panel shown in place of the tap-grid when "Answer by keying" is on.
/// The learner keys the answer (physical Vail/MIDI key or the on-screen key);
/// it's decoded live and submitted to the drill via `AppModel.select(_:)`.
struct SendingKeyerView: View {
    @EnvironmentObject var model: AppModel
    @StateObject private var sender: SendingKeyer
    @State private var keyPressed = false

    init(wpm: Double, toneHz: Double) {
        _sender = StateObject(wrappedValue: SendingKeyer(wpm: wpm, toneHz: toneHz))
    }

    var body: some View {
        VStack(spacing: 14) {
            decodedDisplay
            keyButton
            controls
        }
        .onAppear { sender.start() }
        .onDisappear { sender.stop() }
        // Start each drill with a clean slate so a new answer is never appended
        // to the previous one's decoded text.
        .onChange(of: model.drill) { _ in sender.clear() }
        // Auto-submit once the decoded text reaches the expected answer length
        // and the operator has stopped keying.
        .onChange(of: sender.decodedText) { _ in maybeAutoSubmit() }
        .onChange(of: sender.isKeying) { _ in maybeAutoSubmit() }
    }

    // MARK: - Display

    private var decodedDisplay: some View {
        VStack(spacing: 4) {
            Text("YOU SENT")
                .font(.system(size: 10, weight: .bold)).tracking(1.5)
                .foregroundStyle(Theme.textSecondary)
            Text(sender.decodedText.isEmpty ? "—" : sender.decodedText)
                .font(.system(size: 34, weight: .semibold, design: .monospaced))
                .foregroundStyle(sender.decodedText.isEmpty ? Theme.textSecondary : .white)
                .lineLimit(1).minimumScaleFactor(0.5)
                .frame(maxWidth: .infinity, minHeight: 50)
        }
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity)
        .brandCard()
    }

    private var keyButton: some View {
        ZStack {
            RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                .fill(keyPressed ? Theme.teal : Theme.navyRaised)
            RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                .strokeBorder(keyPressed ? Theme.tealBright : Theme.hairline,
                              lineWidth: keyPressed ? 2 : 1)
            VStack(spacing: 4) {
                Image(systemName: "dot.radiowaves.left.and.right")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(keyPressed ? .white : Theme.teal)
                Text("HOLD TO KEY")
                    .font(.system(size: 12, weight: .bold)).tracking(1.5)
                    .foregroundStyle(keyPressed ? .white : Theme.textSecondary)
            }
        }
        .frame(height: 120)
        .scaleEffect(keyPressed ? 0.98 : 1)
        .animation(.easeOut(duration: 0.06), value: keyPressed)
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    if !keyPressed { keyPressed = true; sender.touchKey(isDown: true) }
                }
                .onEnded { _ in
                    if keyPressed { keyPressed = false; sender.touchKey(isDown: false) }
                }
        )
        .accessibilityLabel("Morse key")
        .accessibilityHint("Press and hold to send each dit and dah of your answer")
    }

    private var controls: some View {
        HStack(spacing: 12) {
            Button { sender.clear() } label: {
                Label("Clear", systemImage: "delete.left")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
            .tint(Theme.textSecondary)

            Button { submit() } label: {
                Label("Submit", systemImage: "checkmark")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.teal)
            .disabled(sender.decodedText.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }

    // MARK: - Submission

    private func submit() {
        let answer = sender.submit()
        guard !answer.isEmpty else { return }
        Haptics.tap()
        model.select(answer)
    }

    private var expectedAnswer: String {
        model.drill?.correct.uppercased() ?? ""
    }

    /// When the decoded text has reached the expected answer's length and the key
    /// is idle, submit automatically so the rhythm matches tapping/voice.
    private func maybeAutoSubmit() {
        guard !expectedAnswer.isEmpty, !sender.isKeying else { return }
        let typed = sender.decodedText.trimmingCharacters(in: .whitespaces)
        guard typed.count >= expectedAnswer.count else { return }
        model.select(typed)
    }
}
