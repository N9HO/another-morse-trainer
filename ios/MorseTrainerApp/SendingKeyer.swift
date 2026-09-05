import Foundation
import OSLog

private let log = Logger(subsystem: "com.justinrogers.MorseTrainer", category: "sendingkeyer")

/// Drives "sending practice": a physical (Vail Adapter / BLE MIDI) or on-screen
/// Morse key plays sidetone and is decoded back to text via `MorseDecoder`. The
/// decoded text answers the current drill through `AppModel.select(_:)`.
///
/// Reuses the same building blocks as the live repeater (`KeyerEngine` sidetone,
/// `MIDIInput` events) but stays offline — nothing is transmitted.
@MainActor
final class SendingKeyer: ObservableObject {
    /// Decoded text so far (finalized characters; trailing in-progress character
    /// appears once its letter gap elapses).
    @Published private(set) var decodedText = ""
    /// True while a key is held down (drives the on-screen key's pressed look).
    @Published private(set) var isKeying = false
    /// Names of the connected hardware keys (Vail Adapter / BLE-MIDI), live
    /// through hot-plug and unplug, for the connected-device readout.
    @Published private(set) var midiDeviceNames: [String] = []
    /// True when MIDI setup itself failed, so the UI can say a hardware key
    /// won't work instead of silently ignoring it (the on-screen key still does).
    /// Distinct from simply having nothing connected yet — see `midiDeviceNames`.
    @Published private(set) var midiUnavailable = false

    private let keyer = KeyerEngine()
    private var midi: MIDIInput?
    /// Output to the key, kept alive for one reason: waking the Vail Adapter.
    /// The adapter boots in HID keyboard mode and sends **no** MIDI note events
    /// until it receives a Control Change, so without this the adapter
    /// enumerates, `midiDeviceNames` names it, and every paddle press goes
    /// nowhere. Only the repeater ran `MIDIOutput`, so sending practice was
    /// dead with a USB Vail Adapter — the same defect reported on Android as
    /// N9HO/another-morse-trainer-android#42.
    private var midiOutput: MIDIOutput?
    private let decoder: MorseDecoder

    /// Kept so the adapter can be woken with the speed and tone in use, and
    /// re-pushed when Settings changes either mid-session (`applyConfig`).
    private var keyerWPM: Double
    private var toneMIDINote: Int

    /// Paddles currently held. The key is "down" while *any* of them is: a
    /// dit and dah squeezed together, or a release that overlaps the next
    /// press, must not cut the tone short on the first note-off (Android
    /// `MidiKeyInput.updateHeld`). The on-screen key counts as `.straight`.
    private var heldKeys = Set<MIDIInput.Key>()
    /// When the first of the held paddles went down; the burst is timed from
    /// here to the release of the last one.
    private var keyDownAtMs: Int64?
    private var idleTask: Task<Void, Never>?

    init(wpm: Double, toneHz: Double) {
        decoder = MorseDecoder(wpm: wpm)
        keyerWPM = wpm
        toneMIDINote = Self.midiNote(forHz: toneHz)
        keyer.localTxToneMIDI = toneMIDINote
        decoder.onUpdate = { [weak self] text in
            self?.decodedText = text
        }
    }

    func start() {
        try? keyer.start()
        do {
            let input = try MIDIInput()
            input.onEvent = { [weak self] event in
                // Straight key, dit, and dah paddles are all measured as bursts;
                // the adapter does any iambic timing, so we just time key-down.
                Task { @MainActor in
                    self?.handle(key: event.key, isDown: event.isDown, atMs: event.timestampMs)
                }
            }
            input.onSourcesChanged = { [weak self] names in
                Task { @MainActor in self?.midiDeviceNames = names }
            }
            midi = input
            midiDeviceNames = input.connectedSourceNames
            midiUnavailable = false
            wakeAdapter()
        } catch {
            // The on-screen key still works, but surface the failure so a
            // hardware-key user isn't left keying into the void.
            midiDeviceNames = []
            midiUnavailable = true
        }
    }

    /// Re-enumerate MIDI sources — after connecting a BLE-MIDI key, whose
    /// arrival CoreMIDI reports but which is worth confirming on the spot.
    /// A key that has just arrived also has to be woken before it will send.
    func rescanMIDI() {
        guard let midi else { return }
        midi.rescan()
        midiDeviceNames = midi.connectedSourceNames
        wakeAdapter()
    }

    /// Put the Vail Adapter into MIDI mode so it starts sending note events.
    ///
    /// `connectToAdapter()` broadcasts the init sequence to every non-network
    /// destination, because the adapter enumerates under different names across
    /// firmwares ("Vail", "QT Py M0"). It is idempotent, so calling it on start
    /// and again after a rescan is safe. A BLE-MIDI paddle that was never in
    /// keyboard mode simply ignores the sequence.
    private func wakeAdapter() {
        do {
            let out = try midiOutput ?? MIDIOutput()
            midiOutput = out
            // The keyer mode describes the user's *hardware* (straight key vs
            // iambic paddle), so reuse the one they chose — in Settings or on
            // the repeater screen, which share one stored value — rather than
            // resetting their paddle every time practice starts. Speed, by
            // contrast, is what they're practising at.
            let mode = MIDIOutput.KeyerMode(rawValue: RepeaterModel.storedKeyerMode) ?? .straightKey
            let wpm = Int(keyerWPM.rounded())
            let tone = toneMIDINote
            Task {
                await out.configure(keyerMode: mode, wpm: wpm, sidetoneMIDINote: tone)
                await out.connectToAdapter()
            }
        } catch {
            // The key may still send on its own (a BLE paddle always does), and
            // the on-screen key is unaffected — so this is not a hard failure.
            log.error("MIDIOutput init failed: \(error.localizedDescription)")
        }
    }

    /// Re-push the key and practice settings to an adapter that is already
    /// awake.
    ///
    /// `start()` configures the adapter once, from the settings as they stood
    /// when the screen opened. Everything reachable from the mid-session
    /// Settings sheet can move afterwards — the keyer mode itself (read back
    /// from the shared stored value), and the speed the adapter's own keyer
    /// sends at in the modes where `KeyerMode.adapterTimesSending` holds —
    /// and until this existed none of it reached the adapter until the next
    /// wake, i.e. not until the operator left the drill and came back. Only
    /// differences go on the wire, so calling this is cheap. Mirrors
    /// `HardwareKey.applyConfig` on Android.
    func applyConfig(wpm: Double, toneHz: Double) {
        keyerWPM = wpm
        toneMIDINote = Self.midiNote(forHz: toneHz)
        keyer.localTxToneMIDI = toneMIDINote
        let mode = MIDIOutput.KeyerMode(rawValue: RepeaterModel.storedKeyerMode) ?? .straightKey
        let wpmInt = Int(wpm.rounded())
        let tone = toneMIDINote
        guard let out = midiOutput else { return }
        Task { await out.applyConfig(keyerMode: mode, wpm: wpmInt, sidetoneMIDINote: tone) }
    }

    func stop() {
        idleTask?.cancel()
        idleTask = nil
        midi = nil
        midiOutput = nil
        keyer.stop()
    }

    /// On-screen key press/release.
    func touchKey(isDown: Bool) {
        handle(key: .straight, isDown: isDown, atMs: Int64(Date().timeIntervalSince1970 * 1000))
    }

    func clear() {
        decoder.reset()
    }

    /// Flush the in-progress character and return the full decoded answer.
    func submit() -> String {
        decoder.submit().trimmingCharacters(in: .whitespaces)
    }

    // MARK: - Key handling

    private func handle(key: MIDIInput.Key, isDown: Bool, atMs ms: Int64) {
        // Aggregate per-paddle state into one logical key: down while ANY
        // paddle is held, up only when the last one releases.
        let wasHeld = !heldKeys.isEmpty
        if isDown { heldKeys.insert(key) } else { heldKeys.remove(key) }
        let nowHeld = !heldKeys.isEmpty
        guard nowHeld != wasHeld else { return }
        if nowHeld {
            keyDownAtMs = ms
            isKeying = true
            idleTask?.cancel()
            idleTask = nil
            keyer.beginTx()
        } else {
            guard let down = keyDownAtMs else { return }
            keyDownAtMs = nil
            isKeying = false
            keyer.endTx()
            decoder.ingestTone(Double(max(0, ms - down)))
            scheduleIdleFinalize()
        }
    }

    /// After the key is released, a letter-gap of silence finalizes the current
    /// character; a longer word-gap of silence adds a space.
    private func scheduleIdleFinalize() {
        idleTask?.cancel()
        let letterGap = decoder.letterGapMs
        let wordGap = decoder.wordGapMs
        idleTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(letterGap * 1_000_000))
            guard let self, !Task.isCancelled else { return }
            self.decoder.finishCharacter()
            let rest = max(0, wordGap - letterGap)
            try? await Task.sleep(nanoseconds: UInt64(rest * 1_000_000))
            guard !Task.isCancelled else { return }
            // A word gap of continued silence: add a space (multi-word answers).
            self.decoder.ingestGap(self.decoder.wordGapMs)
        }
    }

    // MARK: - Helpers

    /// Nearest MIDI note to a frequency in Hz (A4 = 69 = 440 Hz), so the
    /// sidetone roughly matches the user's chosen tone frequency.
    private static func midiNote(forHz hz: Double) -> Int {
        guard hz > 0 else { return 72 }
        return Int((69 + 12 * log2(hz / 440)).rounded())
    }
}

/// Keeps a connected adapter in step with Settings when no keyer screen is
/// underneath to do it.
///
/// Opened from the intro there is no `SendingKeyer` or `RepeaterModel`
/// holding an output, so a keyer mode picked there had nowhere to go until
/// the next screen woke the adapter. This holds one `MIDIOutput` for as long
/// as the Settings sheet is up and pushes changes down it the way a drill
/// does. Mid-session the drill underneath pushes too; `MIDIOutput.applyConfig`
/// only sends differences, so the overlap is harmless. Mirrors the `homeKey`
/// + `AdapterConfigSync` pair in Android's `SettingsScreen`.
///
/// The output is created on the first change rather than when the sheet
/// opens, so merely reading Settings never touches the adapter; the first
/// push is the full wake sequence (`connectToAdapter`), which both carries
/// the change and identifies the destination later diffs go to. The output
/// is released with the holder — no explicit stop.
@MainActor
final class AdapterSettingsSync: ObservableObject {
    private var output: MIDIOutput?
    private var triedInit = false

    /// Push whatever changed since the last call; wake the adapter first if
    /// this is the first change since the sheet opened.
    func apply(keyerMode: Int, wpm: Double, toneHz: Double) {
        let mode = MIDIOutput.KeyerMode(rawValue: keyerMode) ?? .straightKey
        let wpmInt = Int(wpm.rounded())
        let tone = Self.midiNote(forHz: toneHz)
        if let out = output {
            Task { await out.applyConfig(keyerMode: mode, wpm: wpmInt, sidetoneMIDINote: tone) }
            return
        }
        guard !triedInit else { return }
        triedInit = true
        do {
            let out = try MIDIOutput()
            output = out
            Task {
                await out.configure(keyerMode: mode, wpm: wpmInt, sidetoneMIDINote: tone)
                await out.connectToAdapter()
            }
        } catch {
            log.error("MIDIOutput init failed for Settings adapter sync: \(error.localizedDescription)")
        }
    }

    private static func midiNote(forHz hz: Double) -> Int {
        guard hz > 0 else { return 72 }
        return Int((69 + 12 * log2(hz / 440)).rounded())
    }
}
