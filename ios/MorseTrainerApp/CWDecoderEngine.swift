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

/// Owns the C decoders and everything the realtime tap touches. All fields are
/// read and written on the tap thread while the engine runs; create/destroy
/// happen from the main actor only while no tap is installed.
///
/// Two core instances run side by side. The vendored core's pitch search is
/// built for radio-fed audio: it locks onto the first sustained tone it hears
/// and never re-arms. On an open phone microphone that tone is usually some
/// ambient sound, and a decoder parked a hundred hertz off pitch turns real
/// CW into a spray of high-confidence junk characters — or nothing. So next
/// to the `primary` decoder (whose text and telemetry feed the UI) a `scout`
/// runs on the same samples, reset back to fresh acquisition whenever it has
/// copied nothing for a while — and only during quiet, because a decoder that
/// recalibrates its noise floor inside a continuous tone goes deaf for
/// seconds. The referee is physical, not statistical (junk decode and real
/// copy look alike symbol-for-symbol): the box runs its own Goertzel at each
/// slot's locked pitch, and when the scout's pitch has been carrying several
/// times the primary's energy and the scout is actually decoding it, the
/// scout holds the real signal and the two swap roles.
private final class CoreBox: @unchecked Sendable {

    struct Update {
        var text: String
        var wpm: Float
        var toneHz: Float
        var tonePresent: Bool
        var level: Float
    }

    /// One core instance plus everything its C callbacks write into, and the
    /// box's own energy meter at the pitch this instance is locked to.
    /// Rolling one-second buckets hold decoded-symbol counts and summed
    /// Goertzel magnitudes.
    private final class Slot {
        static let buckets = 12

        var decoder: OpaquePointer?
        var pendingText = ""
        var wpm: Float = 0
        var tonePresent = false
        var bucketSymbols = [Int](repeating: 0, count: Slot.buckets)
        var bucketMag = [Float](repeating: 0, count: Slot.buckets)
        var head = 0
        var samplesSinceReset = 0
        /// Samples since the last decoded character (spaces don't count) —
        /// "long ago" meaning not in the middle of copying anything.
        var samplesSinceSymbol = Int.max / 2
        /// Pitch the magnitude meter is tuned to, and its Goertzel coefficient.
        var meterHz: Float = 0
        var meterCoeff: Float = 0

        func advanceBucket() {
            head = (head + 1) % Slot.buckets
            bucketSymbols[head] = 0
            bucketMag[head] = 0
        }

        /// Symbol count over the last `n` one-second buckets (current included).
        func symbols(last n: Int) -> Int {
            var total = 0
            for i in 0..<n { total += bucketSymbols[(head - i + Slot.buckets) % Slot.buckets] }
            return total
        }

        /// Summed magnitude at `meterHz` over the last `n` buckets.
        func magnitude(last n: Int) -> Float {
            var total: Float = 0
            for i in 0..<n { total += bucketMag[(head - i + Slot.buckets) % Slot.buckets] }
            return total
        }

        /// Retune the energy meter when the core re-locks; magnitudes gathered
        /// at the old pitch would poison the comparison, so they go too.
        func retuneMeter(to toneHz: Float, rate: Int) {
            guard abs(toneHz - meterHz) > 1 else { return }
            meterHz = toneHz
            meterCoeff = 2 * cos(2 * Float.pi * toneHz / Float(rate))
            for i in 0..<Slot.buckets { bucketMag[i] = 0 }
        }

        /// Back to fresh acquisition: recalibrate the noise floor and re-arm
        /// the pitch search. Everything counted so far belonged to the old lock.
        func reset() {
            if let decoder { cw_decoder_reset(decoder) }
            pendingText = ""
            for i in 0..<Slot.buckets {
                bucketSymbols[i] = 0
                bucketMag[i] = 0
            }
            samplesSinceReset = 0
            samplesSinceSymbol = Int.max / 2
        }
    }

    /// An idle scout re-arms after this long without a single decoded symbol…
    private static let scoutIdleSeconds = 5
    /// …but only once the room has been quiet this many consecutive buffers
    /// (≈350 ms at the usual tap size — a word gap, not an inter-character
    /// gap), so recalibration never happens inside a tone…
    private static let quietBuffers = 8
    /// …unless nothing has been decoded for this long, when it resets anyway
    /// rather than stay parked forever under wall-to-wall sound.
    private static let scoutForceSeconds = 20
    /// A rescue swap needs the scout's pitch to carry this multiple of the
    /// primary's energy (judged over `magSeconds`)…
    private static let swapMagRatio: Float = 3
    /// …with at least this much absolute signal, over this window, and with
    /// the scout actually decoding it (symbols within `scoutIdleSeconds`).
    private static let swapMagFloor: Float = 0.02
    private static let magSeconds = 4
    /// The two pitches must genuinely differ; equal locks mean the primary
    /// already sits on the strongest tone.
    private static let swapMinHzApart: Float = 40
    /// A long-idle primary re-arms too (quiet-gated like the scout).
    private static let primaryIdleSeconds = 12
    /// And swaps are rate-limited, so two live signals can't ping-pong.
    private static let swapHoldSeconds = 10

    private var primary = Slot()
    private var scout: Slot?
    private var rate = 0
    private var scratch: [Int16] = []
    private var samplesIntoBucket = 0
    private var samplesSinceSwap = 0
    private var toneHz: Float = 0
    private var level: Float = 0
    /// Slow-rising minimum tracker of buffer RMS: what "quiet" means right now.
    private var noiseLevel: Float = 1
    private var quietStreak = 0
    private var reported = Update(text: "", wpm: -1, toneHz: -1, tonePresent: false, level: -1)

    func createDecoder(inputRate: UInt32) -> Bool {
        destroyDecoder()
        rate = Int(inputRate)
        samplesIntoBucket = 0
        // Start with the swap hold already elapsed: the hold exists to stop
        // ping-ponging between two live signals, not to delay the first rescue.
        samplesSinceSwap = Self.swapHoldSeconds * rate
        toneHz = 0; level = 0
        noiseLevel = 1
        quietStreak = 0
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
        cfg.on_symbol = { text, _, user in
            guard let text, let user else { return }
            let slot = Unmanaged<Slot>.fromOpaque(user).takeUnretainedValue()
            let symbol = String(cString: text)
            slot.pendingText += symbol
            // Word spaces are punctuation, not evidence of copy: they arrive
            // by timeout even in dead air, so they don't refresh the counters.
            if symbol != " " {
                slot.bucketSymbols[slot.head] += 1
                slot.samplesSinceSymbol = 0
            }
        }
        cfg.on_status = { wpm, _, tonePresent, user in
            guard let user else { return }
            let slot = Unmanaged<Slot>.fromOpaque(user).takeUnretainedValue()
            slot.wpm = wpm
            slot.tonePresent = tonePresent
        }

        primary = Slot()
        cfg.user = Unmanaged.passUnretained(primary).toOpaque()
        primary.decoder = cw_decoder_create(&cfg)
        guard primary.decoder != nil else { return false }

        // The scout is a resilience layer; if it can't allocate, the primary
        // still decodes the way the firmware does.
        let second = Slot()
        cfg.user = Unmanaged.passUnretained(second).toOpaque()
        second.decoder = cw_decoder_create(&cfg)
        scout = second.decoder != nil ? second : nil
        return true
    }

    func destroyDecoder() {
        if let decoder = primary.decoder { cw_decoder_destroy(decoder) }
        primary.decoder = nil
        if let decoder = scout?.decoder { cw_decoder_destroy(decoder) }
        scout = nil
    }

    /// Convert one Float32 buffer to int16 PCM, meter it, and push it through
    /// both cores. Runs on the realtime tap thread.
    func feed(_ buffer: AVAudioPCMBuffer) {
        guard let decoder = primary.decoder, let data = buffer.floatChannelData?[0] else { return }
        let count = Int(buffer.frameLength)
        guard count > 0 else { return }
        if scratch.count < count { scratch = [Int16](repeating: 0, count: count) }

        primary.retuneMeter(to: cw_decoder_tone_hz(decoder), rate: rate)
        if let scout, let scoutDecoder = scout.decoder {
            scout.retuneMeter(to: cw_decoder_tone_hz(scoutDecoder), rate: rate)
        }
        let pCoeff = primary.meterCoeff
        let sCoeff = scout?.meterCoeff ?? 0
        var p1: Float = 0, p2: Float = 0, s1: Float = 0, s2: Float = 0
        var energy: Float = 0
        for i in 0..<count {
            let raw = data[i]
            let sample = raw.isFinite ? max(-1, min(1, raw)) : 0
            energy += sample * sample
            scratch[i] = Int16(sample * 32000)
            let p0 = sample + pCoeff * p1 - p2
            p2 = p1; p1 = p0
            let s0 = sample + sCoeff * s1 - s2
            s2 = s1; s1 = s0
        }
        level = (energy / Float(count)).squareRoot()
        let norm = Float(count)
        primary.bucketMag[primary.head] +=
            max(0, p1 * p1 + p2 * p2 - pCoeff * p1 * p2).squareRoot() / norm
        if let scout {
            scout.bucketMag[scout.head] +=
                max(0, s1 * s1 + s2 * s2 - sCoeff * s1 * s2).squareRoot() / norm
        }

        scratch.withUnsafeBufferPointer {
            cw_decoder_feed(decoder, $0.baseAddress, count)
            if let scoutDecoder = scout?.decoder {
                cw_decoder_feed(scoutDecoder, $0.baseAddress, count)
            }
        }
        toneHz = cw_decoder_tone_hz(decoder)
        advanceClock(by: count)
        supervise()
    }

    private func advanceClock(by count: Int) {
        samplesSinceSwap += count
        primary.samplesSinceReset += count
        primary.samplesSinceSymbol += count
        scout?.samplesSinceReset += count
        scout?.samplesSinceSymbol += count
        samplesIntoBucket += count
        while samplesIntoBucket >= rate {
            samplesIntoBucket -= rate
            primary.advanceBucket()
            scout?.advanceBucket()
        }
        // Quiet tracking: the noise reference sinks to the softest buffer and
        // creeps back up a few percent a second, so "quiet" stays honest as
        // conditions drift.
        noiseLevel = min(noiseLevel * 1.001 + 1e-6, max(level, 1e-6))
        if level < max(noiseLevel * 2.5, 0.0025) {
            quietStreak += 1
        } else {
            quietStreak = 0
        }
    }

    /// The pitch-lock referee; runs on the tap thread after every buffer.
    private func supervise() {
        guard let scout else { return }

        if abs(scout.meterHz - primary.meterHz) >= Self.swapMinHzApart,
           scout.symbols(last: Self.scoutIdleSeconds) >= 2,
           samplesSinceSwap >= Self.swapHoldSeconds * rate {
            let scoutMag = scout.magnitude(last: Self.magSeconds)
            if scoutMag > Self.swapMagFloor,
               scoutMag >= Self.swapMagRatio * primary.magnitude(last: Self.magSeconds) {
                // The scout's pitch is where the energy actually is, and it is
                // decoding it. Its recent copy is still in pendingText and
                // reaches the UI on this very drain; the deposed primary
                // restarts acquisition from the next quiet stretch.
                let deposed = primary
                primary = scout
                self.scout = deposed
                deposed.reset()
                samplesSinceSwap = 0
                if let decoder = primary.decoder { toneHz = cw_decoder_tone_hz(decoder) }
                return
            }
        }

        // Re-arm the scout the moment sustained quiet sets in and it isn't
        // mid-copy: its job is to be freshly searching when the next signal
        // starts, and quiet is the safe moment — a reset inside a continuous
        // tone calibrates the noise floor onto the tone and the core goes
        // deaf for seconds (measured on the vendored core). The edge trigger
        // fires once per quiet stretch; the idle rule (with a force fallback
        // for wall-to-wall sound) catches everything else, because a decoder
        // parked on a dead pitch hears nothing forever.
        if quietStreak == Self.quietBuffers,
           scout.samplesSinceSymbol >= rate,
           scout.samplesSinceReset >= 2 * rate {
            scout.reset()
        } else if scout.symbols(last: Self.scoutIdleSeconds) == 0,
                  scout.samplesSinceReset >= Self.scoutIdleSeconds * rate,
                  quietStreak >= Self.quietBuffers
                   || scout.samplesSinceReset >= Self.scoutForceSeconds * rate {
            scout.reset()
        }

        // A long-idle primary re-arms too: nothing is lost (it decoded nothing
        // all window), and its next lock starts from a current noise floor.
        if primary.symbols(last: Self.primaryIdleSeconds) == 0,
           primary.samplesSinceReset >= Self.primaryIdleSeconds * rate,
           quietStreak >= Self.quietBuffers {
            primary.reset()
        }

        // A scout that shadows the primary's own healthy copy accumulates
        // text nobody will read; keep the backlog bounded.
        if scout.pendingText.count > 64 {
            scout.pendingText = String(scout.pendingText.suffix(32))
        }
    }

    /// What changed since the last drain, or nil when nothing worth a hop to
    /// the main actor happened in this buffer.
    func drain() -> Update? {
        let update = Update(text: primary.pendingText, wpm: primary.wpm, toneHz: toneHz,
                            tonePresent: primary.tonePresent, level: level)
        primary.pendingText = ""
        let newsworthy = !update.text.isEmpty
            || update.tonePresent != reported.tonePresent
            || update.wpm != reported.wpm
            || update.toneHz != reported.toneHz
            || abs(update.level - reported.level) > 0.01
        guard newsworthy else { return nil }
        reported = update
        return update
    }
}
