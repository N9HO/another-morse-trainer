import Foundation
import AVFoundation
import Speech

/// Listens to the microphone and reports what the learner said, for the voice
/// response training option.
///
/// Wraps `SFSpeechRecognizer` plus an input-tap `AVAudioEngine`. Two things make
/// it fit the trainer:
///   1. It reports **speech onset** (the first moment energy crosses a
///      threshold) so the time-to-recognize clock can stop the instant the
///      learner starts talking — not when they finish — per the spec.
///   2. When speech settles it returns the best transcript *and* the
///      recognizer's alternatives, so the matcher can rank "closest" answers.
///
/// Accuracy is improved with `contextualStrings` always, and — on iOS 17+ — a
/// custom language model weighted toward the expected vocabulary. The custom
/// model auto-disables on older systems (the `#available` check), falling back
/// to contextual strings alone.
@MainActor
final class VoiceRecognizer: NSObject, ObservableObject {

    enum Authorization { case unknown, authorized, denied }
    @Published private(set) var authorization: Authorization = .unknown

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    private let audioEngine = AVAudioEngine()
    private var task: SFSpeechRecognitionTask?

    private var onOnset: (() -> Void)?
    private var onResult: (([String]) -> Void)?
    private var didDetectOnset = false
    private var didFinish = false
    private var silenceTimer: Timer?
    private var lastTranscripts: [String] = []

    /// Prepared custom-LM configuration (typed as Any to avoid an availability
    /// annotation on a stored property). Holds `SFSpeechLanguageModel.Configuration`.
    private var preparedLMConfigBox: Any?

    /// "They started talking" energy threshold for onset detection.
    private static let onsetRMSThreshold: Float = 0.015
    /// How long after the last partial result to treat speech as finished.
    private let settleSeconds: TimeInterval = 0.9

    var isAvailable: Bool { recognizer?.isAvailable ?? false }

    // MARK: - Permissions

    /// Ask for speech-recognition and microphone permission. Safe to call more
    /// than once; the system only prompts the first time.
    func requestAuthorization(_ completion: ((Bool) -> Void)? = nil) {
        // Reflect already-granted permission synchronously so a returning user
        // can record on the very first drill, rather than sitting in `.unknown`
        // (and falling back to tapping) until the async callback lands.
        let speechGranted = SFSpeechRecognizer.authorizationStatus() == .authorized
        let micGranted = AVAudioSession.sharedInstance().recordPermission == .granted
        if speechGranted && micGranted { authorization = .authorized }

        SFSpeechRecognizer.requestAuthorization { status in
            AVAudioSession.sharedInstance().requestRecordPermission { micGranted in
                Task { @MainActor in
                    let ok = (status == .authorized) && micGranted
                    self.authorization = ok ? .authorized : .denied
                    completion?(ok)
                }
            }
        }
    }

    // MARK: - Custom language model (iOS 17+)

    /// Build and prepare a custom language model from the session's vocabulary
    /// once, off the main thread. No-op on iOS < 17 (auto-disabled). Best-effort:
    /// any failure leaves recognition driven by contextual strings alone.
    func prepareCustomLanguageModel(phrases: [String]) {
        guard #available(iOS 17.0, *), !phrases.isEmpty else { return }
        let unique = Array(Set(phrases))
        Task.detached(priority: .utility) {
            do {
                let data = SFCustomLanguageModelData(
                    locale: Locale(identifier: "en_US"),
                    identifier: "com.justinrogers.MorseTrainer.voice",
                    version: "1.0"
                ) {
                    for phrase in unique {
                        SFCustomLanguageModelData.PhraseCount(phrase: phrase, count: 10)
                    }
                }
                let url = FileManager.default.temporaryDirectory
                    .appendingPathComponent("morse-voice-lm.bin")
                try await data.export(to: url)
                let config = SFSpeechLanguageModel.Configuration(languageModel: url)
                try await SFSpeechLanguageModel.prepareCustomLanguageModel(
                    for: url,
                    clientIdentifier: "com.justinrogers.MorseTrainer.voice",
                    configuration: config)
                await MainActor.run { self.preparedLMConfigBox = config }
            } catch {
                // Degrade gracefully to contextual-strings-only recognition.
            }
        }
    }

    // MARK: - Listening

    /// Begin listening. Calls `onOnset` once when speech is first detected, and
    /// `onResult` once with the best transcript plus alternatives (best first)
    /// when speech settles. `onResult` is also called with an empty array if the
    /// recognizer is unavailable or permission is missing, so the caller can
    /// fall back to tapping.
    func start(contextualStrings: [String],
               onOnset: @escaping () -> Void,
               onResult: @escaping ([String]) -> Void) {
        stop()
        self.onOnset = onOnset
        self.onResult = onResult
        didDetectOnset = false
        didFinish = false
        lastTranscripts = []

        guard let recognizer, recognizer.isAvailable else { deliver([]); return }

        // Never touch the microphone until the user has actually granted
        // permission. `requestAuthorization()` is kicked off when the session
        // starts, but it's asynchronous — the first drill can finish playing
        // (and call us) before the user has tapped "Allow". Accessing the input
        // node with denied/undetermined permission yields an invalid (0 Hz)
        // hardware format, and `installTap` then trips an AVAudioEngine
        // assertion that crashes the app. Until we're authorized, fall back to
        // tapping for this round; the next round records normally.
        guard authorization == .authorized else {
            if authorization == .unknown { requestAuthorization() }
            deliver([]); return
        }

        // The session must allow recording while the tone can still play.
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .measurement,
                                    options: [.duckOthers, .defaultToSpeaker, .allowBluetooth])
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            deliver([]); return
        }

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.contextualStrings = contextualStrings
        request.taskHint = .search
        if recognizer.supportsOnDeviceRecognition {
            request.requiresOnDeviceRecognition = true
        }
        if #available(iOS 17.0, *),
           let config = preparedLMConfigBox as? SFSpeechLanguageModel.Configuration {
            request.customizedLanguageModel = config
        }

        let input = audioEngine.inputNode
        let format = input.outputFormat(forBus: 0)
        // A zero sample-rate or channel-count format means there's no usable
        // input route yet (permission race, no microphone, simulator). Passing
        // it to `installTap` trips `IsFormatSampleRateAndChannelCountValid` and
        // crashes, so bail to the tap-to-answer fallback instead.
        guard format.sampleRate > 0, format.channelCount > 0 else {
            deliver([]); return
        }
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self, request] buffer, _ in
            request.append(buffer)
            self?.detectOnset(in: buffer)
        }

        audioEngine.prepare()
        do { try audioEngine.start() } catch { deliver([]); return }

        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            Task { @MainActor in
                guard let self else { return }
                if let result {
                    self.lastTranscripts = VoiceRecognizer.collect(result)
                    self.scheduleSettle()
                    if result.isFinal { self.finish() }
                }
                if error != nil { self.finish() }
            }
        }
    }

    /// Stop listening and discard any pending callbacks.
    func stop() {
        teardown()
        onOnset = nil
        onResult = nil
    }

    // MARK: - Internals

    /// RMS energy onset detection, called from the realtime audio tap.
    nonisolated private func detectOnset(in buffer: AVAudioPCMBuffer) {
        guard let data = buffer.floatChannelData?[0] else { return }
        let n = Int(buffer.frameLength)
        guard n > 0 else { return }
        var sum: Float = 0
        for i in 0..<n { let s = data[i]; sum += s * s }
        let rms = (sum / Float(n)).squareRoot()
        guard rms >= VoiceRecognizer.onsetRMSThreshold else { return }
        Task { @MainActor in
            guard !self.didDetectOnset else { return }
            self.didDetectOnset = true
            self.onOnset?()
        }
    }

    private func scheduleSettle() {
        silenceTimer?.invalidate()
        silenceTimer = Timer.scheduledTimer(withTimeInterval: settleSeconds, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.finish() }
        }
    }

    private func finish() {
        guard !didFinish else { return }
        let results = lastTranscripts
        deliver(results)
    }

    /// Tear down the audio/recognition stack and report the result exactly once.
    private func deliver(_ results: [String]) {
        didFinish = true
        let callback = onResult
        teardown()
        callback?(results)
    }

    private func teardown() {
        silenceTimer?.invalidate()
        silenceTimer = nil
        if audioEngine.isRunning { audioEngine.stop() }
        audioEngine.inputNode.removeTap(onBus: 0)
        task?.cancel()
        task = nil
    }

    private static func collect(_ result: SFSpeechRecognitionResult) -> [String] {
        var out = [result.bestTranscription.formattedString]
        for t in result.transcriptions where !out.contains(t.formattedString) {
            out.append(t.formattedString)
        }
        return out.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }
}
