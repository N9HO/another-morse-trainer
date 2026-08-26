import Foundation
import AVFoundation

/// Live CW (Morse) audio decoder: taps the microphone and runs the vendored
/// Carrier Wave C core (Sources/CWDecoderCore, via the bridging header) over
/// the incoming PCM, publishing decoded text plus speed/pitch telemetry.
///
/// Threading follows the core's contract — cw_decoder_feed() is called only
/// from the audio tap's realtime thread, and the core's callbacks fire there
/// too. `CoreBox` collects what a tap delivered; one hop per buffer brings it
/// to the main actor. The decoder is created/destroyed only while the tap is
/// down, so the pointer never sees two threads at once.
@MainActor
final class CWDecoderEngine: ObservableObject {

    @Published private(set) var decodedText = ""
    @Published private(set) var wpm: Float = 0
    @Published private(set) var toneHz: Float = 0
    @Published private(set) var tonePresent = false
    @Published private(set) var inputLevel: Float = 0     // mic RMS, 0…~1
    @Published private(set) var isListening = false
    @Published private(set) var micDenied = false

    private let audioEngine = AVAudioEngine()
    private let core = CoreBox()

    /// Keep the rolling transcript bounded so a long listen can't grow forever.
    private static let transcriptLimit = 800

    // MARK: - Control

    /// Ask for the microphone (first run only) and start decoding.
    func start() {
        guard !isListening else { return }
        let session = AVAudioSession.sharedInstance()
        switch session.recordPermission {
        case .granted:
            beginListening()
        case .denied:
            micDenied = true
        case .undetermined:
            session.requestRecordPermission { [weak self] granted in
                Task { @MainActor in
                    guard let self, !self.isListening else { return }
                    if granted { self.beginListening() } else { self.micDenied = true }
                }
            }
        @unknown default:
            micDenied = true
        }
    }

    func stop() {
        if audioEngine.isRunning { audioEngine.stop() }
        audioEngine.inputNode.removeTap(onBus: 0)
        core.destroyDecoder()
        isListening = false
        tonePresent = false
        inputLevel = 0
        // Hand the shared session back to plain playback, mirroring
        // VoiceRecognizer: staying on .playAndRecord would break background
        // tone playback elsewhere in the app.
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, options: [.duckOthers])
        try? session.setActive(true)
    }

    func clear() {
        decodedText = ""
    }

    // MARK: - Capture

    private func beginListening() {
        micDenied = false
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .measurement,
                                    options: [.duckOthers, .defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
        } catch { return }

        let input = audioEngine.inputNode
        let format = input.outputFormat(forBus: 0)
        // A dead input route (permission race, simulator, no mic) reports a
        // 0 Hz format; installing a tap on it crashes. Same guard as
        // VoiceRecognizer. The core itself needs at least 6 kHz.
        guard format.sampleRate >= 6000, format.channelCount > 0 else { return }

        guard core.createDecoder(inputRate: UInt32(format.sampleRate)) else { return }

        let box = core
        input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, _ in
            box.feed(buffer)
            guard let update = box.drain() else { return }
            Task { @MainActor in self?.apply(update) }
        }
        audioEngine.prepare()
        do { try audioEngine.start() } catch {
            input.removeTap(onBus: 0)
            core.destroyDecoder()
            return
        }
        isListening = true
    }

    private func apply(_ update: CoreBox.Update) {
        if !update.text.isEmpty {
            decodedText += update.text
            if decodedText.count > Self.transcriptLimit {
                decodedText = String(decodedText.suffix(Self.transcriptLimit))
            }
        }
        wpm = update.wpm
        toneHz = update.toneHz
        tonePresent = update.tonePresent
        inputLevel = update.level
    }
}

/// Owns the C decoder and everything the realtime tap touches. All fields are
/// read and written on the tap thread while the engine runs; create/destroy
/// happen from the main actor only while no tap is installed.
private final class CoreBox: @unchecked Sendable {

    struct Update {
        var text: String
        var wpm: Float
        var toneHz: Float
        var tonePresent: Bool
        var level: Float
    }

    private var decoder: OpaquePointer?
    private var scratch: [Int16] = []
    private var pendingText = ""
    private var wpm: Float = 0
    private var toneHz: Float = 0
    private var tonePresent = false
    private var level: Float = 0
    private var reported = Update(text: "", wpm: -1, toneHz: -1, tonePresent: false, level: -1)

    func createDecoder(inputRate: UInt32) -> Bool {
        destroyDecoder()
        pendingText = ""
        wpm = 0; toneHz = 0; tonePresent = false; level = 0
        reported = Update(text: "", wpm: -1, toneHz: -1, tonePresent: false, level: -1)

        var cfg = cw_config_t()
        cw_config_default(&cfg)
        cfg.input_rate_hz = inputRate
        // Pick a decimation factor that divides the hardware rate exactly, so
        // the Goertzel bins sit where the config says they do (48 k → 8 k,
        // 44.1 k → 7.35 k, …).
        let factor = max(1, Int((Double(inputRate) / 8000.0).rounded()))
        cfg.target_rate_hz = max(3000, inputRate / UInt32(factor))
        cfg.input_channels = 1
        cfg.user = Unmanaged.passUnretained(self).toOpaque()
        cfg.on_symbol = { text, _, user in
            guard let text, let user else { return }
            let box = Unmanaged<CoreBox>.fromOpaque(user).takeUnretainedValue()
            box.pendingText += String(cString: text)
        }
        cfg.on_status = { wpm, _, tonePresent, user in
            guard let user else { return }
            let box = Unmanaged<CoreBox>.fromOpaque(user).takeUnretainedValue()
            box.wpm = wpm
            box.tonePresent = tonePresent
        }
        decoder = cw_decoder_create(&cfg)
        return decoder != nil
    }

    func destroyDecoder() {
        if let decoder { cw_decoder_destroy(decoder) }
        decoder = nil
    }

    /// Convert one Float32 buffer to int16 PCM and push it through the core.
    /// Runs on the realtime tap thread.
    func feed(_ buffer: AVAudioPCMBuffer) {
        guard let decoder, let data = buffer.floatChannelData?[0] else { return }
        let count = Int(buffer.frameLength)
        guard count > 0 else { return }
        if scratch.count < count { scratch = [Int16](repeating: 0, count: count) }
        var energy: Float = 0
        for i in 0..<count {
            let raw = data[i]
            let sample = raw.isFinite ? max(-1, min(1, raw)) : 0
            energy += sample * sample
            scratch[i] = Int16(sample * 32000)
        }
        level = (energy / Float(count)).squareRoot()
        scratch.withUnsafeBufferPointer { cw_decoder_feed(decoder, $0.baseAddress, count) }
        toneHz = cw_decoder_tone_hz(decoder)
    }

    /// What changed since the last drain, or nil when nothing worth a hop to
    /// the main actor happened in this buffer.
    func drain() -> Update? {
        let update = Update(text: pendingText, wpm: wpm, toneHz: toneHz,
                            tonePresent: tonePresent, level: level)
        pendingText = ""
        let newsworthy = !update.text.isEmpty
            || update.tonePresent != reported.tonePresent
            || update.wpm != reported.wpm
            || abs(update.level - reported.level) > 0.01
        guard newsworthy else { return nil }
        reported = update
        return update
    }
}
