import Foundation
import AVFoundation

/// The microphone as a Morse key (#234, #235): a tone the phone can hear — a
/// practice oscillator, a keyer's sidetone, a rig's speaker, a signal
/// generator — becomes key-down / key-up edges for the Sending Analyzer.
///
/// The tone detection is MorseKit's `ToneKeyingDetector`; this is only the
/// capture around it, built the way `CWDecoderEngine` captures (same session
/// profile, same dead-route guard, same stop-on-interruption), and it does
/// not touch the vendored decoder core. Edge times come from the sample count,
/// so they are as accurate as the detector, not as late as the main actor.
///
/// Nobody has keyed real hardware into this yet: the detector is pinned by a
/// synthetic fixture, and the capture has only been compiled.
@MainActor
final class SendingMicInput: ObservableObject {

    @Published private(set) var isListening = false
    @Published private(set) var micDenied = false
    /// Tone level and floor (sine amplitude, 0…1) for the meter.
    @Published private(set) var level: Double = 0
    @Published private(set) var noiseFloor: Double = 0
    @Published private(set) var toneOn = false
    @Published private(set) var pitchHz: Double?
    @Published private(set) var pitchLocked = false

    /// Key edges, in ms since listening started.
    var onEdge: ((_ isDown: Bool, _ atMs: Double) -> Void)?

    private let audioEngine = AVAudioEngine()
    private let box = DetectorBox()
    private var sessionClaim: AudioSession.Claim?
    private var sessionObservation: AudioSession.Observation?

    init() {
        sessionObservation = AudioSession.shared.observe { [weak self] event in
            guard let self, self.isListening else { return }
            switch event {
            case .interruptionBegan, .routeLost, .mediaServicesWereReset:
                self.stop()
            case .interruptionEnded:
                break
            }
        }
    }

    deinit {
        if let sessionObservation { AudioSession.shared.removeObserver(sessionObservation) }
        if let sessionClaim { AudioSession.shared.release(sessionClaim) }
    }

    /// Ask for the microphone (first run only) and start listening.
    func start(sensitivity: Double, manualPitchHz: Double?) {
        guard !isListening else { return }
        let session = AVAudioSession.sharedInstance()
        switch session.recordPermission {
        case .granted:
            begin(sensitivity: sensitivity, manualPitchHz: manualPitchHz)
        case .denied:
            micDenied = true
        case .undetermined:
            session.requestRecordPermission { [weak self] granted in
                Task { @MainActor in
                    guard let self, !self.isListening else { return }
                    if granted {
                        self.begin(sensitivity: sensitivity, manualPitchHz: manualPitchHz)
                    } else {
                        self.micDenied = true
                    }
                }
            }
        @unknown default:
            micDenied = true
        }
    }

    func stop() {
        if audioEngine.isRunning { audioEngine.stop() }
        audioEngine.inputNode.removeTap(onBus: 0)
        isListening = false
        toneOn = false
        level = 0
        if let claim = sessionClaim {
            sessionClaim = nil
            AudioSession.shared.release(claim)
        }
    }

    /// Change how loud a tone must be over the room (applied on the next buffer).
    func setSensitivity(_ snr: Double) { box.requestSNR(snr) }

    private func begin(sensitivity: Double, manualPitchHz: Double?) {
        micDenied = false
        sessionClaim = AudioSession.shared.claim(.recording)
        let input = audioEngine.inputNode
        let format = input.outputFormat(forBus: 0)
        // A dead input route reports a 0 Hz format; a tap on it crashes.
        guard format.sampleRate >= 6000, format.channelCount > 0 else {
            stop(); return
        }
        box.configure(sampleRate: format.sampleRate, snr: sensitivity, pitchHz: manualPitchHz)
        let box = self.box
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            guard let update = box.feed(buffer) else { return }
            Task { @MainActor in self?.apply(update) }
        }
        audioEngine.prepare()
        do { try audioEngine.start() } catch {
            input.removeTap(onBus: 0)
            stop()
            return
        }
        isListening = true
    }

    private func apply(_ update: DetectorBox.Update) {
        for edge in update.edges { onEdge?(edge.isDown, edge.timeMs) }
        level = update.level
        noiseFloor = update.floor
        toneOn = update.isDown
        pitchHz = update.pitchHz
        pitchLocked = update.locked
    }
}

/// Owns the detector. `feed` runs only on the tap's realtime thread; the one
/// thing the main actor may change while it runs (the sensitivity) goes
/// through `lock` and is picked up at the start of the next buffer.
/// `configure` runs only while no tap is installed.
private final class DetectorBox: @unchecked Sendable {
    struct Update: Sendable {
        var edges: [ToneKeyingDetector.Edge]
        var level: Double
        var floor: Double
        var isDown: Bool
        var pitchHz: Double?
        var locked: Bool
    }

    private var detector = ToneKeyingDetector(sampleRate: 48_000)
    private let lock = NSLock()
    private var pendingSNR: Double?

    func configure(sampleRate: Double, snr: Double, pitchHz: Double?) {
        detector = ToneKeyingDetector(sampleRate: sampleRate, snr: snr, pitchHz: pitchHz)
        lock.lock(); pendingSNR = nil; lock.unlock()
    }

    func requestSNR(_ snr: Double) {
        lock.lock(); pendingSNR = snr; lock.unlock()
    }

    func feed(_ buffer: AVAudioPCMBuffer) -> Update? {
        guard let data = buffer.floatChannelData?[0] else { return nil }
        let count = Int(buffer.frameLength)
        guard count > 0 else { return nil }
        lock.lock()
        if let snr = pendingSNR { detector.snr = snr; pendingSNR = nil }
        lock.unlock()
        let edges = detector.process(UnsafeBufferPointer(start: data, count: count))
        return Update(edges: edges, level: detector.level, floor: detector.noiseFloor,
                      isDown: detector.isDown, pitchHz: detector.pitchHz, locked: detector.isLocked)
    }
}
