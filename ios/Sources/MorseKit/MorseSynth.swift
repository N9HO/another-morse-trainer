import Foundation

/// Turns a playable into Morse audio **one sample at a time**, without ever
/// materialising the whole sound.
///
/// This used to live inside `MorseTrainerApp/MorsePlayer.swift` as a `render()`
/// that returned `[Float]`. That had two problems, and moving it here fixes
/// both:
///
///  1. **Memory and latency.** A Code Exam at novice speed is roughly 22
///     million samples — ~88 MB of `Float` — built on the main thread before a
///     single note is heard. Story and Exam at slow effective speeds were the
///     app's memory and main-thread hot spot for exactly this reason. A
///     `Cursor` walked by the audio callback needs none of it: the segment list
///     is a few hundred entries whatever the passage length.
///  2. **Testability.** Living in the app target, the synthesis maths could not
///     be reached by `MorseKitCheck` or by any test at all. Here it can, and
///     `fixtures/render.json` pins it against the Kotlin twin.
///
/// Realtime contract: `next(_:)` allocates nothing, locks nothing and calls no
/// Foundation API. It is safe to call from an `AVAudioSourceNode` render block.
public struct MorseSynth: Sendable {

    /// One keyed element: a tone, then the silence that follows it. Lengths are
    /// in **samples**, converted once up front, because the audio callback must
    /// not be doing seconds-to-samples arithmetic per frame — and because
    /// truncation has to happen exactly once, or the total and the walk disagree.
    public struct Segment: Sendable, Equatable {
        public let toneSamples: Int
        public let gapSamples: Int

        public init(toneSamples: Int, gapSamples: Int) {
            self.toneSamples = max(0, toneSamples)
            self.gapSamples = max(0, gapSamples)
        }
    }

    /// Where playback has got to. Cheap to copy; the audio callback keeps one.
    public struct Cursor: Sendable, Equatable {
        public var segmentIndex: Int = 0
        public var sampleInSegment: Int = 0
        public init() {}
    }

    public let segments: [Segment]
    public let sampleRate: Double
    public let amplitude: Float
    /// Radians per sample. Phase restarts at zero for every tone, matching the
    /// pre-rendering version exactly — the ramps make the discontinuity
    /// inaudible, and resetting keeps a tone's waveform independent of what
    /// came before it.
    public let omega: Double
    /// Full raised-cosine ramp length, before the short-tone clamp below.
    public let fullRampSamples: Int

    /// Total length of the whole playable, in samples.
    public let totalSamples: Int

    public init(segments: [Segment],
                sampleRate: Double,
                frequency: Double,
                amplitude: Float = 0.9,
                rampSeconds: Double = 0.005) {
        self.segments = segments
        self.sampleRate = sampleRate
        self.amplitude = amplitude
        self.omega = 2.0 * Double.pi * frequency / sampleRate
        self.fullRampSamples = max(1, Int(rampSeconds * sampleRate))
        self.totalSamples = segments.reduce(0) { $0 + $1.toneSamples + $1.gapSamples }
    }

    /// Build the segment list for a playable at a given timing.
    ///
    /// Seconds are converted to samples here, once, with the same truncation the
    /// pre-rendering version used, so the sound is sample-identical.
    public static func segments(for playable: MorseItem.Playable,
                                timing: MorseTiming,
                                sampleRate: Double) -> [Segment] {
        func toSamples(_ seconds: TimeInterval) -> Int { Int(seconds * sampleRate) }

        func withGaps(_ elements: [MorseCode.Element],
                      interElement: TimeInterval,
                      trailing: TimeInterval) -> [Segment] {
            elements.enumerated().map { i, el in
                let tone = el == .dit ? timing.dit : timing.dah
                let gap = i == elements.count - 1 ? trailing : interElement
                return Segment(toneSamples: toSamples(tone), gapSamples: toSamples(gap))
            }
        }

        switch playable {
        case .pattern(let pattern):
            let els = pattern.map { $0 == "." ? MorseCode.Element.dit : .dah }
            return withGaps(els, interElement: timing.elementGap, trailing: 0)

        case .text(let text):
            let chars = Array(text)
            var result: [Segment] = []
            for (ci, ch) in chars.enumerated() {
                // A space is a word gap: stretch the previous character's
                // trailing gap to a full word gap. Only QSO-style multi-word
                // transmissions contain spaces — single tokens are unaffected.
                if ch == " " {
                    if let last = result.last {
                        result[result.count - 1] = Segment(toneSamples: last.toneSamples,
                                                           gapSamples: toSamples(timing.wordGap))
                    }
                    continue
                }
                let els = MorseCode.elements(for: ch)
                guard !els.isEmpty else { continue }
                let afterChar = ci == chars.count - 1 ? 0 : timing.characterGap
                result += withGaps(els, interElement: timing.elementGap, trailing: afterChar)
            }
            return result
        }
    }

    /// Convenience: build a synth straight from a playable.
    public init(playable: MorseItem.Playable,
                timing: MorseTiming,
                sampleRate: Double,
                frequency: Double,
                amplitude: Float = 0.9,
                rampSeconds: Double = 0.005) {
        self.init(segments: MorseSynth.segments(for: playable, timing: timing, sampleRate: sampleRate),
                  sampleRate: sampleRate, frequency: frequency,
                  amplitude: amplitude, rampSeconds: rampSeconds)
    }

    /// A synth with nothing to play. Useful as a resting value so a realtime
    /// callback can hold a non-optional `MorseSynth` and avoid unwrapping — an
    /// optional struct holding an array is exactly the shape that tempts a copy
    /// onto the audio thread.
    public static let silent = MorseSynth(segments: [], sampleRate: 44_100, frequency: 600)

    /// True once the cursor has nothing left to emit.
    ///
    /// Deliberately not `segmentIndex >= segments.count`: after exactly
    /// `totalSamples` calls to `next(_:)` the cursor sits *at the end of* the
    /// last segment, not past it, because `next(_:)` only advances the index on
    /// its way in. Asking the question properly — is there any sample left
    /// anywhere ahead? — is what makes "played to completion" testable.
    public func isFinished(_ cursor: Cursor) -> Bool {
        var index = cursor.segmentIndex
        var offset = cursor.sampleInSegment
        while index < segments.count {
            if offset < segments[index].toneSamples + segments[index].gapSamples { return false }
            index += 1
            offset = 0
        }
        return true
    }

    /// Next sample, advancing `cursor`. Returns 0 once finished, so a caller
    /// that keeps asking simply gets silence rather than having to check.
    ///
    /// Allocation-free and branch-light by design: this runs per frame on the
    /// audio thread.
    public func next(_ cursor: inout Cursor) -> Float {
        // Skip past any zero-length segments so a degenerate program (a
        // non-positive duration truncating to nothing) cannot stall the walk.
        while cursor.segmentIndex < segments.count {
            let segment = segments[cursor.segmentIndex]
            let span = segment.toneSamples + segment.gapSamples
            if cursor.sampleInSegment < span { break }
            cursor.segmentIndex += 1
            cursor.sampleInSegment = 0
        }
        guard cursor.segmentIndex < segments.count else { return 0 }

        let segment = segments[cursor.segmentIndex]
        let n = cursor.sampleInSegment
        cursor.sampleInSegment += 1

        guard n < segment.toneSamples else { return 0 }   // in the trailing gap
        return sample(n: n, toneSamples: segment.toneSamples)
    }

    /// One tone sample: a sine under a raised-cosine envelope.
    ///
    /// The ramp is clamped to half the tone so rise and fall can never overlap.
    /// Past ~120 WPM a dit is shorter than two 5 ms ramps, and without the clamp
    /// the fall is skipped entirely and the tone ends at full amplitude — an
    /// audible click (issue #79). At every speed the app offers this is the
    /// unchanged 5 ms.
    @inline(__always)
    private func sample(n: Int, toneSamples: Int) -> Float {
        let rampSamples = max(1, min(fullRampSamples, toneSamples / 2))
        var amp = Double(amplitude)
        if n < rampSamples {
            amp *= 0.5 * (1 - cos(Double.pi * Double(n) / Double(rampSamples)))
        } else if n >= toneSamples - rampSamples {
            let m = toneSamples - n
            amp *= 0.5 * (1 - cos(Double.pi * Double(m) / Double(rampSamples)))
        }
        return Float(amp * sin(omega * Double(n)))
    }

    /// Materialise the whole thing. Only for callers that genuinely need a
    /// buffer — the pileup mixer, and the fixture checks. The streaming path
    /// exists precisely so this is not on the hot path.
    public func renderAll() -> [Float] {
        var out = [Float](repeating: 0, count: totalSamples)
        var cursor = Cursor()
        for i in 0..<totalSamples { out[i] = next(&cursor) }
        return out
    }
}
