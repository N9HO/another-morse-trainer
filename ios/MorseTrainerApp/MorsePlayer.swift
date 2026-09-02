import Foundation
import AVFoundation
import os

/// Generates and plays the sound of a Morse character/word/prosign.
///
/// Design: a single, persistent `AVAudioSourceNode` is wired into the engine
/// once and left running. Each time we play something we hand the node a
/// `MorseSynth` — a segment list plus a cursor — and its render callback
/// *synthesises* the tones (clean sine, short raised-cosine ramps so they don't
/// click) one sample at a time, then emits silence. Because the node never
/// stops and nothing is allocated or scheduled per-tone, there's no
/// start-of-tone clipping and no interrupt clicks.
///
/// It used to pre-render the whole sound into a `[Float]` first. A Code Exam at
/// novice speed is roughly 22 million samples — ~88 MB — built on the main
/// thread before a single note was heard, which made Story and Exam at slow
/// effective speeds the app's memory and main-thread hot spot. Streaming costs
/// a segment list of a few hundred entries whatever the passage length, and the
/// synthesis maths now lives in MorseKit where `MorseKitCheck` can actually
/// test it against `fixtures/render.json`.
///
/// The "finished" signal is **time-based** (scheduled for the exact known
/// duration of the sound) rather than depending on an audio callback, so the
/// quiz loop can never get stuck waiting on the audio system.
final class MorsePlayer {

    /// `var` rather than `let` so it can be replaced wholesale after a
    /// media-services reset, which invalidates every audio object in the process.
    private var engine = AVAudioEngine()
    private var sourceNode: AVAudioSourceNode!
    private let sampleRate: Double = 44_100
    private let rampSeconds: Double = 0.005
    private let amplitude: Float = 0.9

    // Shared with the real-time render thread. `OSAllocatedUnfairLock` is the
    // safe Swift wrapper (a raw os_unfair_lock accessed via &self.lock is
    // undefined behavior and was crashing the app).
    private struct Playback {
        /// The streaming path. `MorseSynth` walks a segment list a sample at a
        /// time, so a long passage costs a few hundred segments instead of tens
        /// of millions of Floats built on the main thread before playback could
        /// even start. `.silent` has no segments and emits nothing.
        var synth = MorseSynth.silent
        var cursor = MorseSynth.Cursor()
        /// The pileup path, which still materialises: several voices summed,
        /// each with its own pitch, speed, QSB envelope and gain, plus band
        /// noise and a peak normalisation over the finished mix. None of that
        /// can be decided one sample ahead. Pileup transmissions are callsigns
        /// and short exchanges, so this is bounded by what a pileup *is* —
        /// unlike Story and Exam, which is where the hot spot actually was.
        var buffer: [Float] = []
        var bufferCursor: Int = 0
        /// Continuous background-noise amplitude (issue #29). Non-zero means the
        /// node emits a faint band hiss instead of digital silence between
        /// tones, which both simulates QRN and — the reason it exists — keeps
        /// Bluetooth earbuds from idling and swallowing the first character of
        /// the next transmission. Read and advanced on the render thread.
        var noise: Float = 0
        var noiseState: UInt64 = 0x2545F4914F6CDD1D
        var noiseLast: Float = 0
    }
    private let state = OSAllocatedUnfairLock(initialState: Playback())

    /// Distinguishes completion callbacks so a previous tone's timer can't
    /// fire for the current one.
    private var generation = 0
    /// Held for the object's lifetime once taken: the tone engine is the thing
    /// that makes `.playback` the session's resting state (see AudioSession).
    private var sessionClaim: AudioSession.Claim?
    /// The `AudioSession.resetGeneration` this engine was built against.
    private var builtForReset = 0

    init() {
        buildEngine()
        // Pre-warm immediately so the first real tone isn't lost to cold-start.
        activate()
    }

    deinit {
        if let sessionClaim { AudioSession.shared.release(sessionClaim) }
    }

    /// Attach a fresh source node to the current engine. The render state lives
    /// in `state`, which is a `let` and outlives any rebuild, so a replacement
    /// node picks up mid-tone exactly where the old one left off.
    private func buildEngine() {
        builtForReset = AudioSession.shared.resetGeneration
        let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
        sourceNode = AVAudioSourceNode { [weak self] _, _, frameCount, audioBufferList in
            let abl = UnsafeMutableAudioBufferListPointer(audioBufferList)
            let frames = Int(frameCount)
            guard let self else {
                for buffer in abl {
                    memset(buffer.mData, 0, Int(buffer.mDataByteSize))
                }
                return noErr
            }
            let out = abl[0].mData!.assumingMemoryBound(to: Float.self)
            self.state.withLock { play in
                // Exactly one source is live at a time: setSynth and setBuffer
                // each clear the other, so this is a choice, not a mix.
                let streaming = play.buffer.isEmpty
                let count = play.buffer.count
                var c = play.bufferCursor
                let level = play.noise
                var st = play.noiseState
                var last = play.noiseLast
                for i in 0..<frames {
                    var v: Float = 0
                    if streaming {
                        // Allocation-free and lock-free inside; see MorseSynth.
                        v = play.synth.next(&play.cursor)
                    } else if c < count {
                        v = play.buffer[c]; c += 1
                    }
                    if level > 0 {
                        // Cheap LCG white noise, one-pole lowpassed: raw white
                        // hiss is harsh and fatiguing, real band noise is softer.
                        // Top 32 bits taken as a *signed* int so the noise
                        // swings both ways — the low half alone would be a DC
                        // offset, not a sound.
                        st = st &* 6364136223846793005 &+ 1442695040888963407
                        let white = Float(Int32(truncatingIfNeeded: st >> 32)) / Float(Int32.max)
                        last += 0.2 * (white - last)
                        v += last * level
                    }
                    out[i] = min(1, max(-1, v))
                }
                play.bufferCursor = c
                play.noiseState = st
                play.noiseLast = last
            }
            return noErr
        }
        engine.attach(sourceNode)
        engine.connect(sourceNode, to: engine.mainMixerNode, format: format)
    }

    /// Claim the session for playback (once), rebuild if the media server has
    /// restarted since we last built, and ensure the engine is running.
    ///
    /// `.playback` keeps the tone playing with the screen locked / app
    /// backgrounded (paired with UIBackgroundModes = audio) for hands-free
    /// Listen mode. The claim is idempotent and AudioSession suppresses a repeat
    /// configuration, which is what keeps this cheap enough to call per play —
    /// re-poking the session on every play was causing intermittent dropouts.
    ///
    /// Recovery is checked here rather than driven by an `AudioSession.observe`
    /// callback on purpose. Every path that makes a sound already comes through
    /// this method, so a poll costs one integer compare and needs no handler —
    /// and a handler would have to capture this class, which is not `Sendable`,
    /// across an isolation boundary. AppModel calls this on the way out of an
    /// interruption so the recovery does not wait for the next tone.
    func activate() {
        if sessionClaim == nil {
            sessionClaim = AudioSession.shared.claim(.playback)
        }
        if AudioSession.shared.resetGeneration != builtForReset {
            // The media server restarted. The engine and node are dead objects —
            // they cannot be restarted, only replaced. `state` is a `let` and
            // survives, so a rebuilt node resumes mid-tone where the old one
            // stopped.
            engine = AVAudioEngine()
            buildEngine()
        }
        if !engine.isRunning {
            engine.prepare()
            try? engine.start()
        }
    }

    func stop() {
        state.withLock { play in
            play.synth = MorseSynth.silent
            play.cursor = MorseSynth.Cursor()
            play.buffer = []
            play.bufferCursor = 0
        }
    }

    /// Set the continuous background-noise floor, 0…1 (issue #29). The engine
    /// and its source node already run for the app's lifetime, so this needs no
    /// extra stream — it just changes what the node emits when it has no tone
    /// to play, which is exactly what keeps a Bluetooth route from going idle.
    func setNoiseLevel(_ level: Float) {
        let clamped = min(1, max(0, level))
        state.withLock { $0.noise = clamped }
        if clamped > 0 { activate() }
    }

    // MARK: - Playing

    /// Play one character (convenience).
    func play(character: Character,
              frequency: Double,
              timing: MorseTiming,
              onFinished: @escaping () -> Void) {
        play(playable: .text(String(character)), frequency: frequency,
             timing: timing, onFinished: onFinished)
    }

    /// Play a playable and call `onFinished` (on the main queue) after its exact
    /// duration. This drives the time-to-recognize clock.
    func play(playable: MorseItem.Playable,
              frequency: Double,
              timing: MorseTiming,
              onFinished: @escaping () -> Void) {
        activate()
        // Built here, on the calling thread: a few hundred segments, not
        // millions of samples. The audio thread does the synthesis.
        let synth = makeSynth(playable: playable, timing: timing, frequency: frequency)
        guard synth.totalSamples > 0 else { onFinished(); return }

        generation += 1
        let token = generation
        setSynth(synth)

        let duration = Double(synth.totalSamples) / sampleRate
        DispatchQueue.main.asyncAfter(deadline: .now() + duration) { [weak self] in
            guard let self, self.generation == token else { return }
            onFinished()
        }
    }

    /// Replay the current sound without affecting the finished-timer (used by
    /// the optional replay button, which must not disturb the TTR clock).
    /// Returns the sound's duration in seconds (0 if nothing to play), so callers
    /// scheduling another replay can wait for this one to finish first.
    @discardableResult
    func replaySound(playable: MorseItem.Playable,
                     frequency: Double,
                     timing: MorseTiming) -> TimeInterval {
        activate()
        let synth = makeSynth(playable: playable, timing: timing, frequency: frequency)
        guard synth.totalSamples > 0 else { return 0 }
        setSynth(synth)
        return Double(synth.totalSamples) / sampleRate
    }

    // MARK: - Pileup (multiple simultaneous transmissions)

    /// One station's transmission in a pileup. Rendered at its own pitch/speed
    /// and summed with the others, offset by `startDelay`, so callers overlap —
    /// zero-beat (same tone) or split (different tone), just like a real pileup.
    struct PileupVoice {
        let text: String
        let frequency: Double
        let timing: MorseTiming
        let gain: Float            // 0…1 relative loudness
        let startDelay: TimeInterval
        let qsbRate: Double?       // slow-fade rate in Hz; nil = steady signal
    }

    /// Mix `voices` into one buffer and play it. Optional `qrn` adds atmospheric
    /// hiss across the whole band. `onFinished` fires after the longest voice.
    func playPileup(_ voices: [PileupVoice],
                    qrn: Float = 0,
                    onFinished: @escaping () -> Void) {
        activate()
        let mixed = mixPileup(voices, qrn: qrn)
        guard !mixed.isEmpty else { onFinished(); return }
        generation += 1
        let token = generation
        setBuffer(mixed)
        let duration = Double(mixed.count) / sampleRate
        DispatchQueue.main.asyncAfter(deadline: .now() + duration) { [weak self] in
            guard let self, self.generation == token else { return }
            onFinished()
        }
    }

    private func mixPileup(_ voices: [PileupVoice], qrn: Float) -> [Float] {
        let rendered = voices.map { v -> (samples: [Float], offset: Int, gain: Float, qsb: Double?) in
            (makeSynth(playable: .text(v.text), timing: v.timing, frequency: v.frequency).renderAll(),
             max(0, Int(v.startDelay * sampleRate)), v.gain, v.qsbRate)
        }
        let total = rendered.map { $0.offset + $0.samples.count }.max() ?? 0
        guard total > 0 else { return [] }
        var out = [Float](repeating: 0, count: total)

        for r in rendered {
            let qsbOmega = r.qsb.map { 2.0 * Double.pi * $0 / sampleRate }
            for i in 0..<r.samples.count {
                var a = r.samples[i] * r.gain
                if let w = qsbOmega {
                    // Gentle 0.35…1.0 fade so some signals swell and dip.
                    let env = 0.675 + 0.325 * sin(w * Double(r.offset + i))
                    a *= Float(env)
                }
                out[r.offset + i] += a
            }
        }

        if qrn > 0 {
            var st: UInt64 = 0x2545F4914F6CDD1D
            for i in 0..<total {
                st = st &* 6364136223846793005 &+ 1442695040888963407
                let n = Float(Int32(truncatingIfNeeded: st >> 33)) / Float(Int32.max)
                out[i] += n * qrn
            }
        }

        // Sum can exceed ±1 with several loud callers — scale down to avoid hard
        // clipping (a busy pileup is loud, which is realistic).
        var peak: Float = 0
        for v in out { let a = abs(v); if a > peak { peak = a } }
        if peak > 1 { let inv = 1 / peak; for i in 0..<total { out[i] *= inv } }
        return out
    }

    /// Hand the render callback a new program to synthesise, from the start.
    private func setSynth(_ new: MorseSynth) {
        state.withLock { play in
            play.synth = new
            play.cursor = MorseSynth.Cursor()
            play.buffer = []          // the two sources are exclusive
            play.bufferCursor = 0
        }
    }

    /// Hand it a pre-mixed buffer instead — the pileup path, which cannot be
    /// decided a sample ahead.
    private func setBuffer(_ new: [Float]) {
        state.withLock { play in
            play.buffer = new
            play.bufferCursor = 0
            play.synth = MorseSynth.silent
            play.cursor = MorseSynth.Cursor()
        }
    }

    private func makeSynth(playable: MorseItem.Playable,
                           timing: MorseTiming,
                           frequency: Double) -> MorseSynth {
        MorseSynth(playable: playable, timing: timing, sampleRate: sampleRate,
                   frequency: frequency, amplitude: amplitude, rampSeconds: rampSeconds)
    }

}
