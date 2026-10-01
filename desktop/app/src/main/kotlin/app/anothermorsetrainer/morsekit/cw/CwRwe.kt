package app.anothermorsetrainer.morsekit.cw

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Mills Ratio-Weighted Estimation (RWE) — a two-cluster online estimator.
 *
 * Line-faithful Kotlin port of the vendored Carrier Wave decoder core's
 * `cw_rwe.c` / `cw_rwe.h` (see PROVENANCE.md in this package; MIT, © 2026
 * Jay Vana). All arithmetic stays in Float to match the C core's behavior.
 *
 * Given a stream of interval durations that fall into a "short" and a "long"
 * cluster with a known ideal ratio (rho), RWE tracks the mean of each cluster
 * and adapts quickly when the operator's speed/weighting drifts. A short ring
 * of recent samples supplies window extremes; each update is weighted by how
 * close the window's max/min ratio is to rho, with a power-of-two attenuation
 * `est += (extreme - est) * 2^-w` where `w = k + q * |(r - rho)/rho|^p`.
 * Updates are additionally guarded by the current decision boundary so an
 * all-short or all-long window cannot erode the opposite cluster's estimate.
 */
class CwRwe(ratioIdeal: Float, minSeed: Float, maxSeed: Float) {

    companion object {
        /** Mills used 3–4; longer = smoother, slower to adapt. */
        const val RING = 4
    }

    var ratioIdeal: Float = ratioIdeal
        private set
    private var kBase = 1.0f
    private var q = 10.0f
    private var p = 2.0f

    /** Tracked cluster means (short, long). */
    var minEst: Float = if (minSeed > 0f) minSeed else 1.0f
        private set
    var maxEst: Float = 0f
        private set

    private val ring = FloatArray(RING)
    private var count = 0
    private var pos = 0

    init {
        maxEst = if (maxSeed > minEst) maxSeed else minEst * ratioIdeal
    }

    /**
     * Override the weight-function parameters (tuning knob). Lower ratioIdeal
     * (~2.5) + lower q (~5) makes the estimator track off-ideal but still
     * bimodal fists (e.g. compressed dah:dit ~2:1) instead of ignoring them.
     */
    fun setParams(ratioIdeal: Float, kBase: Float, q: Float, p: Float) {
        if (ratioIdeal > 1.0f) this.ratioIdeal = ratioIdeal
        if (kBase > 0.0f) this.kBase = kBase
        if (q >= 0.0f) this.q = q
        if (p > 0.0f) this.p = p
    }

    /**
     * Re-seed the cluster estimates without disturbing the weight parameters
     * or ratioIdeal. Clears the sample ring. Used by the auto-seed warm-up.
     */
    fun reseed(minSeed: Float, maxSeed: Float) {
        if (minSeed <= 0.0f) return
        minEst = minSeed
        maxEst = if (maxSeed > minSeed) maxSeed else minSeed * ratioIdeal
        count = 0
        pos = 0
        ring.fill(0.0f)
    }

    /** Geometric-mean decision boundary between the two clusters. */
    fun boundary(): Float {
        val prod = minEst * maxEst
        return if (prod > 0.0f) sqrt(prod) else minEst
    }

    /** True if the sample belongs to the long (max) cluster. */
    fun isMax(sampleMs: Float): Boolean = sampleMs > boundary()

    /** 0.0 at the boundary … 1.0 at a cluster center (log-domain margin). */
    fun confidence(sampleMs: Float): Float {
        if (sampleMs <= 0.0f || minEst <= 0.0f || maxEst <= 0.0f) return 0.0f
        val lmin = ln(minEst)
        val lmax = ln(maxEst)
        val lbnd = 0.5f * (lmin + lmax)
        val half = 0.5f * (lmax - lmin)
        if (half <= 1e-6f) return 0.5f
        var c = abs(ln(sampleMs) - lbnd) / half
        if (c > 1.0f) c = 1.0f
        return c
    }

    /** Feed one observed interval (ms). Updates the cluster estimates. */
    fun update(s: Float) {
        if (s <= 0.0f) return

        // Push into the ring.
        ring[pos] = s
        pos = (pos + 1) % RING
        if (count < RING) count++

        // Window extremes.
        var mx = -1e30f
        var mn = 1e30f
        for (i in 0 until count) {
            val v = ring[i]
            if (v > mx) mx = v
            if (v < mn) mn = v
        }
        if (mn <= 0.0f) return

        // Ratio-weighted attenuation: w = k + q*|(r-rho)/rho|^p, alpha = 2^-w.
        val ratio = mx / mn
        val err = (ratio - ratioIdeal) / ratioIdeal
        val w = kBase + q * abs(err).pow(p)
        var shift = (w + 0.5f).toInt()
        if (shift < 1) shift = 1
        if (shift > 16) shift = 16
        val alpha = Math.scalb(1.0f, -shift)   // exact 2^-shift, like ldexpf

        // Guard by the current boundary so an all-short or all-long window
        // cannot drag the opposite cluster.
        val bnd = boundary()
        if (mx >= bnd) maxEst += (mx - maxEst) * alpha
        if (mn < bnd) minEst += (mn - minEst) * alpha

        // Keep ordering + a minimum separation so the boundary stays meaningful.
        if (maxEst < minEst * 1.2f) maxEst = minEst * 1.2f
    }
}
