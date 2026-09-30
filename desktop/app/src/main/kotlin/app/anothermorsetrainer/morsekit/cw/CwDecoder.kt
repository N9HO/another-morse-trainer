package app.anothermorsetrainer.morsekit.cw

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hand-sent Morse (CW) decoder core.
 *
 * Line-faithful Kotlin port of the vendored Carrier Wave decoder core's
 * `cw_decoder.c` / `cw_decoder.h` (see PROVENANCE.md in this package; MIT,
 * © 2026 Jay Vana). The iOS app compiles the C core directly; Android has no
 * NDK toolchain in this project, so the same pipeline is ported statement for
 * statement — all arithmetic in Float, same constants, same defaults — and
 * held to the same synthetic-audio checks (see CwDecoderTest) the firmware
 * bench runs. Fix behavior upstream first; keep this port in step.
 *
 * Pipeline:
 *   int16 PCM
 *     -> DC block
 *     -> decimating anti-alias FIR (integer factor)
 *     -> [optional] coarse pitch-search prepass to acquire the real tone
 *     -> 3-bin generalized Goertzel tone detector (center ± neighbor)
 *     -> adaptive noise-floor threshold w/ hysteresis (keying envelope)
 *     -> committed/pending debouncer (glitch rejection)
 *     -> Mills Ratio-Weighted Estimation (adaptive dit/dah + gap timing)
 *     -> [optional] auto-seed warm-up (lock speed from first marks)
 *     -> Morse element tree -> character / prosign (with confidence)
 *
 * Threading: NOT internally locked. Call [feed] from exactly one thread; the
 * onSymbol/onStatus callbacks fire from within [feed], on that same thread.
 */
class CwDecoder private constructor(private val cfg: Config) {

    /**
     * Decoder configuration; mirrors `cw_config_t` with the C defaults
     * (the original 48 kHz host profile). All four fixes default ON.
     */
    class Config {
        var inputRateHz = 48_000          // PCM sample rate fed to feed()
        var inputChannels = 1             // 1 = mono; 2 = interleaved L/R (left used)
        var targetRateHz = 8_000          // internal processing rate after decimation
        var toneHz = 700.0f               // CW pitch / sidetone (acquisition center)
        var blockMs = 6.0f                // Goertzel integration window
        var initialWpm = 18.0f            // seed speed before RWE converges
        var threshOn = 5.0f               // tone asserts when mag > floor * threshOn
        var threshOff = 2.5f              // tone releases when mag < floor * threshOff
        var toneValidate = 1.2f           // center bin must beat neighbors by this
        var debounceMs = 0.0f             // min keying-state run length; 0 => one block

        // Fix A: adaptive (proportional) noise-floor gate.
        var adaptiveGate = true
        var gateDits = 8.0f

        // Fix B: relative noise-floor minimum.
        var relativeFloor = true
        var floorMinFrac = 0.02f

        // Fix C: coarse pitch-search prepass.
        var pitchSearch = true
        var pitchSearchLo = 500.0f
        var pitchSearchHi = 900.0f

        // Fix D: auto-seed RWE speed from first marks.
        var autoSeed = true
        var autoSeedMarks = 8

        // RWE mark-cluster tuning knob (compressed-fist handling).
        var markRatioIdeal = 3.0f
        var markRweQ = 10.0f
        var markRweP = 2.0f

        /** Fired once per decoded character, prosign, or word space. */
        var onSymbol: ((text: String, confidence: Float) -> Unit)? = null

        /** Optional telemetry, fired roughly once per decoded character. */
        var onStatus: ((wpm: Float, signalRatio: Float, tonePresent: Boolean) -> Unit)? = null

        internal fun copy(): Config {
            val c = Config()
            c.inputRateHz = inputRateHz; c.inputChannels = inputChannels
            c.targetRateHz = targetRateHz; c.toneHz = toneHz; c.blockMs = blockMs
            c.initialWpm = initialWpm; c.threshOn = threshOn; c.threshOff = threshOff
            c.toneValidate = toneValidate; c.debounceMs = debounceMs
            c.adaptiveGate = adaptiveGate; c.gateDits = gateDits
            c.relativeFloor = relativeFloor; c.floorMinFrac = floorMinFrac
            c.pitchSearch = pitchSearch; c.pitchSearchLo = pitchSearchLo
            c.pitchSearchHi = pitchSearchHi
            c.autoSeed = autoSeed; c.autoSeedMarks = autoSeedMarks
            c.markRatioIdeal = markRatioIdeal; c.markRweQ = markRweQ; c.markRweP = markRweP
            c.onSymbol = onSymbol; c.onStatus = onStatus
            return c
        }
    }

    companion object {
        private const val CALIB_BLOCKS = 80      // ~0.5 s @ 6 ms blocks: initial floor estimate
        private const val GATE_MAX = 50          // legacy fixed gate (blocks) when adaptiveGate=false
        private const val MAX_ELEMS = 10         // longest pattern before forcing a decode
        private const val MAX_SEARCH_BINS = 24   // coarse pitch-search bank cap
        private const val SEARCH_SPACING = 50.0f // coarse pitch-search bin spacing (Hz)
        private const val SEARCH_HITS_LOCK = 2   // consecutive strong blocks to lock pitch

        private val FPI = PI.toFloat()

        /** Allocate + initialize, mirroring `cw_decoder_create`. Null on bad config. */
        fun create(config: Config): CwDecoder? {
            if (config.onSymbol == null) return null
            if (config.inputRateHz < 6000 || config.targetRateHz < 3000) return null
            if (config.toneHz <= 0.0f || config.initialWpm <= 0.0f) return null
            val cfg = config.copy()
            if (cfg.markRatioIdeal < 1.5f) cfg.markRatioIdeal = 3.0f
            if (cfg.gateDits < 3.0f) cfg.gateDits = 8.0f
            if (cfg.autoSeedMarks < 2) cfg.autoSeedMarks = 8
            return CwDecoder(cfg)
        }

        private fun goertzelCoeff(fHz: Float, fs: Float): Float =
            2.0f * cos(2.0f * FPI * fHz / fs)
    }

    // ---- decimation ----
    private var decimFactor = 1
    private var firTaps = FloatArray(0)
    private var firLen = 0
    private var firHist = FloatArray(0)   // circular history, length firLen
    private var firHead = 0
    private var decimCount = 0

    // ---- dc blocker (input rate) ----
    private var dcX1 = 0.0f
    private var dcY1 = 0.0f

    // ---- goertzel (3-bin) ----
    private var blockN = 8
    private var blockIdx = 0
    private var coeffC = 0.0f
    private var coeffL = 0.0f
    private var coeffR = 0.0f
    private var sc1 = 0.0f; private var sc2 = 0.0f
    private var sl1 = 0.0f; private var sl2 = 0.0f
    private var sr1 = 0.0f; private var sr2 = 0.0f
    private var msPerBlock = 0.0f

    // ---- pitch-search prepass (Fix C) ----
    private var searching = false
    private var nSearch = 0
    private var searchCoeff = FloatArray(0)
    private var searchFreq = FloatArray(0)
    private var ss1 = FloatArray(0)
    private var ss2 = FloatArray(0)
    private var searchHits = 0

    // ---- noise floor / detection ----
    private var noiseFloor = 0.0f
    private var sigPeak = 0.0f            // slow signal-peak track (Fix B)
    private var calibN = 0
    private var calibrated = false
    private var gateCount = 0

    // ---- committed/pending keying debouncer ----
    private var committedOn = false
    private var committedRun = 0          // blocks
    private var pendingOn = false
    private var pendingRun = 0
    private var minRunBlocks = 1

    // ---- symbol assembly ----
    private val symbol = StringBuilder()
    private var confSum = 0.0f
    private var confN = 0
    private var charEmitted = true        // char already flushed for the current gap
    private var wordEmitted = true

    // ---- adaptive timing ----
    private val rweMark = CwRwe(cfg.markRatioIdeal, 1.0f, 3.0f)   // reseeded below
    private val rweSpace = CwRwe(3.0f, 1.0f, 3.0f)
    private var wordGapEst = 0.0f         // EMA of word-gap duration (~7 units)
    private var wpmEst = cfg.initialWpm

    // ---- auto-seed warm-up (Fix D) ----
    private var seeding = false
    private var seedCount = 0
    private var seedMin = 1e30f
    private var seedMax = 0.0f

    private var lastRatio = 0.0f          // mag/floor of last block (telemetry)

    init {
        buildDecimator()

        // Goertzel block size.
        blockN = (cfg.targetRateHz * cfg.blockMs / 1000.0f + 0.5f).toInt()
        if (blockN < 8) blockN = 8
        msPerBlock = 1000.0f * blockN / cfg.targetRateHz

        buildSearchBank()
        setTone(cfg.toneHz)   // initial 3-bin coeffs

        minRunBlocks = (cfg.debounceMs / msPerBlock + 0.5f).toInt()
        if (minRunBlocks < 1) minRunBlocks = 1

        reset()
    }

    /** Current speed estimate (words per minute). */
    val wpm: Float get() = wpmEst

    /** Currently locked tone frequency (Hz) — useful after a pitch-search lock. */
    val toneHz: Float get() = cfg.toneHz

    /* ------------------------------------------------------------------- */
    /* setup helpers                                                       */
    /* ------------------------------------------------------------------- */

    /** (Re)compute the 3-bin coefficients for a center tone; reset accumulators. */
    private fun setTone(f: Float) {
        val fs = cfg.targetRateHz.toFloat()
        val delta = fs / blockN
        var fl = f - delta; if (fl < delta) fl = delta
        var fr = f + delta; if (fr > fs * 0.49f) fr = fs * 0.49f
        cfg.toneHz = f
        coeffC = goertzelCoeff(f, fs)
        coeffL = goertzelCoeff(fl, fs)
        coeffR = goertzelCoeff(fr, fs)
        sc1 = 0f; sc2 = 0f; sl1 = 0f; sl2 = 0f; sr1 = 0f; sr2 = 0f
    }

    private fun applyMarkParams() {
        rweMark.setParams(cfg.markRatioIdeal, 1.0f, cfg.markRweQ, cfg.markRweP)
    }

    private fun seedTiming(wpm: Float) {
        val dit = 1200.0f / wpm            // ms
        rweMark.setParams(cfg.markRatioIdeal, 1.0f, 10.0f, 2.0f)
        rweMark.reseed(dit, cfg.markRatioIdeal * dit)
        rweSpace.setParams(3.0f, 1.0f, 10.0f, 2.0f)
        rweSpace.reseed(dit, 3.0f * dit)
        applyMarkParams()
        wordGapEst = 7.0f * dit
        wpmEst = wpm
    }

    /** Design a windowed-sinc low-pass and store decimation taps. */
    private fun buildDecimator() {
        var factor = (cfg.inputRateHz.toFloat() / cfg.targetRateHz + 0.5f).toInt()
        if (factor < 1) factor = 1
        decimFactor = factor

        if (factor == 1) {   // no decimation needed
            firTaps = FloatArray(0); firHist = FloatArray(0); firLen = 0
            return
        }

        val taps = 8 * factor + 1          // odd
        firLen = taps
        firTaps = FloatArray(taps)
        firHist = FloatArray(taps)

        val fcNorm = 0.45f / factor        // 0.45 * target, in input-rate units
        val m = taps - 1
        var sum = 0.0f
        for (n in 0 until taps) {
            val k = n - 0.5f * m
            val arg = 2.0f * fcNorm * k
            val s = if (abs(arg) < 1e-6f) 1.0f else sin(FPI * arg) / (FPI * arg)
            val win = 0.54f - 0.46f * cos(2.0f * FPI * n / m)   // Hamming
            firTaps[n] = s * win
            sum += firTaps[n]
        }
        if (sum != 0.0f) for (n in 0 until taps) firTaps[n] /= sum   // unity DC gain
    }

    private fun buildSearchBank() {
        if (!cfg.pitchSearch) { nSearch = 0; return }
        val lo = cfg.pitchSearchLo
        val hi = cfg.pitchSearchHi
        if (hi <= lo) { cfg.pitchSearch = false; nSearch = 0; return }
        var k = ((hi - lo) / SEARCH_SPACING + 0.5f).toInt() + 1
        if (k < 3) k = 3
        if (k > MAX_SEARCH_BINS) k = MAX_SEARCH_BINS
        nSearch = k
        searchCoeff = FloatArray(k)
        searchFreq = FloatArray(k)
        ss1 = FloatArray(k)
        ss2 = FloatArray(k)
        val fs = cfg.targetRateHz.toFloat()
        for (i in 0 until k) {
            val f = lo + SEARCH_SPACING * i
            searchFreq[i] = f
            searchCoeff[i] = goertzelCoeff(f, fs)
        }
    }

    /** Reset timing models, keying state, pitch lock, and floor calibration. */
    fun reset() {
        firHead = 0; decimCount = 0
        firHist.fill(0.0f)
        dcX1 = 0.0f; dcY1 = 0.0f
        blockIdx = 0
        sc1 = 0f; sc2 = 0f; sl1 = 0f; sl2 = 0f; sr1 = 0f; sr2 = 0f

        searching = false; searchHits = 0
        ss1.fill(0.0f)
        ss2.fill(0.0f)

        noiseFloor = 0.0f; sigPeak = 0.0f
        calibN = 0; calibrated = false; gateCount = 0
        committedOn = false; committedRun = 0
        pendingOn = false; pendingRun = 0
        symbol.setLength(0); confSum = 0.0f; confN = 0
        charEmitted = true; wordEmitted = true    // nothing pending
        lastRatio = 0.0f

        seeding = cfg.autoSeed; seedCount = 0
        seedMin = 1e30f; seedMax = 0.0f

        seedTiming(cfg.initialWpm)
    }

    /* ------------------------------------------------------------------- */
    /* element / character assembly                                        */
    /* ------------------------------------------------------------------- */

    private fun emitSymbol() {
        if (symbol.isEmpty()) return
        val txt = CwMorse.lookup(symbol.toString())
        val conf = if (confN > 0) confSum / confN else 0.0f
        if (txt != null) {
            cfg.onSymbol?.invoke(txt, conf)
        } else {
            cfg.onSymbol?.invoke("?", conf * 0.3f)
        }
        cfg.onStatus?.invoke(wpmEst, lastRatio, committedOn)
        symbol.setLength(0); confSum = 0.0f; confN = 0
    }

    private fun onMarkEnd(durMs: Float) {
        // Reject sub-dit glitches: don't append, don't pollute the estimator.
        if (durMs < 0.35f * rweMark.minEst) return

        /* Fix D: auto-seed warm-up. Observe the first few marks and, once we
           have a clear short/long spread, re-seed the dit/dah estimates so a
           fast opener locks in ~2 chars instead of ~8. Includes the vendored
           core's early-out reseed (>=3 marks with a clear >=2:1 spread). */
        if (seeding) {
            if (durMs < seedMin) seedMin = durMs
            if (durMs > seedMax) seedMax = durMs
            seedCount++
            val clearSpread = seedMin > 0.0f && seedMax >= 2.0f * seedMin
            val early = seedCount >= 3 && clearSpread
            if (early || seedCount >= cfg.autoSeedMarks) {
                seeding = false
                if (clearSpread) {
                    rweMark.reseed(seedMin, seedMax)
                    rweSpace.reseed(seedMin, 3.0f * seedMin)
                    wordGapEst = 7.0f * seedMin
                    wpmEst = 1200.0f / seedMin
                }
            }
        }

        val isDah = rweMark.isMax(durMs)
        val conf = rweMark.confidence(durMs)
        rweMark.update(durMs)
        wpmEst = 1200.0f / rweMark.minEst

        if (symbol.length >= MAX_ELEMS) {
            emitSymbol()   // overrun -> flush as unknown, then start fresh
        }
        symbol.append(if (isDah) '-' else '.')
        confSum += conf; confN++

        // A new mark opens a new gap to evaluate.
        charEmitted = false
        wordEmitted = false
    }

    /** Responsive emission while the key is up (timeout-based). */
    private fun onSpaceOngoing(offMs: Float) {
        val bEc = rweSpace.boundary()                       // elem|char
        val bCw = sqrt(rweSpace.maxEst * wordGapEst)        // char|word

        if (!charEmitted && symbol.isNotEmpty() && offMs >= bEc) {
            emitSymbol()
            charEmitted = true
        }
        if (!wordEmitted && offMs >= bCw) {
            cfg.onSymbol?.invoke(" ", 1.0f)
            wordEmitted = true
        }
    }

    /** A gap just completed (next mark arrived): update the space estimators. */
    private fun onSpaceEnd(offMs: Float) {
        if (offMs < 0.35f * rweMark.minEst) return   // glitch gap: ignore

        val bCw = sqrt(rweSpace.maxEst * wordGapEst)
        if (offMs >= bCw) {
            wordGapEst += 0.2f * (offMs - wordGapEst)   // EMA
        } else {
            rweSpace.update(offMs)                      // elem/char clusters
        }
    }

    private fun finalizeInterval(wasOn: Boolean, runBlocks: Int) {
        val durMs = runBlocks * msPerBlock
        if (wasOn) onMarkEnd(durMs) else onSpaceEnd(durMs)
    }

    /* ------------------------------------------------------------------- */
    /* per-block processing: detection + debounce + timing                 */
    /* ------------------------------------------------------------------- */

    private fun processBlock(mag2c: Float, mag2l: Float, mag2r: Float) {
        val magc = if (mag2c > 0.0f) sqrt(mag2c) else 0.0f

        // Initial noise-floor calibration: assume mostly noise at startup.
        if (!calibrated) {
            noiseFloor += magc
            if (++calibN >= CALIB_BLOCKS) {
                noiseFloor /= calibN
                if (noiseFloor < 1e-6f) noiseFloor = 1e-6f
                calibrated = true
                searching = cfg.pitchSearch   // Fix C: acquire pitch next
            }
            return   // hold output until calibrated
        }

        // Fix B: slow signal-peak track for a relative floor minimum.
        if (magc > sigPeak) sigPeak = magc else sigPeak *= 0.9995f

        // Gated noise-floor tracking.
        if (magc < 2.0f * noiseFloor) {
            noiseFloor = 0.99f * noiseFloor + 0.01f * magc
            gateCount = 0
        } else {
            gateCount++
            /* Fix A: gate timeout proportional to the current dit estimate, so
               a single long dah (or a run of dahs) at slow speeds never trips
               "floor rose". Never tighter than the legacy 300 ms. */
            val gateLimit: Int
            if (cfg.adaptiveGate) {
                var gateMs = cfg.gateDits * rweMark.minEst
                val floorMs = GATE_MAX * msPerBlock
                if (gateMs < floorMs) gateMs = floorMs
                gateLimit = (gateMs / msPerBlock + 0.5f).toInt()
            } else {
                gateLimit = GATE_MAX
            }
            if (gateCount > gateLimit) {
                noiseFloor = 0.90f * noiseFloor + 0.10f * magc
            }
        }
        if (noiseFloor < 1e-6f) noiseFloor = 1e-6f
        if (cfg.relativeFloor) {
            val fmin = sigPeak * cfg.floorMinFrac
            if (fmin > noiseFloor) noiseFloor = fmin
        }
        lastRatio = magc / noiseFloor

        // Tone present: 3-bin dominance check (only required to turn ON).
        val toneValid = mag2c > mag2l * cfg.toneValidate &&
            mag2c > mag2r * cfg.toneValidate

        val thrOn = noiseFloor * cfg.threshOn
        val thrOff = noiseFloor * cfg.threshOff

        val raw: Boolean = if (committedOn) {
            magc > thrOff                    // ride QSB while on
        } else {
            magc > thrOn && toneValid        // strict to turn on
        }

        /* Committed/pending debouncer: a state change must persist
           minRunBlocks before it's accepted; shorter glitches are absorbed. */
        if (raw == committedOn) {
            committedRun += pendingRun + 1   // absorb any tentative glitch
            pendingRun = 0
        } else {
            if (pendingRun == 0) pendingOn = raw
            pendingRun++
            if (pendingRun >= minRunBlocks) {
                finalizeInterval(committedOn, committedRun)
                committedOn = raw
                committedRun = pendingRun
                pendingRun = 0
            }
        }

        // While the key is up, allow timeout-based char/word emission.
        if (!committedOn) onSpaceOngoing(committedRun * msPerBlock)
    }

    /* ------------------------------------------------------------------- */
    /* pitch-search prepass (Fix C): after calibration until a tone locks  */
    /* ------------------------------------------------------------------- */

    private val searchMag = FloatArray(MAX_SEARCH_BINS)

    private fun handleSearch() {
        val k = nSearch
        var vmax = -1.0f
        var kp = 0
        for (i in 0 until k) {
            val m2 = ss1[i] * ss1[i] + ss2[i] * ss2[i] - searchCoeff[i] * ss1[i] * ss2[i]
            searchMag[i] = if (m2 > 0.0f) sqrt(m2) else 0.0f
            if (searchMag[i] > vmax) { vmax = searchMag[i]; kp = i }
            ss1[i] = 0.0f; ss2[i] = 0.0f    // reset for next block
        }

        /* Lock criterion: the peak bin must clear the calibrated noise floor by
           the same margin the detector uses (threshOn), sustained for two
           blocks. See the C core for why no relative/dominance test is used. */
        val strong = vmax > noiseFloor * cfg.threshOn

        if (!strong) { searchHits = 0; return }

        if (++searchHits >= SEARCH_HITS_LOCK) {
            // Parabolic interpolation around the peak bin for sub-bin pitch.
            val ml = if (kp > 0) searchMag[kp - 1] else vmax
            val mh = if (kp < k - 1) searchMag[kp + 1] else vmax
            val denom = ml - 2.0f * vmax + mh
            var delta = if (abs(denom) > 1e-9f) 0.5f * (ml - mh) / denom else 0.0f
            if (delta > 1.0f) delta = 1.0f
            if (delta < -1.0f) delta = -1.0f
            val f = searchFreq[kp] + delta * SEARCH_SPACING
            setTone(f)                 // lock the lean 3-bin detector here
            searching = false
            searchHits = 0
        }
    }

    /* ------------------------------------------------------------------- */
    /* goertzel accumulation over decimated samples                        */
    /* ------------------------------------------------------------------- */

    private fun pushDecimated(x: Float) {
        // Center / neighbor recurrences.
        val s0 = x + coeffC * sc1 - sc2; sc2 = sc1; sc1 = s0
        val l0 = x + coeffL * sl1 - sl2; sl2 = sl1; sl1 = l0
        val r0 = x + coeffR * sr1 - sr2; sr2 = sr1; sr1 = r0

        // Search-bank recurrences (only while acquiring pitch).
        if (searching) {
            for (i in 0 until nSearch) {
                val v = x + searchCoeff[i] * ss1[i] - ss2[i]
                ss2[i] = ss1[i]; ss1[i] = v
            }
        }

        if (++blockIdx >= blockN) {
            if (searching) {
                handleSearch()
                // Keep center accumulators from carrying across blocks.
                sc1 = 0f; sc2 = 0f; sl1 = 0f; sl2 = 0f; sr1 = 0f; sr2 = 0f
                blockIdx = 0
            } else {
                val mag2c = sc1 * sc1 + sc2 * sc2 - coeffC * sc1 * sc2
                val mag2l = sl1 * sl1 + sl2 * sl2 - coeffL * sl1 * sl2
                val mag2r = sr1 * sr1 + sr2 * sr2 - coeffR * sr1 * sr2
                sc1 = 0f; sc2 = 0f; sl1 = 0f; sl2 = 0f; sr1 = 0f; sr2 = 0f
                blockIdx = 0
                processBlock(mag2c, mag2l, mag2r)
            }
        }
    }

    /* ------------------------------------------------------------------- */
    /* input: dc block + decimate                                          */
    /* ------------------------------------------------------------------- */

    private fun pushInput(x: Float) {
        // One-pole DC blocker.
        val y = x - dcX1 + 0.995f * dcY1
        dcX1 = x; dcY1 = y

        if (decimFactor == 1) { pushDecimated(y); return }

        // Write into the circular history.
        firHist[firHead] = y
        firHead = (firHead + 1) % firLen

        if (++decimCount >= decimFactor) {
            decimCount = 0
            // Convolve: most-recent sample is at (firHead - 1).
            var acc = 0.0f
            var idx = firHead - 1
            for (k in 0 until firLen) {
                if (idx < 0) idx += firLen
                acc += firTaps[k] * firHist[idx]
                idx--
            }
            pushDecimated(acc)
        }
    }

    /**
     * Feed PCM. [count] is the number of int16 samples (NOT frames). For
     * stereo input pass interleaved samples and count = frames*2. Callbacks
     * may fire during this call.
     */
    fun feed(samples: ShortArray, count: Int = samples.size) {
        if (cfg.inputChannels == 2) {
            var i = 0
            while (i + 1 < count) {        // take the left channel
                pushInput(samples[i].toFloat())
                i += 2
            }
        } else {
            for (i in 0 until count) pushInput(samples[i].toFloat())
        }
    }
}
