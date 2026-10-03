import Foundation
import AVFoundation
import Speech
import OSLog

private let log = Logger(subsystem: "com.justinrogers.MorseTrainer", category: "voice")

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
///
/// ## A session, then rounds (#300, #301)
///
/// The audio session and the microphone are held for the whole quiz session
/// (`beginSession` … `endSession`); each answer (`start` … `stop`) only hands
/// the running tap a fresh recognition request. It used to claim a record-
/// capable session and start the mic when each tone ended and give both back
/// after each answer. That reconfigured the route twice a character, which
///   * cut out the Morse that followed — on AirPods each claim moved the link
///     between A2DP and the hands-free profile, and the tone played into the
///     switch (#301), and
///   * could stop the input engine just after it started: the route change
///     landed as an engine configuration change, nothing restarted it, and the
///     round sat on "Speak your answer" with a recogniser that never got a
///     buffer (#300).
/// The input engine now restarts itself on a configuration change, and a round
/// that hears nothing at all falls back to tapping instead of waiting forever.
@MainActor
final class VoiceRecognizer: NSObject, ObservableObject {

    enum Authorization { case unknown, authorized, denied }
    @Published private(set) var authorization: Authorization = .unknown

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    /// `var` so it can be replaced after a media-services reset, which kills
    /// every audio object in the process (see MorsePlayer).
    private var audioEngine = AVAudioEngine()
    private var builtForReset = 0
    /// True while our tap is installed on `audioEngine`'s input node. The input
    /// node is never touched otherwise: reading it before permission is granted
    /// yields a 0 Hz format that crashes `installTap`.
    private var inputRunning = false
    /// Off while the app is in the background: the mic is not left open there.
    private var inForeground = true

    private var task: SFSpeechRecognitionTask?
    /// Where the always-on tap sends its buffers; empty between rounds.
    nonisolated private let tap = TapTarget()

    private var onOnset: (() -> Void)?
    private var onResult: (([String]) -> Void)?
    /// Bumped per round so a late callback from a cancelled task cannot finish
    /// the round that replaced it.
    private var round = 0
    private var didDetectOnset = false
    private var didFinish = false
    private var silenceTimer: Timer?
    private var nothingHeardTimer: Timer?
    private var lastTranscripts: [String] = []
    /// Held from `beginSession` (or the first round) to `endSession`.
    private var sessionClaim: AudioSession.Claim?
    private var sessionObservation: AudioSession.Observation?
    private var engineObservation: EngineConfigurationObserver?

    /// Prepared custom-LM configuration (typed as Any to avoid an availability
    /// annotation on a stored property). Holds `SFSpeechLanguageModel.Configuration`.
    private var preparedLMConfigBox: Any?
    /// Set when a round that used the custom model failed without a word; the
    /// rest of the run recognises without it rather than failing every round.
    private var customLMFailed = false

    /// "They started talking" energy threshold for onset detection.
    nonisolated fileprivate static let onsetRMSThreshold: Float = 0.015
    /// How long after the last partial result to treat speech as finished.
    private let settleSeconds: TimeInterval = 0.9
    /// How long to listen with nothing recognised before offering the tap
    /// grid. Long enough to think, short enough not to look frozen.
    private let nothingHeardSeconds: TimeInterval = 8

    var isAvailable: Bool { recognizer?.isAvailable ?? false }

    override init() {
        super.init()
        builtForReset = AudioSession.shared.resetGeneration
        // A call, a lost route or a media reset kills the microphone mid-round.
        // Falling back to tapping is the graceful answer; without this the round
        // sits waiting on a recogniser that will never report, which reads to
        // the user as the quiz having frozen.
        sessionObservation = AudioSession.shared.observe { [weak self] event in
            guard let self else { return }
            switch event {
            case .interruptionBegan, .routeLost, .mediaServicesWereReset:
                // The engine is stopped, or after a reset dead (startInput
                // replaces it); either way the next round starts it afresh.
                if case .mediaServicesWereReset = event {
                    self.inputRunning = false
                } else {
                    self.stopInput()
                }
                guard self.onResult != nil, !self.didFinish else { return }
                self.deliver([])
            case .interruptionEnded:
                break
            }
        }
        // A route or format change stops an AVAudioEngine without a word. The
        // recogniser's engine restarts at once if a session is live, so the
        // round in progress keeps hearing the learner.
        engineObservation = EngineConfigurationObserver { [weak self] changed in
            Task { @MainActor in self?.engineConfigurationChanged(changed) }
        }
    }

    deinit {
        if let sessionObservation { AudioSession.shared.removeObserver(sessionObservation) }
        if let sessionClaim { AudioSession.shared.release(sessionClaim) }
    }

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
        guard #available(iOS 17.0, *), !phrases.isEmpty, !customLMFailed else { return }
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
                let dir = FileManager.default.temporaryDirectory
                let asset = dir.appendingPathComponent("morse-voice-lm.bin")
                try? FileManager.default.removeItem(at: asset)
                try await data.export(to: asset)
                // The configuration names where the *compiled* model is written,
                // so it must not be the training data it is compiled from. It
                // used to be the same file: preparing it overwrote its own input.
                let config = SFSpeechLanguageModel.Configuration(
                    languageModel: dir.appendingPathComponent("morse-voice-lm.compiled"),
                    vocabulary: dir.appendingPathComponent("morse-voice-lm.vocab"))
                try await SFSpeechLanguageModel.prepareCustomLanguageModel(
                    for: asset,
                    clientIdentifier: "com.justinrogers.MorseTrainer.voice",
                    configuration: config)
                await MainActor.run { self.preparedLMConfigBox = config }
            } catch {
                // Degrade gracefully to contextual-strings-only recognition.
                log.error("Custom language model unavailable: \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    // MARK: - Session

    /// A voice-answer quiz is starting: take the record-capable audio session
    /// now, before the first tone, so the route is set up once rather than
    /// around every answer, and warm the microphone if permission is already
    /// in hand. Idempotent. Call before MorsePlayer claims the session, so the
    /// category is configured once.
    func beginSession() {
        if sessionClaim == nil {
            sessionClaim = AudioSession.shared.claim(.voiceAnswers)
        }
        if authorization == .authorized { startInput() }
    }

    /// The quiz is over (or voice answers were turned off): stop listening,
    /// close the microphone and give the session claim back.
    func endSession() {
        stop()
        stopInput()
        if let claim = sessionClaim {
            sessionClaim = nil
            AudioSession.shared.release(claim)
        }
    }

    /// Close the microphone while the app is not active; it reopens on return.
    /// The session claim is kept, so coming back changes no route. A round in
    /// progress keeps its request: if the app is back within the listening
    /// window it carries on hearing, and if not it falls back to tapping when
    /// that window closes.
    func setForeground(_ active: Bool) {
        inForeground = active
        if !active {
            stopInput()
        } else if sessionClaim != nil, authorization == .authorized {
            startInput()
        }
    }

    // MARK: - Listening

    /// Begin listening. Calls `onOnset` once when speech is first detected, and
    /// `onResult` once with the best transcript plus alternatives (best first)
    /// when speech settles. `onResult` is also called with an empty array if the
    /// recognizer is unavailable or permission is missing, or nothing is heard
    /// at all, so the caller can fall back to tapping.
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
        // permission. Accessing the input node with denied/undetermined
        // permission yields an invalid (0 Hz) hardware format, and `installTap`
        // then trips an AVAudioEngine assertion that crashes the app.
        switch authorization {
        case .authorized:
            beginRecording(with: recognizer, contextualStrings: contextualStrings)
        case .denied:
            deliver([])
        case .unknown:
            // `requestAuthorization()` is kicked off when the session starts,
            // but it's asynchronous — the first drill can finish playing (and
            // call us) before the user has tapped "Allow". Rather than silently
            // dropping this round to tapping (the reported "voice never works"
            // bug), ask now and start recording the instant permission lands.
            let thisRound = round
            requestAuthorization { [weak self] granted in
                guard let self else { return }
                // Bail if this listening round was torn down while we waited.
                guard self.round == thisRound, self.onResult != nil, !self.didFinish else { return }
                if granted, let recognizer = self.recognizer, recognizer.isAvailable {
                    self.beginRecording(with: recognizer, contextualStrings: contextualStrings)
                } else {
                    self.deliver([])
                }
            }
        }
    }

    /// Point the running tap at a new recognition request. The caller has
    /// already confirmed authorization and recognizer availability.
    private func beginRecording(with recognizer: SFSpeechRecognizer,
                                contextualStrings: [String]) {
        // Normally taken by `beginSession`; this covers a round started without
        // one. Either way it is held until `endSession`, not released per round.
        if sessionClaim == nil {
            sessionClaim = AudioSession.shared.claim(.voiceAnswers)
        }
        guard startInput() else { deliver([]); return }

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.contextualStrings = contextualStrings
        request.taskHint = .search
        if recognizer.supportsOnDeviceRecognition {
            request.requiresOnDeviceRecognition = true
        }
        var usesCustomLM = false
        if #available(iOS 17.0, *), !customLMFailed,
           let config = preparedLMConfigBox as? SFSpeechLanguageModel.Configuration {
            request.customizedLanguageModel = config
            usesCustomLM = true
        }

        let thisRound = round
        tap.begin(request) { [weak self] in
            Task { @MainActor in self?.markOnset(round: thisRound) }
        }

        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            let transcripts = result.map(VoiceRecognizer.collect)
            let isFinal = result?.isFinal ?? false
            let failure = error.map { $0.localizedDescription }
            Task { @MainActor in
                guard let self, self.round == thisRound, !self.didFinish else { return }
                if let transcripts {
                    self.lastTranscripts = transcripts
                    self.scheduleSettle()
                    if isFinal { self.finish() }
                }
                if let failure {
                    log.error("Recognition failed: \(failure, privacy: .public)")
                    if usesCustomLM, self.lastTranscripts.isEmpty {
                        self.customLMFailed = true
                    }
                    self.finish()
                }
            }
        }

        nothingHeardTimer = Timer.scheduledTimer(withTimeInterval: nothingHeardSeconds,
                                                 repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.round == thisRound else { return }
                self.finish()
            }
        }
    }

    /// Stop listening and discard any pending callbacks. The microphone and the
    /// audio session stay up until `endSession`.
    func stop() {
        endRound()
        onOnset = nil
        onResult = nil
    }

    // MARK: - Internals

    private func markOnset(round thisRound: Int) {
        guard thisRound == round, !didDetectOnset, !didFinish else { return }
        didDetectOnset = true
        onOnset?()
    }

    private func scheduleSettle() {
        nothingHeardTimer?.invalidate()
        nothingHeardTimer = nil
        silenceTimer?.invalidate()
        let thisRound = round
        silenceTimer = Timer.scheduledTimer(withTimeInterval: settleSeconds, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.round == thisRound else { return }
                self.finish()
            }
        }
    }

    private func finish() {
        guard !didFinish else { return }
        let results = lastTranscripts
        deliver(results)
    }

    /// End the round and report the result exactly once.
    private func deliver(_ results: [String]) {
        didFinish = true
        let callback = onResult
        endRound()
        callback?(results)
    }

    /// Detach the round from the tap and cancel its recogniser. Leaves the
    /// microphone running for the next round.
    private func endRound() {
        round += 1
        silenceTimer?.invalidate()
        silenceTimer = nil
        nothingHeardTimer?.invalidate()
        nothingHeardTimer = nil
        tap.end()
        task?.cancel()
        task = nil
    }

    /// Start the input engine with the tap installed, if it is not already
    /// running. Returns whether it is running. Needs a record-capable session
    /// and granted permission.
    @discardableResult
    private func startInput() -> Bool {
        guard inForeground else { return false }
        if AudioSession.shared.resetGeneration != builtForReset {
            // The media server restarted: the engine is a dead object.
            audioEngine = AVAudioEngine()
            builtForReset = AudioSession.shared.resetGeneration
            inputRunning = false
        }
        if inputRunning && audioEngine.isRunning { return true }

        let input = audioEngine.inputNode
        // Always clear the bus first: after a configuration change the old tap
        // is still installed (in the old format), and installing a second one
        // on the same bus is an AVAudioEngine assertion. Removing none is a no-op.
        input.removeTap(onBus: 0)
        inputRunning = false
        let format = input.outputFormat(forBus: 0)
        // A zero sample-rate or channel-count format means there's no usable
        // input route yet (permission race, no microphone, simulator). Passing
        // it to `installTap` trips `IsFormatSampleRateAndChannelCountValid` and
        // crashes, so bail to the tap-to-answer fallback instead.
        guard format.sampleRate > 0, format.channelCount > 0 else {
            log.error("No usable microphone input format")
            return false
        }
        let target = tap
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            target.receive(buffer)
        }
        inputRunning = true
        audioEngine.prepare()
        do {
            try audioEngine.start()
            return true
        } catch {
            log.error("Microphone engine failed to start: \(error.localizedDescription, privacy: .public)")
            input.removeTap(onBus: 0)
            inputRunning = false
            return false
        }
    }

    private func stopInput() {
        guard inputRunning else { return }
        if audioEngine.isRunning { audioEngine.stop() }
        audioEngine.inputNode.removeTap(onBus: 0)
        inputRunning = false
    }

    /// The engine was stopped by a route or format change. Restart it (with a
    /// tap in the new format) while a session is live; if that fails, give the
    /// round in progress back to tapping rather than leave it deaf.
    private func engineConfigurationChanged(_ changed: ObjectIdentifier?) {
        guard changed == ObjectIdentifier(audioEngine), inputRunning, sessionClaim != nil else { return }
        log.info("Microphone engine configuration changed; restarting")
        if !startInput(), onResult != nil, !didFinish { deliver([]) }
    }

    private nonisolated static func collect(_ result: SFSpeechRecognitionResult) -> [String] {
        var out = [result.bestTranscription.formattedString]
        for t in result.transcriptions where !out.contains(t.formattedString) {
            out.append(t.formattedString)
        }
        return out.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }
}

/// The hand-off between the input tap, which runs on an AVAudioEngine thread
/// for the whole session, and the round in progress on the main actor. Between
/// rounds there is no request and the buffers are dropped.
///
/// `@unchecked Sendable` because every field is read and written under `lock`.
/// `SFSpeechAudioBufferRecognitionRequest.append` is documented as callable
/// from the audio thread, which is how Apple's own samples feed it; the request
/// is only ever appended to outside the lock.
private final class TapTarget: @unchecked Sendable {
    private let lock = NSLock()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var onsetPending = false
    private var onOnset: (@Sendable () -> Void)?

    func begin(_ request: SFSpeechAudioBufferRecognitionRequest,
               onOnset: @escaping @Sendable () -> Void) {
        lock.lock()
        self.request = request
        self.onOnset = onOnset
        onsetPending = true
        lock.unlock()
    }

    func end() {
        lock.lock()
        request?.endAudio()
        request = nil
        onOnset = nil
        onsetPending = false
        lock.unlock()
    }

    /// Called from the tap. Feeds the live request and reports onset once.
    func receive(_ buffer: AVAudioPCMBuffer) {
        lock.lock()
        let request = self.request
        let wantsOnset = onsetPending
        lock.unlock()
        guard let request else { return }
        request.append(buffer)
        guard wantsOnset, Self.rms(buffer) >= VoiceRecognizer.onsetRMSThreshold else { return }
        lock.lock()
        let fire = onsetPending && self.request === request
        if fire { onsetPending = false }
        let callback = onOnset
        lock.unlock()
        if fire { callback?() }
    }

    private static func rms(_ buffer: AVAudioPCMBuffer) -> Float {
        guard let data = buffer.floatChannelData?[0] else { return 0 }
        let n = Int(buffer.frameLength)
        guard n > 0 else { return 0 }
        var sum: Float = 0
        for i in 0..<n { let s = data[i]; sum += s * s }
        return (sum / Float(n)).squareRoot()
    }
}
