import SwiftUI

/// Answer panel shown in place of the tap-grid when "Answer by keying" is on.
/// The learner keys the answer (physical Vail/MIDI key or the on-screen key);
/// it's decoded live and submitted to the drill via `AppModel.select(_:)`.
struct SendingKeyerView: View {
    @EnvironmentObject var model: AppModel
    @StateObject private var sender: SendingKeyer
    @State private var keyPressed = false
    @State private var showingBluetoothMIDI = false
    /// The operator's keyer mode, read from the value Settings and the Vail
    /// screen share (issue #43), so a change made in the Settings sheet drawn
    /// over this drill is pushed to the adapter at once.
    @AppStorage(RepeaterModel.keyerModeDefaultsKey) private var adapterKeyerMode: Int =
        MIDIOutput.KeyerMode.straightKey.rawValue

    init(wpm: Double, toneHz: Double) {
        _sender = StateObject(wrappedValue: SendingKeyer(wpm: wpm, toneHz: toneHz))
    }

    var body: some View {
        VStack(spacing: 14) {
            decodedDisplay
            // Straight key or paddles, as chosen in Settings (#233); the
            // paddles' keyer feeds the same sidetone and decoder.
            OnScreenKeySwitch(onKey: { down, ms in sender.touchKey(isDown: down, atMs: ms) }) {
                keyButton
            }
            .frame(height: 120)
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
        // The Settings sheet is drawn *over* this drill, so the keyer mode,
        // speed and tone can all move while the adapter is awake. Push each
        // change down the output we already hold rather than waiting for the
        // next wake (Android `AdapterConfigSync`).
        .onChange(of: adapterKeyerMode) { _ in applyAdapterConfig() }
        .onChange(of: model.settings.wpm) { _ in applyAdapterConfig() }
        .onChange(of: model.settings.toneFrequency) { _ in applyAdapterConfig() }
    }

    private func applyAdapterConfig() {
        sender.applyConfig(wpm: model.settings.wpm, toneHz: model.settings.toneFrequency)
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

/// A hardware Morse key (Vail Adapter / BLE-MIDI) typing into a screen that
/// otherwise takes the keyboard — the Pileup Runner and Contest box, Type It,
/// QRQ, Daily Dit, Defender's typed copy and Journey's choices (#251).
///
/// The adapter types nothing on its own: out of the box it is a HID keyboard
/// sending Ctrl (or `[` `]`) for dit and dah, which a text field ignores, and
/// it sends MIDI only once something wakes it. Only the screens that answer by
/// keying ran a `SendingKeyer`, so everywhere else the key was dead. This runs
/// the same `SendingKeyer` those screens use — adapter wake, sidetone, decoder
/// — and hands each newly decoded run of text to `onText`. Once the key has
/// been idle for two word gaps (after the decoder's own word space), the
/// operator has stopped sending: `onPause` fires and the decoder starts clean.
///
/// Hardware keys only; the on-screen key stays with the modes that answer by
/// keying. Typing still works alongside it. Twin of the Kotlin
/// `HardwareKeyInput` composable in HardwareKey.kt.
struct HardwareKeyInput: ViewModifier {
    let wpm: Double
    let toneHz: Double
    let isEnabled: Bool
    let onText: (String) -> Void
    let onPause: () -> Void

    @StateObject private var sender: SendingKeyer
    /// How much of `sender.decodedText` has already been handed on.
    @State private var fed = 0
    @State private var pauseTask: Task<Void, Never>?
    @State private var running = false
    /// The keyer mode Settings and the Vail screen share, pushed to an awake
    /// adapter when the Settings sheet drawn over the screen changes it.
    @AppStorage(RepeaterModel.keyerModeDefaultsKey) private var adapterKeyerMode: Int =
        MIDIOutput.KeyerMode.straightKey.rawValue

    init(wpm: Double, toneHz: Double, isEnabled: Bool,
         onText: @escaping (String) -> Void, onPause: @escaping () -> Void) {
        self.wpm = wpm
        self.toneHz = toneHz
        self.isEnabled = isEnabled
        self.onText = onText
        self.onPause = onPause
        _sender = StateObject(wrappedValue: SendingKeyer(wpm: wpm, toneHz: toneHz))
    }

    func body(content: Content) -> some View {
        content
            .onAppear { setRunning(isEnabled) }
            .onDisappear { setRunning(false) }
            .onChange(of: isEnabled) { setRunning($0) }
            .onChange(of: sender.decodedText) { feed($0) }
            // Every key edge restarts the pause clock. Watching the edge count
            // rather than `isKeying`: a down/up pair delivered together leaves
            // `isKeying` unchanged and would not restart it.
            .onChange(of: sender.edgeCount) { _ in
                pauseTask?.cancel()
                pauseTask = nil
                if running, !sender.isKeying { schedulePause() }
            }
            .onChange(of: adapterKeyerMode) { _ in applyConfig() }
            .onChange(of: wpm) { _ in applyConfig() }
            .onChange(of: toneHz) { _ in applyConfig() }
    }

    /// Append a decoded run to a text box: no leading space into an empty box
    /// or after one that already ends in a space.
    static func appending(_ chunk: String, to text: String) -> String {
        var add = Substring(chunk)
        if text.isEmpty || text.hasSuffix(" ") { add = add.drop { $0 == " " } }
        return text + add
    }

    private func setRunning(_ on: Bool) {
        guard on != running else { return }
        running = on
        if on {
            // The keyer was built with the settings of the first render; wake
            // the adapter with the ones in force now.
            sender.applyConfig(wpm: wpm, toneHz: toneHz)
            sender.start(sidetoneOnDemand: true)
        } else {
            pauseTask?.cancel()
            pauseTask = nil
            sender.stop()
            sender.clear()
            fed = 0
        }
    }

    private func applyConfig() {
        guard running else { return }
        sender.applyConfig(wpm: wpm, toneHz: toneHz)
    }

    private func feed(_ decoded: String) {
        if decoded.count < fed { fed = 0 }   // the decoder was cleared
        guard decoded.count > fed else { return }
        let chunk = String(decoded.dropFirst(fed))
        fed = decoded.count
        onText(chunk)
    }

    private func schedulePause() {
        let wait = sender.wordGapMs * 2
        let edge = sender.edgeCount
        pauseTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(max(0, wait) * 1_000_000))
            // Re-checked on waking: still idle, and no edge since this was set.
            guard !Task.isCancelled, running, !sender.isKeying,
                  sender.edgeCount == edge else { return }
            guard fed > 0 else { return }   // nothing was decoded — no send
            onPause()
            sender.clear()
            fed = 0
        }
    }
}

extension View {
    /// Feed a hardware Morse key into this screen (#251); see `HardwareKeyInput`.
    func hardwareKeyInput(wpm: Double, toneHz: Double, isEnabled: Bool = true,
                          onText: @escaping (String) -> Void,
                          onPause: @escaping () -> Void = {}) -> some View {
        modifier(HardwareKeyInput(wpm: wpm, toneHz: toneHz, isEnabled: isEnabled,
                                  onText: onText, onPause: onPause))
    }
}
