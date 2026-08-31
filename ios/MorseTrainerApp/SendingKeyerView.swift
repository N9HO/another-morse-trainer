import SwiftUI

/// Answer panel shown in place of the tap-grid when "Answer by keying" is on.
/// The learner keys the answer (physical Vail/MIDI key or the on-screen key);
/// it's decoded live and submitted to the drill via `AppModel.select(_:)`.
struct SendingKeyerView: View {
    @EnvironmentObject var model: AppModel
    @StateObject private var sender: SendingKeyer
    @State private var keyPressed = false
    @State private var showingBluetoothMIDI = false

    init(wpm: Double, toneHz: Double) {
        _sender = StateObject(wrappedValue: SendingKeyer(wpm: wpm, toneHz: toneHz))
    }

    var body: some View {
        VStack(spacing: 14) {
            decodedDisplay
            keyButton
            controls
            midiStatus
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
                .font(Theme.copyFont(size: 34, weight: .semibold, monospaced: true,
                                     slashedZero: model.settings.slashedZero))
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
                    .foregroundStyle(keyPressed ? Theme.navy : Theme.teal)
                Text("HOLD TO KEY")
                    .font(.system(size: 12, weight: .bold)).tracking(1.5)
                    .foregroundStyle(keyPressed ? Theme.navy : Theme.textSecondary)
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
                    .foregroundStyle(Theme.navy)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.teal)
            .disabled(sender.decodedText.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }

    /// What hardware key (if any) is feeding this panel: a connected
    /// Vail/BLE-MIDI key is named, and when none is there the panel says which
    /// of the two reasons applies and offers the way out of the fixable one.
    /// The Bluetooth browser and its button live outside the connected/not
    /// branch on purpose. Hosting the sheet inside the "no key" branch meant the
    /// view presenting it was torn out of the hierarchy the moment a key
    /// connected — which is precisely what happens *while the browser is open*,
    /// since connecting there is what makes CoreMIDI enumerate the key. That
    /// yanked the sheet away mid-connect, and afterwards there was no way back
    /// in to attach a second key or retry a dropped one (issue #91).
    private var midiStatus: some View {
        VStack(spacing: 6) {
            if !sender.midiDeviceNames.isEmpty {
                Label(sender.midiDeviceNames.joined(separator: ", "), systemImage: "pianokeys")
                    .font(.caption)
                    .foregroundStyle(Theme.teal)
                    .lineLimit(1)
                    .accessibilityLabel("Hardware key connected: \(sender.midiDeviceNames.joined(separator: ", "))")
            } else {
                // "MIDI unavailable" used to cover both the setup failing and
                // nothing being plugged in, which read as a dead end. Only the
                // first is a fault; the second just needs a key connected —
                // and a BLE-MIDI key paired in iOS Settings still has to be
                // connected here before CoreMIDI will show it to any app.
                Label(sender.midiUnavailable
                        ? "MIDI unavailable — on-screen key only"
                        : "No hardware key — on-screen key only",
                      systemImage: sender.midiUnavailable
                        ? "exclamationmark.triangle" : "pianokeys")
                    .font(.caption)
                    .foregroundStyle(sender.midiUnavailable ? .orange : Theme.textSecondary)
                    .multilineTextAlignment(.center)
            }

            Button {
                showingBluetoothMIDI = true
            } label: {
                Label(sender.midiDeviceNames.isEmpty
                        ? "Connect a Bluetooth key…"
                        : "Bluetooth keys…",
                      systemImage: "dot.radiowaves.right")
                    .font(.caption)
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
        }
        .bluetoothMIDISheet(isPresented: $showingBluetoothMIDI) {
            sender.rescanMIDI()
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
