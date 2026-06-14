import Foundation

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

    private let keyer = KeyerEngine()
    private var midi: MIDIInput?
    private let decoder: MorseDecoder

    private var keyDownAtMs: Int64?
    private var idleTask: Task<Void, Never>?

    init(wpm: Double, toneHz: Double) {
        decoder = MorseDecoder(wpm: wpm)
        keyer.localTxToneMIDI = Self.midiNote(forHz: toneHz)
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
                    self?.handle(isDown: event.isDown, atMs: event.timestampMs)
                }
            }
            midi = input
        } catch {
            // No MIDI is fine — the on-screen key still works.
        }
    }

    func stop() {
        idleTask?.cancel()
        idleTask = nil
        midi = nil
        keyer.stop()
    }

    /// On-screen key press/release.
    func touchKey(isDown: Bool) {
        handle(isDown: isDown, atMs: Int64(Date().timeIntervalSince1970 * 1000))
    }

    func clear() {
        decoder.reset()
    }

    /// Flush the in-progress character and return the full decoded answer.
    func submit() -> String {
        decoder.submit().trimmingCharacters(in: .whitespaces)
    }

    // MARK: - Key handling

    private func handle(isDown: Bool, atMs ms: Int64) {
        if isDown {
            guard keyDownAtMs == nil else { return }
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
