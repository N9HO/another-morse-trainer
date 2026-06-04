import Foundation
import AVFoundation
import os

/// Generates and plays the sound of a Morse character/word/prosign.
///
/// Design: a single, persistent `AVAudioSourceNode` is wired into the engine
/// once and left running. Each time we play something we pre-render its samples
/// (clean sine tones with short raised-cosine ramps so they don't click) and
/// hand them to the node's render callback, which streams them out and then
/// emits silence. Because the node never stops and nothing is allocated or
/// scheduled per-tone, there's no start-of-tone clipping and no interrupt
/// clicks.
///
/// The "finished" signal is **time-based** (scheduled for the exact known
/// duration of the sound) rather than depending on an audio callback, so the
/// quiz loop can never get stuck waiting on the audio system.
final class MorsePlayer {

    private let engine = AVAudioEngine()
    private var sourceNode: AVAudioSourceNode!
    private let sampleRate: Double = 44_100
    private let rampSeconds: Double = 0.005
    private let amplitude: Float = 0.9

    // Shared with the real-time render thread. `OSAllocatedUnfairLock` is the
    // safe Swift wrapper (a raw os_unfair_lock accessed via &self.lock is
    // undefined behavior and was crashing the app).
    private struct Playback { var samples: [Float] = []; var cursor: Int = 0 }
    private let state = OSAllocatedUnfairLock(initialState: Playback())

    /// Distinguishes completion callbacks so a previous tone's timer can't
    /// fire for the current one.
    private var generation = 0
    private var didActivate = false

    init() {
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
                let count = play.samples.count
                var c = play.cursor
                for i in 0..<frames {
                    if c < count { out[i] = play.samples[c]; c += 1 } else { out[i] = 0 }
                }
                play.cursor = c
            }
            return noErr
        }
        engine.attach(sourceNode)
        engine.connect(sourceNode, to: engine.mainMixerNode, format: format)
        // Pre-warm immediately so the first real tone isn't lost to cold-start.
        activate()
    }

    /// Start the audio session/engine. The session is configured only once
    /// (re-poking it on every play was causing intermittent dropouts); the
    /// engine is simply ensured-running thereafter.
    func activate() {
        if !didActivate {
            #if os(iOS)
            let session = AVAudioSession.sharedInstance()
            // `.playback` keeps the tone playing with the screen locked / app
            // backgrounded (paired with UIBackgroundModes = audio) for
            // hands-free Listen mode. `.duckOthers` matches the category the
            // voice recogniser restores to, so switching between them doesn't
            // thrash the session.
            try? session.setCategory(.playback, mode: .default, options: [.duckOthers])
            try? session.setActive(true)
            #endif
            didActivate = true
        }
        if !engine.isRunning {
            engine.prepare()
            try? engine.start()
        }
    }

    func stop() {
        setSamples([])
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
        let floats = render(playable: playable, timing: timing, frequency: frequency)
        guard !floats.isEmpty else { onFinished(); return }

        generation += 1
        let token = generation
        setSamples(floats)

        let duration = Double(floats.count) / sampleRate
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
        let floats = render(playable: playable, timing: timing, frequency: frequency)
        guard !floats.isEmpty else { return 0 }
        setSamples(floats)
        return Double(floats.count) / sampleRate
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
        setSamples(mixed)
        let duration = Double(mixed.count) / sampleRate
        DispatchQueue.main.asyncAfter(deadline: .now() + duration) { [weak self] in
            guard let self, self.generation == token else { return }
            onFinished()
        }
    }

    private func mixPileup(_ voices: [PileupVoice], qrn: Float) -> [Float] {
        let rendered = voices.map { v -> (samples: [Float], offset: Int, gain: Float, qsb: Double?) in
            (render(playable: .text(v.text), timing: v.timing, frequency: v.frequency),
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

    private func setSamples(_ new: [Float]) {
        state.withLock { play in
            play.samples = new
            play.cursor = 0
        }
    }

    // MARK: - Rendering to float samples

    private func render(playable: MorseItem.Playable,
                        timing: MorseTiming,
                        frequency: Double) -> [Float] {
        let segments = self.segments(for: playable, timing: timing)
        var out: [Float] = []
        let rampSamples = max(1, Int(rampSeconds * sampleRate))
        let omega = 2.0 * Double.pi * frequency / sampleRate

        for segment in segments {
            let toneCount = Int(segment.tone * sampleRate)
            if toneCount > 0 {
                out.reserveCapacity(out.count + toneCount)
                for n in 0..<toneCount {
                    var amp = Double(amplitude)
                    if n < rampSamples {
                        amp *= 0.5 * (1 - cos(Double.pi * Double(n) / Double(rampSamples)))
                    } else if n >= toneCount - rampSamples {
                        let m = toneCount - n
                        amp *= 0.5 * (1 - cos(Double.pi * Double(m) / Double(rampSamples)))
                    }
                    out.append(Float(amp * sin(omega * Double(n))))
                }
            }
            let gapCount = Int(segment.gap * sampleRate)
            if gapCount > 0 { out.append(contentsOf: repeatElement(0, count: gapCount)) }
        }
        return out
    }

    private func segments(for playable: MorseItem.Playable,
                          timing: MorseTiming) -> [(tone: Double, gap: Double)] {
        switch playable {
        case .pattern(let pattern):
            let els = pattern.map { $0 == "." ? MorseCode.Element.dit : .dah }
            return withGaps(els, timing: timing, interElement: timing.elementGap, trailing: 0)

        case .text(let text):
            let chars = Array(text)
            var result: [(tone: Double, gap: Double)] = []
            for (ci, ch) in chars.enumerated() {
                // A space is a word gap: stretch the previous character's
                // trailing gap to a full word gap. Only QSO-style multi-word
                // transmissions contain spaces — single tokens are unaffected.
                if ch == " " {
                    if !result.isEmpty { result[result.count - 1].gap = timing.wordGap }
                    continue
                }
                let els = MorseCode.elements(for: ch)
                guard !els.isEmpty else { continue }
                let afterChar = ci == chars.count - 1 ? 0 : timing.characterGap
                result += withGaps(els, timing: timing,
                                   interElement: timing.elementGap, trailing: afterChar)
            }
            return result
        }
    }

    private func withGaps(_ elements: [MorseCode.Element],
                          timing: MorseTiming,
                          interElement: TimeInterval,
                          trailing: TimeInterval) -> [(tone: Double, gap: Double)] {
        elements.enumerated().map { i, el in
            let tone = el == .dit ? timing.dit : timing.dah
            let gap = i == elements.count - 1 ? trailing : interElement
            return (tone, gap)
        }
    }
}
