import Foundation

/// Noise blanker for the CW decoder's microphone path (#195).
///
/// QRN — atmospheric static heard through a rig's speaker — is mostly short
/// broadband crashes, and each one lands in the vendored core's tone bin as a
/// spurious mark that splits a character or adds a stray E. Hiss the core
/// handles on its own down to about 6 dB SNR; crashes break it at one per
/// second. This is the receiver noise blanker's answer, applied to PCM before
/// `cw_decoder_feed`: detect the impulse where it is unmistakable (the
/// wideband residual, where a tone leaves nothing and a crash leaves nearly
/// everything), and while it lasts substitute the tone's own continuation so
/// a crash inside a mark fills the hole instead of punching one.
///
/// The signal path is pass-through: samples reach the core untouched unless a
/// blank is active. Both apps run the same arithmetic, and
/// `fixtures/cw-prefilter.json` pins it; the fixture's `derivation` block is
/// the spec this implements, so read that before changing a constant here.
///
/// Per sample x (Float, nominal full scale ±1), in this order:
///  1. Detector band-pass at the pitch (RBJ constant-peak form, Q = 1):
///     yd = b0·x + b2·x[n-2] − a1·yd[n-1] − a2·yd[n-2]
///  2. In-band envelope  e = max(|yd|, e·envDecay)            (2 ms)
///  3. Residual          r = x − yd;  R += (|r| − R)·rAlpha     (100 ms mean)
///  4. Trigger: |r| > max(k·e, kR·R, floor), with k = 1.5 when clear and 0.5
///     while a blank is running (hysteresis eats the crash's tail), starts or
///     extends a blank of `hold` samples (1 ms).
///  5. Continuation resonator (same band-pass form, Q = 6) is fed x while
///     clear; while blanked it runs on as g·(c·y[n-1] − y[n-2]) with input 0.
///  6. Output: x when clear; the resonator's output while blanked.
///
/// Why these shapes: the residual/envelope ratio is scale-invariant, so the
/// trigger follows a crash all the way down its decay instead of passing the
/// tail (which still clears the core's threshold). R keeps steady hiss from
/// tripping it — hiss raises R, a 20 ms crash barely moves it. The floor
/// keeps a quiet room from being blanked at all: a blanker that squelches
/// silence feeds the core a residue at the blanker's own pitch, which is the
/// mis-lock the decoder's scout exists to escape. A narrow band-pass in the
/// signal path was measured and rejected: it rings on crashes and slows the
/// speed estimate, and the continuation alone is what carries a mark across.
public struct CWPrefilter: Sendable {

    public static let defaultPitchHz: Float = 700
    static let detectorQ: Float = 1
    static let resonatorQ: Float = 6
    static let triggerRatio: Float = 1.5
    static let holdRatio: Float = 0.5
    static let noiseRatio: Float = 2.5
    static let floor: Float = 0.02
    static let holdMs: Float = 1
    static let envelopeMs: Float = 2
    static let noiseMs: Float = 100
    static let decayMs: Float = 25

    public private(set) var sampleRate: Float
    public private(set) var pitchHz: Float

    // Detector band-pass and its history.
    private var db0: Float = 0, db2: Float = 0, da1: Float = 0, da2: Float = 0
    private var dx1: Float = 0, dx2: Float = 0, dy1: Float = 0, dy2: Float = 0
    // Continuation resonator and its history.
    private var cb0: Float = 0, cb2: Float = 0, ca1: Float = 0, ca2: Float = 0
    private var cx1: Float = 0, cx2: Float = 0, cy1: Float = 0, cy2: Float = 0
    private var cosine: Float = 0
    // Envelopes and the blank countdown.
    private var envelope: Float = 0
    private var residualLevel: Float = 0
    private var remaining = 0

    private let hold: Int
    private let envDecay: Float
    private let rAlpha: Float
    private let decay: Float

    /// RBJ constant-peak band-pass, normalised so a0 = 1:
    /// w = 2π·pitch/rate, α = sin(w)/(2Q), b0 = α/(1+α), b2 = −b0,
    /// a1 = −2cos(w)/(1+α), a2 = (1−α)/(1+α). b1 is zero.
    public static func bandPassCoefficients(sampleRate: Float, pitchHz: Float, q: Float)
        -> (b0: Float, b2: Float, a1: Float, a2: Float) {
        let w = 2 * Float.pi * pitchHz / sampleRate
        let alpha = sin(w) / (2 * q)
        let a0 = 1 + alpha
        return (alpha / a0, -alpha / a0, -2 * cos(w) / a0, (1 - alpha) / a0)
    }

    public init(sampleRate: Float, pitchHz: Float = CWPrefilter.defaultPitchHz) {
        self.sampleRate = sampleRate
        self.pitchHz = pitchHz
        hold = Int((Self.holdMs * sampleRate / 1000).rounded())
        envDecay = exp(-1000 / (Self.envelopeMs * sampleRate))
        rAlpha = 1000 / (Self.noiseMs * sampleRate)
        decay = exp(-1000 / (Self.decayMs * sampleRate))
        tune(pitchHz)
    }

    private mutating func tune(_ hz: Float) {
        pitchHz = hz
        (db0, db2, da1, da2) = Self.bandPassCoefficients(sampleRate: sampleRate, pitchHz: hz, q: Self.detectorQ)
        (cb0, cb2, ca1, ca2) = Self.bandPassCoefficients(sampleRate: sampleRate, pitchHz: hz, q: Self.resonatorQ)
        cosine = 2 * cos(2 * Float.pi * hz / sampleRate)
    }

    /// Follow the core's pitch lock. Coefficients change; state carries over,
    /// so a retune mid-stream costs a small transient and nothing else.
    /// Moves under half a hertz are ignored.
    public mutating func retune(pitchHz hz: Float) {
        guard hz > 0, abs(hz - pitchHz) >= 0.5 else { return }
        tune(hz)
    }

    /// Forget everything heard so far (the decoder it feeds was reset).
    public mutating func reset() {
        dx1 = 0; dx2 = 0; dy1 = 0; dy2 = 0
        cx1 = 0; cx2 = 0; cy1 = 0; cy2 = 0
        envelope = 0; residualLevel = 0; remaining = 0
    }

    /// True while a blank is running (the last `process` output was the
    /// continuation, not the input).
    public var isBlanking: Bool { remaining > 0 }

    /// One sample in, one sample out.
    public mutating func process(_ x: Float) -> Float {
        let yd = db0 * x + db2 * dx2 - da1 * dy1 - da2 * dy2
        dx2 = dx1; dx1 = x; dy2 = dy1; dy1 = yd
        envelope = max(abs(yd), envelope * envDecay)
        let r = abs(x - yd)
        residualLevel += (r - residualLevel) * rAlpha
        let ratio = remaining > 0 ? Self.holdRatio : Self.triggerRatio
        let reference = max(ratio * envelope, max(Self.noiseRatio * residualLevel, Self.floor))
        if r > reference { remaining = hold }
        let blanked = remaining > 0
        let resonator: Float
        if blanked {
            remaining -= 1
            resonator = decay * (cosine * cy1 - cy2)
            cx2 = cx1; cx1 = 0
        } else {
            resonator = cb0 * x + cb2 * cx2 - ca1 * cy1 - ca2 * cy2
            cx2 = cx1; cx1 = x
        }
        cy2 = cy1; cy1 = resonator
        return blanked ? resonator : x
    }
}
