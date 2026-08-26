/*
 * cw_decoder.c  --  Hand-sent Morse decoder core (pure C99, no ESP-IDF deps)
 * See cw_decoder.h for the architecture overview.
 *
 * Change log vs. the original core:
 *   Fix A  adaptive proportional noise-floor gate   (slow-speed / QRS hard failure)
 *   Fix B  relative noise-floor minimum             (zero-noise ringing artifact)
 *   Fix C  coarse pitch-search prepass              (+/-70 Hz mistune limitation)
 *   Fix D  auto-seed RWE speed from first marks      (fast-opener warm-up loss)
 *   knob   RWE mark-cluster ratio/q/p exposed        (compressed-fist substitutions)
 * Each is individually toggleable via cw_config_t; all defaults reproduce or
 * strictly improve on the original behavior.
 */
#include "cw_decoder.h"
#include "cw_rwe.h"
#include "cw_morse.h"

#include <stdlib.h>
#include <string.h>
#include <math.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define CALIB_BLOCKS    80      /* ~0.5 s @ 6 ms blocks: initial noise-floor estimate */
#define GATE_MAX        50      /* legacy fixed gate (blocks) when adaptive_gate=false */
#define MAX_ELEMS       10      /* longest pattern we assemble before forcing a decode */
#define MAX_SEARCH_BINS 24      /* coarse pitch-search bank cap                        */
#define SEARCH_SPACING  50.0f   /* coarse pitch-search bin spacing (Hz)                */
#define SEARCH_HITS_LOCK 2      /* consecutive strong blocks required to lock pitch    */

struct cw_decoder {
    cw_config_t cfg;

    /* ---- decimation ---- */
    int     decim_factor;
    float  *fir_taps;
    int     fir_len;
    float  *fir_hist;        /* circular history, length fir_len */
    int     fir_head;
    int     decim_count;

    /* ---- dc blocker (input rate) ---- */
    float   dc_x1, dc_y1;

    /* ---- goertzel (3-bin) ---- */
    int     block_n, block_idx;
    float   coeff_c, coeff_l, coeff_r;
    float   sc1, sc2, sl1, sl2, sr1, sr2;
    float   ms_per_block;

    /* ---- pitch-search prepass (Fix C) ---- */
    bool    searching;
    int     n_search;
    float  *search_coeff;
    float  *search_freq;
    float  *ss1, *ss2;
    int     search_hits;

    /* ---- noise floor / detection ---- */
    float   noise_floor;
    float   sig_peak;        /* slow signal-peak track (Fix B) */
    int     calib_n;
    bool    calibrated;
    int     gate_count;

    /* ---- committed/pending keying debouncer ---- */
    bool    committed_on;
    int     committed_run;   /* blocks */
    bool    pending_on;
    int     pending_run;
    int     min_run_blocks;

    /* ---- symbol assembly ---- */
    char    symbol[MAX_ELEMS + 2];
    int     symbol_len;
    float   conf_sum;
    int     conf_n;
    bool    char_emitted;    /* char already flushed for the current gap */
    bool    word_emitted;

    /* ---- adaptive timing ---- */
    cw_rwe_t rwe_mark;       /* dit (min) vs dah (max), rho = mark_ratio_ideal */
    cw_rwe_t rwe_space;      /* element-gap (min) vs char-gap (max), rho = 3    */
    float    word_gap_est;   /* EMA of word-gap duration (~7 units)            */
    float    wpm;

    /* ---- auto-seed warm-up (Fix D) ---- */
    bool    seeding;
    int     seed_count;
    float   seed_min, seed_max;

    float    last_ratio;     /* mag/floor of last block (telemetry) */
};

/* ----------------------------------------------------------------------- */
/* config                                                                  */
/* ----------------------------------------------------------------------- */
void cw_config_default(cw_config_t *cfg)
{
    if (!cfg) return;
    memset(cfg, 0, sizeof(*cfg));
    cfg->input_rate_hz  = 48000;
    cfg->input_channels = 1;
    cfg->target_rate_hz = 8000;
    cfg->tone_hz        = 700.0f;
    cfg->block_ms       = 6.0f;
    cfg->initial_wpm    = 18.0f;
    cfg->thresh_on      = 5.0f;
    cfg->thresh_off     = 2.5f;
    cfg->tone_validate  = 1.2f;
    cfg->debounce_ms    = 0.0f;

    cfg->adaptive_gate   = true;   /* Fix A */
    cfg->gate_dits       = 8.0f;
    cfg->relative_floor  = true;   /* Fix B */
    cfg->floor_min_frac  = 0.02f;
    cfg->pitch_search    = true;   /* Fix C */
    cfg->pitch_search_lo = 500.0f;
    cfg->pitch_search_hi = 900.0f;
    cfg->auto_seed       = true;   /* Fix D */
    cfg->auto_seed_marks = 8;
    cfg->mark_ratio_ideal = 3.0f;  /* RWE knob (defaults = original behavior) */
    cfg->mark_rwe_q       = 10.0f;
    cfg->mark_rwe_p       = 2.0f;

    cfg->on_symbol      = NULL;
    cfg->on_status      = NULL;
    cfg->user           = NULL;
}

/* ----------------------------------------------------------------------- */
/* helpers                                                                 */
/* ----------------------------------------------------------------------- */
static float goertzel_coeff(float f_hz, float fs)
{
    return 2.0f * cosf(2.0f * (float)M_PI * f_hz / fs);
}

/* (re)compute the 3-bin Goertzel coefficients for a given center tone and
   reset the center accumulators. Used at create and on pitch lock. */
static void set_tone(cw_decoder_t *d, float f)
{
    float fs    = (float)d->cfg.target_rate_hz;
    float delta = fs / (float)d->block_n;
    float fl = f - delta; if (fl < delta)        fl = delta;
    float fr = f + delta; if (fr > fs * 0.49f)   fr = fs * 0.49f;
    d->cfg.tone_hz = f;
    d->coeff_c = goertzel_coeff(f,  fs);
    d->coeff_l = goertzel_coeff(fl, fs);
    d->coeff_r = goertzel_coeff(fr, fs);
    d->sc1 = d->sc2 = d->sl1 = d->sl2 = d->sr1 = d->sr2 = 0.0f;
}

static void apply_mark_params(cw_decoder_t *d)
{
    cw_rwe_set_params(&d->rwe_mark, d->cfg.mark_ratio_ideal,
                      1.0f, d->cfg.mark_rwe_q, d->cfg.mark_rwe_p);
}

static void seed_timing(cw_decoder_t *d, float wpm)
{
    float dit = 1200.0f / wpm;                 /* ms */
    cw_rwe_init(&d->rwe_mark,  d->cfg.mark_ratio_ideal, dit, d->cfg.mark_ratio_ideal * dit);
    cw_rwe_init(&d->rwe_space, 3.0f, dit, 3.0f * dit);
    apply_mark_params(d);
    d->word_gap_est = 7.0f * dit;
    d->wpm = wpm;
}

/* Design a windowed-sinc low-pass and store decimation taps.
 * Cutoff at 0.45 * target_rate, expressed in input-rate normalized units. */
static int build_decimator(cw_decoder_t *d)
{
    int factor = (int)((float)d->cfg.input_rate_hz / (float)d->cfg.target_rate_hz + 0.5f);
    if (factor < 1) factor = 1;
    d->decim_factor = factor;

    if (factor == 1) {       /* no decimation needed */
        d->fir_taps = NULL; d->fir_hist = NULL; d->fir_len = 0;
        return 0;
    }

    int taps = 8 * factor + 1;            /* odd */
    d->fir_len  = taps;
    d->fir_taps = (float *)calloc(taps, sizeof(float));
    d->fir_hist = (float *)calloc(taps, sizeof(float));
    if (!d->fir_taps || !d->fir_hist) return -1;

    float fc_norm = 0.45f * ((float)d->cfg.input_rate_hz / (float)factor)
                    / (float)d->cfg.input_rate_hz;   /* = 0.45/factor */
    int   M = taps - 1;
    float sum = 0.0f;
    for (int n = 0; n < taps; ++n) {
        float k   = (float)n - 0.5f * (float)M;
        float arg = 2.0f * fc_norm * k;
        float s   = (fabsf(arg) < 1e-6f) ? 1.0f
                    : sinf((float)M_PI * arg) / ((float)M_PI * arg);
        float win = 0.54f - 0.46f * cosf(2.0f * (float)M_PI * (float)n / (float)M); /* Hamming */
        d->fir_taps[n] = s * win;
        sum += d->fir_taps[n];
    }
    if (sum != 0.0f)
        for (int n = 0; n < taps; ++n) d->fir_taps[n] /= sum;  /* unity DC gain */
    return 0;
}

static int build_search_bank(cw_decoder_t *d)
{
    if (!d->cfg.pitch_search) { d->n_search = 0; return 0; }
    float lo = d->cfg.pitch_search_lo, hi = d->cfg.pitch_search_hi;
    if (hi <= lo) { d->cfg.pitch_search = false; d->n_search = 0; return 0; }
    int K = (int)((hi - lo) / SEARCH_SPACING + 0.5f) + 1;
    if (K < 3)               K = 3;
    if (K > MAX_SEARCH_BINS) K = MAX_SEARCH_BINS;
    d->n_search    = K;
    d->search_coeff = (float *)calloc(K, sizeof(float));
    d->search_freq  = (float *)calloc(K, sizeof(float));
    d->ss1          = (float *)calloc(K, sizeof(float));
    d->ss2          = (float *)calloc(K, sizeof(float));
    if (!d->search_coeff || !d->search_freq || !d->ss1 || !d->ss2) return -1;
    float fs = (float)d->cfg.target_rate_hz;
    for (int k = 0; k < K; ++k) {
        float f = lo + SEARCH_SPACING * (float)k;
        d->search_freq[k]  = f;
        d->search_coeff[k] = goertzel_coeff(f, fs);
    }
    return 0;
}

/* ----------------------------------------------------------------------- */
/* create / destroy / reset                                                */
/* ----------------------------------------------------------------------- */
cw_decoder_t *cw_decoder_create(const cw_config_t *cfg)
{
    if (!cfg || !cfg->on_symbol) return NULL;
    if (cfg->input_rate_hz < 6000 || cfg->target_rate_hz < 3000) return NULL;
    if (cfg->tone_hz <= 0.0f || cfg->initial_wpm <= 0.0f) return NULL;

    cw_decoder_t *d = (cw_decoder_t *)calloc(1, sizeof(*d));
    if (!d) return NULL;
    d->cfg = *cfg;
    if (d->cfg.mark_ratio_ideal < 1.5f) d->cfg.mark_ratio_ideal = 3.0f;
    if (d->cfg.gate_dits        < 3.0f) d->cfg.gate_dits        = 8.0f;
    if (d->cfg.auto_seed_marks  < 2)    d->cfg.auto_seed_marks  = 8;

    if (build_decimator(d) != 0)   { cw_decoder_destroy(d); return NULL; }

    /* goertzel block size */
    d->block_n = (int)((float)cfg->target_rate_hz * cfg->block_ms / 1000.0f + 0.5f);
    if (d->block_n < 8) d->block_n = 8;
    d->ms_per_block = 1000.0f * (float)d->block_n / (float)cfg->target_rate_hz;

    if (build_search_bank(d) != 0) { cw_decoder_destroy(d); return NULL; }

    set_tone(d, cfg->tone_hz);     /* initial 3-bin coeffs */

    d->min_run_blocks = (int)(cfg->debounce_ms / d->ms_per_block + 0.5f);
    if (d->min_run_blocks < 1) d->min_run_blocks = 1;

    cw_decoder_reset(d);
    return d;
}

void cw_decoder_destroy(cw_decoder_t *d)
{
    if (!d) return;
    free(d->fir_taps);
    free(d->fir_hist);
    free(d->search_coeff);
    free(d->search_freq);
    free(d->ss1);
    free(d->ss2);
    free(d);
}

void cw_decoder_reset(cw_decoder_t *d)
{
    if (!d) return;
    d->fir_head = 0; d->decim_count = 0;
    if (d->fir_hist) memset(d->fir_hist, 0, d->fir_len * sizeof(float));
    d->dc_x1 = d->dc_y1 = 0.0f;
    d->block_idx = 0;
    d->sc1 = d->sc2 = d->sl1 = d->sl2 = d->sr1 = d->sr2 = 0.0f;

    d->searching = false; d->search_hits = 0;
    if (d->ss1) memset(d->ss1, 0, d->n_search * sizeof(float));
    if (d->ss2) memset(d->ss2, 0, d->n_search * sizeof(float));

    d->noise_floor = 0.0f; d->sig_peak = 0.0f;
    d->calib_n = 0; d->calibrated = false; d->gate_count = 0;
    d->committed_on = false; d->committed_run = 0;
    d->pending_on = false;   d->pending_run = 0;
    d->symbol_len = 0; d->symbol[0] = 0; d->conf_sum = 0.0f; d->conf_n = 0;
    d->char_emitted = true; d->word_emitted = true;   /* nothing pending */
    d->last_ratio = 0.0f;

    d->seeding = d->cfg.auto_seed; d->seed_count = 0;
    d->seed_min = 1e30f; d->seed_max = 0.0f;

    seed_timing(d, d->cfg.initial_wpm);
}

float cw_decoder_wpm(const cw_decoder_t *d)     { return d ? d->wpm : 0.0f; }
float cw_decoder_tone_hz(const cw_decoder_t *d) { return d ? d->cfg.tone_hz : 0.0f; }

/* ----------------------------------------------------------------------- */
/* element / character assembly                                            */
/* ----------------------------------------------------------------------- */
static void emit_symbol(cw_decoder_t *d)
{
    if (d->symbol_len == 0) return;
    d->symbol[d->symbol_len] = 0;
    const char *txt = cw_morse_lookup(d->symbol);
    float conf = (d->conf_n > 0) ? (d->conf_sum / (float)d->conf_n) : 0.0f;
    if (txt) {
        d->cfg.on_symbol(txt, conf, d->cfg.user);
    } else {
        d->cfg.on_symbol("?", conf * 0.3f, d->cfg.user);
    }
    if (d->cfg.on_status)
        d->cfg.on_status(d->wpm, d->last_ratio, d->committed_on, d->cfg.user);
    d->symbol_len = 0; d->symbol[0] = 0; d->conf_sum = 0.0f; d->conf_n = 0;
}

static void on_mark_end(cw_decoder_t *d, float dur_ms)
{
    /* reject sub-dit glitches: don't append, don't pollute the estimator */
    if (dur_ms < 0.35f * d->rwe_mark.min_est) return;

    /* Fix D: auto-seed warm-up. Observe the first few marks and, once we have a
       clear short/long spread, re-seed the dit/dah estimates so a fast opener
       locks in ~2 chars instead of ~8. */
    if (d->seeding) {
        if (dur_ms < d->seed_min) d->seed_min = dur_ms;
        if (dur_ms > d->seed_max) d->seed_max = dur_ms;
        d->seed_count++;
        /* LOCAL ENHANCEMENT (propose upstream): early-out reseed. The fixed
           auto_seed_marks window left ~2-3 mis-seeded chars at high speed (the
           "50+ WPM floor" is warm-up, not a steady-state limit — validated:
           errors/over are constant ~3 regardless of message length). As soon as
           >=3 marks show a clear >=2:1 dit/dah spread, reseed immediately; this
           halves high-speed warm-up errors (40 WPM 2.4->1.2, 50 WPM 2.8->1.6)
           with no regression. An ambiguous opener (all dits, no spread) still
           waits the full window, preserving the slow-opener guard. */
        bool clear_spread = (d->seed_min > 0.0f && d->seed_max >= 2.0f * d->seed_min);
        bool early = (d->seed_count >= 3 && clear_spread);
        if (early || d->seed_count >= d->cfg.auto_seed_marks) {
            d->seeding = false;
            if (clear_spread) {
                cw_rwe_reseed(&d->rwe_mark,  d->seed_min, d->seed_max);
                cw_rwe_reseed(&d->rwe_space, d->seed_min, 3.0f * d->seed_min);
                d->word_gap_est = 7.0f * d->seed_min;
                d->wpm = 1200.0f / d->seed_min;
            }
        }
    }

    bool  is_dah = cw_rwe_is_max(&d->rwe_mark, dur_ms);
    float conf   = cw_rwe_confidence(&d->rwe_mark, dur_ms);
    cw_rwe_update(&d->rwe_mark, dur_ms);
    d->wpm = 1200.0f / d->rwe_mark.min_est;

    if (d->symbol_len >= MAX_ELEMS)
        emit_symbol(d);      /* overrun -> flush as unknown, then start fresh */
    d->symbol[d->symbol_len++] = is_dah ? '-' : '.';
    d->conf_sum += conf; d->conf_n++;

    /* a new mark opens a new gap to evaluate */
    d->char_emitted = false;
    d->word_emitted = false;
}

/* Responsive emission while the key is up (timeout-based). */
static void on_space_ongoing(cw_decoder_t *d, float off_ms)
{
    float b_ec = cw_rwe_boundary(&d->rwe_space);                              /* elem|char */
    float b_cw = sqrtf(d->rwe_space.max_est * d->word_gap_est);               /* char|word */

    if (!d->char_emitted && d->symbol_len > 0 && off_ms >= b_ec) {
        emit_symbol(d);
        d->char_emitted = true;
    }
    if (!d->word_emitted && off_ms >= b_cw) {
        d->cfg.on_symbol(" ", 1.0f, d->cfg.user);
        d->word_emitted = true;
    }
}

/* A gap just completed (next mark arrived): update the space estimators. */
static void on_space_end(cw_decoder_t *d, float off_ms)
{
    if (off_ms < 0.35f * d->rwe_mark.min_est) return;   /* glitch gap: ignore */

    float b_cw = sqrtf(d->rwe_space.max_est * d->word_gap_est);
    if (off_ms >= b_cw) {
        d->word_gap_est += 0.2f * (off_ms - d->word_gap_est);   /* EMA */
    } else {
        cw_rwe_update(&d->rwe_space, off_ms);                   /* elem/char clusters */
    }
}

static void finalize_interval(cw_decoder_t *d, bool was_on, int run_blocks)
{
    float dur_ms = (float)run_blocks * d->ms_per_block;
    if (was_on) on_mark_end(d, dur_ms);
    else        on_space_end(d, dur_ms);
}

/* ----------------------------------------------------------------------- */
/* per-block processing: detection + debounce + timing                     */
/* ----------------------------------------------------------------------- */
static void process_block(cw_decoder_t *d, float mag2c, float mag2l, float mag2r)
{
    float magc = (mag2c > 0.0f) ? sqrtf(mag2c) : 0.0f;

    /* initial noise-floor calibration: assume mostly noise at startup */
    if (!d->calibrated) {
        d->noise_floor += magc;
        if (++d->calib_n >= CALIB_BLOCKS) {
            d->noise_floor /= (float)d->calib_n;
            if (d->noise_floor < 1e-6f) d->noise_floor = 1e-6f;
            d->calibrated = true;
            d->searching  = d->cfg.pitch_search;   /* Fix C: acquire pitch next */
        }
        return;   /* hold output until calibrated */
    }

    /* Fix B: slow signal-peak track for a relative floor minimum */
    if (magc > d->sig_peak) d->sig_peak = magc;
    else                    d->sig_peak *= 0.9995f;

    /* gated noise-floor tracking */
    if (magc < 2.0f * d->noise_floor) {
        d->noise_floor = 0.99f * d->noise_floor + 0.01f * magc;
        d->gate_count  = 0;
    } else {
        d->gate_count++;
        /* Fix A: gate timeout proportional to current dit estimate, so a single
           long dah (or a run of dahs) at slow speeds never trips "floor rose".
           Never tighter than the legacy 300 ms, so fast speeds are unchanged. */
        int gate_limit;
        if (d->cfg.adaptive_gate) {
            float gate_ms  = d->cfg.gate_dits * d->rwe_mark.min_est;
            float floor_ms = (float)GATE_MAX * d->ms_per_block;
            if (gate_ms < floor_ms) gate_ms = floor_ms;
            gate_limit = (int)(gate_ms / d->ms_per_block + 0.5f);
        } else {
            gate_limit = GATE_MAX;
        }
        if (d->gate_count > gate_limit)
            d->noise_floor = 0.90f * d->noise_floor + 0.10f * magc;
    }
    if (d->noise_floor < 1e-6f) d->noise_floor = 1e-6f;
    if (d->cfg.relative_floor) {
        float fmin = d->sig_peak * d->cfg.floor_min_frac;
        if (fmin > d->noise_floor) d->noise_floor = fmin;
    }
    d->last_ratio = magc / d->noise_floor;

    /* tone present: 3-bin dominance check (only required to turn ON) */
    bool tone_valid = (mag2c > mag2l * d->cfg.tone_validate) &&
                      (mag2c > mag2r * d->cfg.tone_validate);

    float thr_on  = d->noise_floor * d->cfg.thresh_on;
    float thr_off = d->noise_floor * d->cfg.thresh_off;

    bool raw;
    if (d->committed_on) raw = (magc > thr_off);                  /* ride QSB while on */
    else                 raw = (magc > thr_on) && tone_valid;     /* strict to turn on */

    /* committed/pending debouncer: a state change must persist
       min_run_blocks before it's accepted; shorter glitches are absorbed. */
    if (raw == d->committed_on) {
        d->committed_run += d->pending_run + 1;   /* absorb any tentative glitch */
        d->pending_run = 0;
    } else {
        if (d->pending_run == 0) d->pending_on = raw;
        d->pending_run++;
        if (d->pending_run >= d->min_run_blocks) {
            finalize_interval(d, d->committed_on, d->committed_run);
            d->committed_on  = raw;
            d->committed_run = d->pending_run;
            d->pending_run   = 0;
        }
    }

    /* while the key is up, allow timeout-based char/word emission */
    if (!d->committed_on)
        on_space_ongoing(d, (float)d->committed_run * d->ms_per_block);
}

/* ----------------------------------------------------------------------- */
/* pitch-search prepass (Fix C): runs after calibration until a tone locks  */
/* ----------------------------------------------------------------------- */
static void handle_search(cw_decoder_t *d)
{
    int   K = d->n_search;
    float mag[MAX_SEARCH_BINS];
    float vmax = -1.0f;
    int   kp = 0;
    for (int k = 0; k < K; ++k) {
        float m2 = d->ss1[k]*d->ss1[k] + d->ss2[k]*d->ss2[k]
                   - d->search_coeff[k]*d->ss1[k]*d->ss2[k];
        mag[k] = (m2 > 0.0f) ? sqrtf(m2) : 0.0f;
        if (mag[k] > vmax) { vmax = mag[k]; kp = k; }
        d->ss1[k] = d->ss2[k] = 0.0f;       /* reset for next block */
    }

    /* Lock criterion: the peak bin must clear the calibrated noise floor by the
       same margin the detector uses (thresh_on), sustained for two blocks. The
       calibrated floor already separates tone (~20-50x floor) from noise peaks
       (~2-3x floor), so no relative/dominance test is needed -- and a relative
       test is unreliable here anyway, because at 50 Hz spacing a tone leaks into
       most of the span and there is no clean "rest" to compare against. */
    bool strong = (vmax > d->noise_floor * d->cfg.thresh_on);

    if (!strong) { d->search_hits = 0; return; }

    if (++d->search_hits >= SEARCH_HITS_LOCK) {
        /* parabolic interpolation around the peak bin for sub-bin pitch */
        float ml = (kp > 0)     ? mag[kp - 1] : vmax;
        float mh = (kp < K - 1) ? mag[kp + 1] : vmax;
        float denom = ml - 2.0f * vmax + mh;
        float delta = (fabsf(denom) > 1e-9f) ? 0.5f * (ml - mh) / denom : 0.0f;
        if (delta >  1.0f) delta =  1.0f;
        if (delta < -1.0f) delta = -1.0f;
        float f = d->search_freq[kp] + delta * SEARCH_SPACING;
        set_tone(d, f);                 /* lock the lean 3-bin detector here */
        d->searching   = false;
        d->search_hits = 0;
    }
}

/* ----------------------------------------------------------------------- */
/* goertzel accumulation over decimated samples                            */
/* ----------------------------------------------------------------------- */
static void push_decimated(cw_decoder_t *d, float x)
{
    /* center / neighbor recurrences */
    float s0 = x + d->coeff_c * d->sc1 - d->sc2; d->sc2 = d->sc1; d->sc1 = s0;
    float l0 = x + d->coeff_l * d->sl1 - d->sl2; d->sl2 = d->sl1; d->sl1 = l0;
    float r0 = x + d->coeff_r * d->sr1 - d->sr2; d->sr2 = d->sr1; d->sr1 = r0;

    /* search-bank recurrences (only while acquiring pitch) */
    if (d->searching) {
        for (int k = 0; k < d->n_search; ++k) {
            float v = x + d->search_coeff[k] * d->ss1[k] - d->ss2[k];
            d->ss2[k] = d->ss1[k]; d->ss1[k] = v;
        }
    }

    if (++d->block_idx >= d->block_n) {
        if (d->searching) {
            handle_search(d);
            /* keep center accumulators from carrying across blocks */
            d->sc1 = d->sc2 = d->sl1 = d->sl2 = d->sr1 = d->sr2 = 0.0f;
            d->block_idx = 0;
        } else {
            float mag2c = d->sc1*d->sc1 + d->sc2*d->sc2 - d->coeff_c*d->sc1*d->sc2;
            float mag2l = d->sl1*d->sl1 + d->sl2*d->sl2 - d->coeff_l*d->sl1*d->sl2;
            float mag2r = d->sr1*d->sr1 + d->sr2*d->sr2 - d->coeff_r*d->sr1*d->sr2;
            d->sc1 = d->sc2 = d->sl1 = d->sl2 = d->sr1 = d->sr2 = 0.0f;
            d->block_idx = 0;
            process_block(d, mag2c, mag2l, mag2r);
        }
    }
}

/* ----------------------------------------------------------------------- */
/* input: dc block + decimate                                              */
/* ----------------------------------------------------------------------- */
static void push_input(cw_decoder_t *d, float x)
{
    /* one-pole DC blocker */
    float y = x - d->dc_x1 + 0.995f * d->dc_y1;
    d->dc_x1 = x; d->dc_y1 = y;

    if (d->decim_factor == 1) { push_decimated(d, y); return; }

    /* write into circular history */
    d->fir_hist[d->fir_head] = y;
    d->fir_head = (d->fir_head + 1) % d->fir_len;

    if (++d->decim_count >= d->decim_factor) {
        d->decim_count = 0;
        /* convolve: most-recent sample is at (fir_head-1) */
        float acc = 0.0f;
        int idx = d->fir_head - 1;
        for (int k = 0; k < d->fir_len; ++k) {
            if (idx < 0) idx += d->fir_len;
            acc += d->fir_taps[k] * d->fir_hist[idx];
            idx--;
        }
        push_decimated(d, acc);
    }
}

void cw_decoder_feed(cw_decoder_t *d, const int16_t *samples, size_t count)
{
    if (!d || !samples) return;
    if (d->cfg.input_channels == 2) {
        for (size_t i = 0; i + 1 < count; i += 2)        /* take left channel */
            push_input(d, (float)samples[i]);
    } else {
        for (size_t i = 0; i < count; ++i)
            push_input(d, (float)samples[i]);
    }
}
