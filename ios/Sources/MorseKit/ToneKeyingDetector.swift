import Foundation

/// Turns microphone audio into key-down / key-up edges with sample-accurate
/// timestamps, so a tone keyed on anything the phone can hear — a practice
/// oscillator, a keyer's sidetone, a rig's speaker, a signal generator — can
/// feed the Sending Analyzer like a key (#234, #235).
///
/// The vendored CW decoder core already hears tones, but it reports text and a
/// tone-present flag, not edge times, and it is not to be modified; timing
/// feedback needs the edges themselves. So this is a separate, small detector
/// that runs on the same capture. The spec is `fixtures/sending-analysis.json`
/// (`toneDetector.derivation`); the Kotlin twin is `ToneKeyingDetector.kt`.
///
/// Per block of `blockMs` (4 ms):
///  1. Goertzel magnitude (normalised to sine amplitude, 2|X|/N) at every
///     candidate pitch from 300 to 1200 Hz in 50 Hz steps, or only at the
///     locked pitch once there is one. The level is the largest.
///  2. Noise floor `nf` follows the level while the key is up (drops at half
///     the difference per block, rises at 0.2 %), frozen while it is down so a
///     long dah does not become the floor.
///  3. Peak `pk` is the loudest block of the current or last confirmed tone,
///     decaying toward the floor (0.3 % per block). Updated only while the key
///     is down, so a click cannot raise it.
///  4. Raw "on" when level > max(nf·snr, 0.002, nf + ½(pk − nf)); raw "off"
///     below max(nf·snr·0.7, 0.0015, nf + 0.35(pk − nf)). The gap between the
///     two is the hysteresis.
///  5. A change must hold for two blocks (8 ms); the edge is stamped at the
///     start of the first block of the new state. So a click is ignored and
///     each edge is off by at most about a block.
///  6. While unlocked, each confirmed-on block votes for its loudest pitch;
///     the first pitch to reach 12 votes (≈ 50 ms of tone) is locked.
public struct ToneKeyingDetector: Sendable {

    public struct Edge: Sendable, Equatable {
        public let isDown: Bool
        /// Milliseconds since the detector started (from the sample count).
        public let timeMs: Double
    }

    public static let blockMs: Double = 4
    public static let candidatePitches: [Double] = stride(from: 300.0, through: 1200.0, by: 50.0).map { $0 }
    public static let lockVotes = 12
    static let absoluteOn: Double = 0.002
    static let absoluteOff: Double = 0.0015

    public let sampleRate: Double
    public let blockSamples: Int
    /// Required peak-to-floor ratio (≈ 12 dB at 4). Lower hears quieter tones
    /// and more noise.
    public var snr: Double

    /// The pitch in use: the locked one, else the loudest candidate lately.
    public private(set) var pitchHz: Double?
    public private(set) var isLocked = false
    /// Level of the last block (sine amplitude, 0…1), the floor, and whether
    /// the key is (confirmed) down — for a level meter and a tone light.
    public private(set) var level: Double = 0
    public private(set) var noiseFloor: Double = 0
    public private(set) var isDown = false

    private var coeffs: [Double]
    private var s1: [Double]
    private var s2: [Double]
    private var fill = 0
    private var blockIndex = 0
    private var peak: Double = 0
    private var started = false
    private var pendingState: Bool?
    private var pendingSince = 0
    private var votes: [Int]
    private var lockedIndex: Int?
    /// The frequencies behind `coeffs`, index for index.
    private let pitches: [Double]

    public init(sampleRate: Double, snr: Double = 4, pitchHz: Double? = nil) {
        self.sampleRate = sampleRate
        self.snr = snr
        blockSamples = max(16, Int((sampleRate * Self.blockMs / 1000).rounded()))
        let pitches = pitchHz.map { [$0] } ?? Self.candidatePitches
        self.pitches = pitches
        coeffs = pitches.map { 2 * cos(2 * Double.pi * $0 / sampleRate) }
        s1 = [Double](repeating: 0, count: pitches.count)
        s2 = [Double](repeating: 0, count: pitches.count)
        votes = [Int](repeating: 0, count: pitches.count)
        if let pitchHz {
            self.pitchHz = pitchHz
            isLocked = true
            lockedIndex = 0
        }
    }


    /// Feed samples (nominal full scale ±1). Returns the edges confirmed by
    /// these samples, oldest first.
    public mutating func process(_ samples: UnsafeBufferPointer<Float>) -> [Edge] {
        var edges: [Edge] = []
        for raw in samples {
            let x = raw.isFinite ? Double(max(-1, min(1, raw))) : 0
            if let li = lockedIndex {
                let s0 = x + coeffs[li] * s1[li] - s2[li]
                s2[li] = s1[li]; s1[li] = s0
            } else {
                for b in 0..<coeffs.count {
                    let s0 = x + coeffs[b] * s1[b] - s2[b]
                    s2[b] = s1[b]; s1[b] = s0
                }
            }
            fill += 1
            if fill == blockSamples {
                if let e = finishBlock() { edges.append(e) }
                fill = 0
            }
        }
        return edges
    }

    /// Convenience for arrays (tests, the harness).
    public mutating func process(_ samples: [Float]) -> [Edge] {
        samples.withUnsafeBufferPointer { process($0) }
    }

    private mutating func finishBlock() -> Edge? {
        let n = Double(blockSamples)
        var best = 0.0
        var bestIndex = lockedIndex ?? 0
        let range = lockedIndex.map { $0...$0 } ?? 0...(coeffs.count - 1)
        for b in range {
            let power = max(0, s1[b] * s1[b] + s2[b] * s2[b] - coeffs[b] * s1[b] * s2[b])
            let mag = 2 * power.squareRoot() / n
            if mag > best { best = mag; bestIndex = b }
            s1[b] = 0; s2[b] = 0
        }
        level = best
        blockIndex += 1

        if !started {
            started = true
            noiseFloor = best
            peak = best
            return nil
        }
        if !isDown {
            noiseFloor += (best - noiseFloor) * (best < noiseFloor ? 0.5 : 0.002)
        } else {
            peak = max(peak, best)
        }
        peak = max(noiseFloor, peak - (peak - noiseFloor) * 0.003)

        let onThreshold = max(noiseFloor * snr, Self.absoluteOn, noiseFloor + 0.5 * (peak - noiseFloor))
        let offThreshold = max(noiseFloor * snr * 0.7, Self.absoluteOff, noiseFloor + 0.35 * (peak - noiseFloor))
        let raw = isDown ? best >= offThreshold : best > onThreshold

        var edge: Edge?
        if raw == isDown {
            pendingState = nil
        } else if pendingState == raw {
            // Second block in the new state: confirm, stamped at the first.
            isDown = raw
            pendingState = nil
            edge = Edge(isDown: raw, timeMs: Double(pendingSince) * n * 1000 / sampleRate)
            if raw { peak = max(peak, best) }
        } else {
            pendingState = raw
            pendingSince = blockIndex - 1
        }

        if isDown, lockedIndex == nil {
            votes[bestIndex] += 1
            pitchHz = pitches[bestIndex]
            if votes[bestIndex] >= Self.lockVotes {
                lockedIndex = bestIndex
                isLocked = true
            }
        }
        return edge
    }

    /// Back to a fresh start: floor re-learned, pitch search re-armed (unless
    /// the pitch was set by hand).
    public mutating func reset() {
        for b in 0..<s1.count { s1[b] = 0; s2[b] = 0 }
        fill = 0
        blockIndex = 0
        started = false
        isDown = false
        pendingState = nil
        level = 0
        noiseFloor = 0
        peak = 0
        votes = [Int](repeating: 0, count: votes.count)
        if pitches.count > 1 {
            lockedIndex = nil
            isLocked = false
            pitchHz = nil
        }
    }
}
