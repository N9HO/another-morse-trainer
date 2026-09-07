package app.anothermorsetrainer.morsekit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Noise blanker for the CW decoder's microphone path (#195). Port of the iOS
 * `CWPrefilter`; the two are kept to the same arithmetic and pinned by
 * `fixtures/cw-prefilter.json`, whose `derivation` block is the spec.
 *
 * QRN — atmospheric static heard through a rig's speaker — is mostly short
 * broadband crashes, and each one lands in the ported core's tone bin as a
 * spurious mark that splits a character or adds a stray E. Hiss the core
 * handles on its own down to about 6 dB SNR; crashes break it at one per
 * second. This is the receiver noise blanker's answer, applied to PCM before
 * [app.anothermorsetrainer.morsekit.cw.CwDecoder.feed]: detect the impulse
 * where it is unmistakable (the wideband residual, where a tone leaves
 * nothing and a crash leaves nearly everything), and while it lasts
 * substitute the tone's own continuation so a crash inside a mark fills the
 * hole instead of punching one.
 *
 * The signal path is pass-through: samples reach the core untouched unless a
 * blank is active. Per sample x (nominal full scale ±1), in this order:
 *  1. Detector band-pass at the pitch (RBJ constant-peak form, Q = 1):
 *     yd = b0·x + b2·x[n-2] − a1·yd[n-1] − a2·yd[n-2]
 *  2. In-band envelope  e = max(|yd|, e·envDecay)            (2 ms)
 *  3. Residual          r = x − yd;  R += (|r| − R)·rAlpha     (100 ms mean)
 *  4. Trigger: |r| > max(k·e, kR·R, floor), with k = 1.5 when clear and 0.5
 *     while a blank is running (hysteresis eats the crash's tail), starts or
 *     extends a blank of `hold` samples (1 ms).
 *  5. Continuation resonator (same band-pass form, Q = 6) is fed x while
 *     clear; while blanked it runs on as g·(c·y[n-1] − y[n-2]) with input 0.
 *  6. Output: x when clear; the resonator's output while blanked.
 *
 * Why these shapes: the residual/envelope ratio is scale-invariant, so the
 * trigger follows a crash all the way down its decay instead of passing the
 * tail (which still clears the core's threshold). R keeps steady hiss from
 * tripping it — hiss raises R, a 20 ms crash barely moves it. The floor keeps
 * a quiet room from being blanked at all: a blanker that squelches silence
 * feeds the core a residue at the blanker's own pitch, which is the mis-lock
 * the decoder's scout exists to escape. A narrow band-pass in the signal path
 * was measured and rejected: it rings on crashes and slows the speed
 * estimate, and the continuation alone is what carries a mark across.
 */
class CwPrefilter(val sampleRate: Float, pitchHz: Float = DEFAULT_PITCH_HZ) {

    companion object {
        const val DEFAULT_PITCH_HZ = 700f
        const val DETECTOR_Q = 1f
        const val RESONATOR_Q = 6f
        const val TRIGGER_RATIO = 1.5f
        const val HOLD_RATIO = 0.5f
        const val NOISE_RATIO = 2.5f
        const val FLOOR = 0.02f
        const val HOLD_MS = 1f
        const val ENVELOPE_MS = 2f
        const val NOISE_MS = 100f
        const val DECAY_MS = 25f

        /**
         * RBJ constant-peak band-pass, normalised so a0 = 1, as [b0, b2, a1, a2]:
         * w = 2π·pitch/rate, α = sin(w)/(2Q), b0 = α/(1+α), b2 = −b0,
         * a1 = −2cos(w)/(1+α), a2 = (1−α)/(1+α). b1 is zero.
         */
        fun bandPassCoefficients(sampleRate: Float, pitchHz: Float, q: Float): FloatArray {
            val w = 2f * Math.PI.toFloat() * pitchHz / sampleRate
            val alpha = sin(w) / (2f * q)
            val a0 = 1f + alpha
            return floatArrayOf(alpha / a0, -alpha / a0, -2f * cos(w) / a0, (1f - alpha) / a0)
        }
    }

    var pitchHz: Float = pitchHz
        private set

    // Detector band-pass and its history.
    private var db0 = 0f; private var db2 = 0f; private var da1 = 0f; private var da2 = 0f
    private var dx1 = 0f; private var dx2 = 0f; private var dy1 = 0f; private var dy2 = 0f
    // Continuation resonator and its history.
    private var cb0 = 0f; private var cb2 = 0f; private var ca1 = 0f; private var ca2 = 0f
    private var cx1 = 0f; private var cx2 = 0f; private var cy1 = 0f; private var cy2 = 0f
    private var cosine = 0f
    // Envelopes and the blank countdown.
    private var envelope = 0f
    private var residualLevel = 0f
    private var remaining = 0

    private val hold: Int = (HOLD_MS * sampleRate / 1000f).roundToInt()
    private val envDecay: Float = exp(-1000f / (ENVELOPE_MS * sampleRate))
    private val rAlpha: Float = 1000f / (NOISE_MS * sampleRate)
    private val decay: Float = exp(-1000f / (DECAY_MS * sampleRate))

    init {
        tune(pitchHz)
    }

    private fun tune(hz: Float) {
        pitchHz = hz
        val d = bandPassCoefficients(sampleRate, hz, DETECTOR_Q)
        db0 = d[0]; db2 = d[1]; da1 = d[2]; da2 = d[3]
        val c = bandPassCoefficients(sampleRate, hz, RESONATOR_Q)
        cb0 = c[0]; cb2 = c[1]; ca1 = c[2]; ca2 = c[3]
        cosine = 2f * cos(2f * Math.PI.toFloat() * hz / sampleRate)
    }

    /**
     * Follow the core's pitch lock. Coefficients change; state carries over,
     * so a retune mid-stream costs a small transient and nothing else. Moves
     * under half a hertz are ignored.
     */
    fun retune(hz: Float) {
        if (hz <= 0f || abs(hz - pitchHz) < 0.5f) return
        tune(hz)
    }

    /** Forget everything heard so far (the decoder it feeds was reset). */
    fun reset() {
        dx1 = 0f; dx2 = 0f; dy1 = 0f; dy2 = 0f
        cx1 = 0f; cx2 = 0f; cy1 = 0f; cy2 = 0f
        envelope = 0f; residualLevel = 0f; remaining = 0
    }

    /** True while a blank is running (the last [process] output was the
     * continuation, not the input). */
    val isBlanking: Boolean get() = remaining > 0

    /** One sample in, one sample out. */
    fun process(x: Float): Float {
        val yd = db0 * x + db2 * dx2 - da1 * dy1 - da2 * dy2
        dx2 = dx1; dx1 = x; dy2 = dy1; dy1 = yd
        envelope = max(abs(yd), envelope * envDecay)
        val r = abs(x - yd)
        residualLevel += (r - residualLevel) * rAlpha
        val ratio = if (remaining > 0) HOLD_RATIO else TRIGGER_RATIO
        val reference = max(ratio * envelope, max(NOISE_RATIO * residualLevel, FLOOR))
        if (r > reference) remaining = hold
        val blanked = remaining > 0
        val resonator: Float
        if (blanked) {
            remaining -= 1
            resonator = decay * (cosine * cy1 - cy2)
            cx2 = cx1; cx1 = 0f
        } else {
            resonator = cb0 * x + cb2 * cx2 - ca1 * cy1 - ca2 * cy2
            cx2 = cx1; cx1 = x
        }
        cy2 = cy1; cy1 = resonator
        return if (blanked) resonator else x
    }
}
