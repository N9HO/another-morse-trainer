/*
 * cw_decoder.h  --  Hand-sent Morse (CW) decoder core
 *
 * Self-contained DSP + decode pipeline for an ESP32-S3 (or any C99 target).
 * The pipeline is intentionally free of ESP-IDF dependencies so it can be
 * unit-tested on a host (gcc) and dropped into an ESP-IDF component unchanged.
 *
 * Pipeline:
 *   int16 PCM (from your USB/UAC ring buffer)
 *     -> DC block
 *     -> decimating anti-alias FIR (integer factor, e.g. 12 kHz -> 6 kHz)
 *     -> [optional] coarse pitch-search prepass to acquire the real tone
 *     -> 3-bin generalized Goertzel tone detector (center +/- neighbor)
 *     -> adaptive noise-floor threshold w/ hysteresis  (keying envelope)
 *     -> committed/pending debouncer (glitch rejection)
 *     -> Mills Ratio-Weighted Estimation (adaptive dit/dah + gap timing)
 *     -> [optional] auto-seed warm-up (lock speed from first marks)
 *     -> Morse element tree -> character / prosign  (with confidence)
 *
 * Threading: NOT internally locked. Call cw_decoder_feed() from exactly one
 * task (your decode task that drains the ring buffer). The on_symbol / on_status
 * callbacks fire from within cw_decoder_feed(), on that same task.
 */
#ifndef CW_DECODER_H
#define CW_DECODER_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct cw_decoder cw_decoder_t;

/* Fired once per decoded character, prosign, or word space.
 *   text       : NUL-terminated, e.g. "A", "5", "?", "<SK>", or " " for word gap
 *   confidence : 0.0 (ambiguous) .. 1.0 (cleanly in a timing cluster)
 *   user       : your cfg.user pointer
 */
typedef void (*cw_symbol_cb_t)(const char *text, float confidence, void *user);

/* Optional telemetry, fired roughly once per decoded character.
 *   wpm           : current estimated speed (1200 / dit_ms)
 *   signal_ratio  : last block magnitude / noise floor (linear; >thresh_on => tone)
 *   tone_present  : current keying state
 */
typedef void (*cw_status_cb_t)(float wpm, float signal_ratio, bool tone_present, void *user);

typedef struct {
    uint32_t input_rate_hz;   /* PCM sample rate fed to cw_decoder_feed(), e.g. 12000  */
    uint8_t  input_channels;  /* 1 = mono; 2 = interleaved L/R (left channel is used)   */
    uint32_t target_rate_hz;  /* internal processing rate after decimation; default 8000 */
    float    tone_hz;         /* CW pitch / sidetone, e.g. 700 (acquisition center)     */
    float    block_ms;        /* Goertzel integration window; default 6.0 ms            */
    float    initial_wpm;     /* seed speed before RWE converges; default 18             */
    float    thresh_on;       /* tone asserts when mag > noise_floor * thresh_on  (~5.0) */
    float    thresh_off;      /* tone releases when mag < noise_floor * thresh_off (~2.5)*/
    float    tone_validate;   /* center bin must exceed neighbor bins by this factor(1.2)*/
    float    debounce_ms;     /* min keying-state run length; 0 => one block             */

    /* ---- Fix A: adaptive (proportional) noise-floor gate ----
     * The "floor rose" recovery only fires after a loud run longer than any legal
     * element, scaled to the current speed. Fixes the slow-speed (QRS) hard failure
     * where a single long dah tripped a fixed block-count gate.
     * adaptive_gate=false restores the legacy fixed 300 ms gate. */
    bool  adaptive_gate;      /* default true                                            */
    float gate_dits;          /* loud-run headroom in dit-lengths; default 8.0           */

    /* ---- Fix B: relative noise-floor minimum ----
     * Keeps the floor a small fraction below the tracked signal peak so post-tone
     * Goertzel ringing can't bleed across gaps in (synthetic) zero-noise conditions.
     * Harmless on real receivers; relative_floor=false restores absolute 1e-6 floor. */
    bool  relative_floor;     /* default true                                            */
    float floor_min_frac;     /* floor >= floor_min_frac * signal_peak; default 0.02     */

    /* ---- Fix C: coarse pitch-search prepass ----
     * After noise calibration, scan [pitch_search_lo..hi] and lock tone_hz to the
     * actual received pitch before collapsing to the lean 3-bin detector. Widens the
     * usable mistune range from ~+/-70 Hz to the whole search span, WITHOUT widening
     * the steady-state detection bandwidth. pitch_search=false uses cfg.tone_hz as-is. */
    bool  pitch_search;       /* default true                                            */
    float pitch_search_lo;    /* Hz, default 500                                         */
    float pitch_search_hi;    /* Hz, default 900                                         */

    /* ---- Fix D (#3): auto-seed RWE speed from first marks ----
     * Observe the first auto_seed_marks key-downs; if they show a clear >=2:1 spread,
     * re-seed the dit/dah estimates from the observed shortest/longest. Recovers fast
     * openers (e.g. 40 WPM against an 18 WPM seed) in ~2 chars instead of ~8. If the
     * opener shows no clear spread (all dits), keeps the neutral initial_wpm seed. */
    bool  auto_seed;          /* default true                                            */
    int   auto_seed_marks;    /* marks to observe before re-seeding; default 8           */

    /* ---- RWE mark-cluster tuning knob (compressed-fist handling) ----
     * Applies to the dit/dah estimator only (spaces stay at 3.0/10/2). Lower
     * mark_ratio_ideal (~2.5) + lower mark_rwe_q (~5) lets the estimator track a
     * compressed fist (dah:dit ~2:1) instead of ignoring those windows. Defaults
     * reproduce the original behavior exactly. */
    float mark_ratio_ideal;   /* dah:dit ideal ratio; default 3.0                        */
    float mark_rwe_q;         /* weight steepness;     default 10.0                      */
    float mark_rwe_p;         /* weight exponent;      default 2.0                       */

    cw_symbol_cb_t on_symbol; /* required */
    cw_status_cb_t on_status; /* optional, may be NULL */
    void          *user;
} cw_config_t;

/* Populate cfg with sane defaults. NOTE: defaults reflect the original 48 kHz host
 * profile (48000->8000). Override input_rate_hz/target_rate_hz for the firmware
 * profile (e.g. 12000->6000). All four fixes default ON; the RWE knob defaults to
 * the original 3.0/10/2 so behavior is unchanged unless you tune it. */
void cw_config_default(cw_config_t *cfg);

/* Allocate + initialize. Returns NULL on bad config or OOM. */
cw_decoder_t *cw_decoder_create(const cw_config_t *cfg);

/* Free everything. Safe on NULL. */
void cw_decoder_destroy(cw_decoder_t *d);

/* Feed PCM. 'count' is the number of int16 samples (NOT frames). For stereo input
 * pass interleaved samples and count = frames*2. Callbacks may fire during this call. */
void cw_decoder_feed(cw_decoder_t *d, const int16_t *samples, size_t count);

/* Current speed estimate (words per minute). */
float cw_decoder_wpm(const cw_decoder_t *d);

/* Currently locked tone frequency (Hz) -- useful after a pitch-search lock. */
float cw_decoder_tone_hz(const cw_decoder_t *d);

/* Reset timing models, keying state, pitch lock, and noise-floor calibration. */
void cw_decoder_reset(cw_decoder_t *d);

#ifdef __cplusplus
}
#endif

#endif /* CW_DECODER_H */
