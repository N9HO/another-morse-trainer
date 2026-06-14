// KeyerEngine.swift
// Audio engine: local TX sidetone and scheduled RX playback.
//
// Two distinct audio paths feeding the main mixer:
//
//   TX (local sidetone):
//     AVAudioSourceNode generating sin(2π·f·t) gated by an atomic Bool.
//     5ms linear envelope at start/end to avoid clicks.
//
//   RX (received from server):
//     Pre-rendered AVAudioPCMBuffers scheduled on AVAudioPlayerNodes.
//     Pool of nodes keyed by sender's MIDI note so concurrent senders mix.
//
// See CLAUDE.md §3 for full audio model.

import AVFoundation
import OSLog

private let log = Logger(subsystem: "com.justinrogers.MorseTrainer", category: "audio")

@MainActor
public final class KeyerEngine {
    // MARK: - Configuration

    /// 5ms attack/release matches the web client's `OscillatorRampDuration`.
    /// Linear ramp; avoids the square-wave overtones of instant transitions.
    public nonisolated static let envelopeRampMs: Double = 5

    /// Master peak amplitude. 0.5 matches the web client's `txGain`.
    public nonisolated static let peakAmplitude: Float = 0.5

    // MARK: - Public state

    public var localTxToneMIDI: Int = 72 {
        didSet { txGenerator.frequency = Self.midiNoteToHz(localTxToneMIDI) }
    }

    public private(set) var sampleRate: Double = 48000
    public var lastRenderTime: AVAudioTime? {
        engine.outputNode.lastRenderTime
    }

    // MARK: - Engine

    private let engine = AVAudioEngine()
    private lazy var txGenerator = ToneGenerator(initialFrequency: 600)

    /// One AVAudioPlayerNode per unique sender MIDI tone. Lazy-created.
    private var rxPlayers: [Int: AVAudioPlayerNode] = [:]

    // MARK: - Lifecycle

    public init() {}

    public func start() throws {
        try configureAudioSession()

        let mixer = engine.mainMixerNode
        let mixerFormat = mixer.outputFormat(forBus: 0)
        sampleRate = mixerFormat.sampleRate

        // Use a consistent mono format for our sources; the mixer up-mixes.
        // AVAudioEngine.connect handles format conversion automatically.
        let monoFormat = AVAudioFormat(
            standardFormatWithSampleRate: sampleRate,
            channels: 1
        )!

        // TX path: continuous source node, gated by atomic bool inside.
        txGenerator.sampleRate = sampleRate
        txGenerator.frequency = Self.midiNoteToHz(localTxToneMIDI)
        engine.attach(txGenerator.node)
        engine.connect(txGenerator.node, to: mixer, format: monoFormat)

        try engine.start()
        let rate = sampleRate
        log.info("Audio engine started at \(rate) Hz")
    }

    public func stop() {
        engine.stop()
        rxPlayers.removeAll()
    }

    /// Force-stop everything. Mirror of `outputs.mjs` `Panic()`.
    public func panic() {
        txGenerator.setKeyDown(false)
        for player in rxPlayers.values {
            player.stop()
        }
    }

    private func configureAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playback,
            mode: .default,
            options: [.mixWithOthers, .duckOthers]
        )
        try session.setActive(true)
    }

    // MARK: - TX

    /// Local sidetone on. Called from key-down.
    public func beginTx() {
        txGenerator.setKeyDown(true)
    }

    /// Local sidetone off. Called from key-up.
    public func endTx() {
        txGenerator.setKeyDown(false)
    }

    // MARK: - RX

    /// Schedule a received tone for playback at the given local-clock time
    /// (ms since Unix epoch). The KeyerEngine converts to AVAudioTime and
    /// uses sample-accurate scheduling.
    ///
    /// - Parameters:
    ///   - midiNote: Sender's TX tone (MIDI note). Each unique note gets its
    ///     own AVAudioPlayerNode so concurrent senders mix correctly.
    ///   - durationMs: Tone duration.
    ///   - playAtLocalMs: When to start, in local clock ms.
    public func scheduleReceivedTone(
        midiNote: Int,
        durationMs: UInt16,
        playAtLocalMs: Int64
    ) {
        guard durationMs > 0 else { return }

        let player = getOrCreateRxPlayer(midiNote: midiNote)
        let buffer = renderToneBuffer(midiNote: midiNote, durationMs: durationMs)

        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let leadMs = max(0, playAtLocalMs - nowMs)

        if playAtLocalMs < nowMs {
            log.warning("Tone scheduled \(nowMs - playAtLocalMs)ms in the past; suggest increasing rxDelay")
            // Still play it — better late than dropped.
        }

        // Schedule via hostTime (mach_absolute_time). sampleTime is interpreted
        // in the player node's own render timeline, which starts at 0 when the
        // player is freshly attached — making engine.outputNode.lastRenderTime
        // useless here. hostTime is universal across nodes.
        let hostLead = AVAudioTime.hostTime(forSeconds: Double(leadMs) / 1000.0)
        let avTime = AVAudioTime(hostTime: mach_absolute_time() + hostLead)

        if !player.isPlaying {
            player.play()
        }
        player.scheduleBuffer(buffer, at: avTime, options: [], completionHandler: nil)
    }

    private func getOrCreateRxPlayer(midiNote: Int) -> AVAudioPlayerNode {
        if let existing = rxPlayers[midiNote] { return existing }
        let player = AVAudioPlayerNode()
        let monoFormat = AVAudioFormat(
            standardFormatWithSampleRate: sampleRate,
            channels: 1
        )!
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: monoFormat)
        rxPlayers[midiNote] = player
        return player
    }

    /// Render a single tone burst with attack/release ramps.
    private func renderToneBuffer(midiNote: Int, durationMs: UInt16) -> AVAudioPCMBuffer {
        let frequency = Self.midiNoteToHz(midiNote)
        let frames = AVAudioFrameCount(sampleRate * Double(durationMs) / 1000.0)
        let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames)!
        buffer.frameLength = frames

        let rampFrames = min(
            Int(sampleRate * Self.envelopeRampMs / 1000.0),
            Int(frames) / 2
        )

        let ptr = buffer.floatChannelData![0]
        let omega = 2.0 * .pi * frequency / sampleRate
        let amp = Self.peakAmplitude
        let total = Int(frames)

        for i in 0 ..< total {
            var envelope: Float = 1.0
            if i < rampFrames {
                envelope = Float(i) / Float(rampFrames)
            } else if i >= total - rampFrames {
                envelope = Float(total - i) / Float(rampFrames)
            }
            ptr[i] = sin(Float(omega * Double(i))) * amp * envelope
        }
        return buffer
    }

    // MARK: - Helpers

    /// MIDI note → frequency in Hz, equal temperament with A4 = 69 = 440 Hz.
    public static func midiNoteToHz(_ note: Int) -> Double {
        440.0 * pow(2.0, Double(note - 69) / 12.0)
    }
}

// MARK: - ToneGenerator

/// AVAudioSourceNode-based continuous sine with key gating.
///
/// The render block executes on the audio thread. We use a lock for the
/// small amount of state read each block (key state, frequency, phase). For
/// CW timing this is overkill — the block runs every ~10ms and the lock is
/// held for microseconds — but it keeps things obviously correct.
private final class ToneGenerator {
    let node: AVAudioSourceNode

    var frequency: Double {
        get { lock.withLock { state.frequency } }
        set { lock.withLock { state.frequency = newValue } }
    }

    var sampleRate: Double {
        get { lock.withLock { state.sampleRate } }
        set { lock.withLock { state.sampleRate = newValue } }
    }

    func setKeyDown(_ down: Bool) {
        lock.withLock { state.keyDown = down }
    }

    private final class State {
        var keyDown: Bool = false
        var currentGain: Float = 0
        var phase: Double = 0
        var frequency: Double = 600
        var sampleRate: Double = 48000
    }

    private let state = State()
    private let lock = NSLock()

    init(initialFrequency: Double) {
        let stateRef = state
        let lockRef = lock

        // The format here is the source node's *output* format. AVAudioEngine
        // negotiates with the connection's format param during engine.connect.
        // Standard 48k mono is a safe default; actual sampleRate is updated
        // before render via the State and reflected in the omega calc.
        let format = AVAudioFormat(standardFormatWithSampleRate: 48000, channels: 1)!
        state.frequency = initialFrequency

        node = AVAudioSourceNode(format: format) { _, _, frameCount, audioBufferList -> OSStatus in
            let abl = UnsafeMutableAudioBufferListPointer(audioBufferList)
            guard let buffer = abl.first else { return noErr }
            let ptr = buffer.mData!.assumingMemoryBound(to: Float.self)

            lockRef.lock()
            let target: Float = stateRef.keyDown ? KeyerEngine.peakAmplitude : 0
            let freq = stateRef.frequency
            let sampleRate = stateRef.sampleRate
            var phase = stateRef.phase
            var gain = stateRef.currentGain
            lockRef.unlock()

            // Linear ramp toward target. 5ms at sampleRate = rampFrames samples.
            let rampFrames = Float(KeyerEngine.envelopeRampMs * sampleRate / 1000.0)
            let perSampleStep: Float = target > gain
                ? (KeyerEngine.peakAmplitude / rampFrames)
                : -(KeyerEngine.peakAmplitude / rampFrames)

            let omega = 2.0 * .pi * freq / sampleRate
            let twoPi = 2.0 * .pi

            for i in 0 ..< Int(frameCount) {
                if gain != target {
                    gain += perSampleStep
                    if (perSampleStep > 0 && gain > target) || (perSampleStep < 0 && gain < target) {
                        gain = target
                    }
                }
                ptr[i] = Float(sin(phase)) * gain
                phase += omega
                if phase > twoPi { phase -= twoPi }
            }

            // Write back the advanced phase and gain.
            lockRef.lock()
            stateRef.phase = phase
            stateRef.currentGain = gain
            lockRef.unlock()

            // Mirror to remaining channels if multi-channel.
            for j in 1 ..< abl.count {
                let dst = abl[j].mData!.assumingMemoryBound(to: Float.self)
                memcpy(dst, ptr, Int(frameCount) * MemoryLayout<Float>.size)
            }
            return noErr
        }
    }
}
