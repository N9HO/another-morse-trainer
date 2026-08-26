/*
 * cw_rwe.h  --  Mills Ratio-Weighted Estimation (RWE)
 *
 * A two-cluster online estimator. Given a stream of interval durations that
 * naturally fall into a "short" cluster and a "long" cluster with a known
 * ideal ratio (rho), RWE tracks the mean of each cluster and adapts quickly
 * when the operator's speed/weighting drifts.
 *
 * Core idea (Mills, TR-554, 1977): keep a short ring of recent samples. The
 * window's max and min are instantaneous estimates of the long/short cluster
 * means. Weight each update by how close the window's observed ratio (max/min)
 * is to the ideal ratio rho -- clean-looking windows update strongly, ambiguous
 * windows (e.g. all-dits) barely update. The update step uses a power-of-two
 * (shift) attenuation:  est += (window_extreme - est) * 2^(-w),
 * where  w = k + q * |(r - rho)/rho|^p.
 *
 * We additionally guard updates by the current decision boundary so an all-short
 * or all-long window cannot erode the opposite cluster's estimate.
 */
#ifndef CW_RWE_H
#define CW_RWE_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CW_RWE_RING 4   /* Mills used 3-4; longer = smoother, slower to adapt */

typedef struct {
    float ratio_ideal;          /* rho, e.g. 3.0 for dit:dah                  */
    float k_base, q, p;         /* weight-function constants (1.0, 10.0, 2.0) */
    float min_est, max_est;     /* tracked cluster means (short, long)        */
    float ring[CW_RWE_RING];
    int   len, count, pos;
} cw_rwe_t;

/* Seed the estimator. min_seed < max_seed (both > 0). Sets weights to defaults
 * (k=1.0, q=10.0, p=2.0). */
void  cw_rwe_init(cw_rwe_t *r, float ratio_ideal, float min_seed, float max_seed);

/* Override the weight-function parameters (tuning knob). Call AFTER cw_rwe_init.
 * Lower ratio_ideal (~2.5) + lower q (~5) makes the estimator track off-ideal but
 * still-bimodal fists (e.g. compressed dah:dit ~2:1) instead of ignoring them. */
void  cw_rwe_set_params(cw_rwe_t *r, float ratio_ideal, float k_base, float q, float p);

/* Re-seed the cluster estimates without disturbing the weight parameters or
 * ratio_ideal. Clears the sample ring. Used by the auto-seed warm-up. */
void  cw_rwe_reseed(cw_rwe_t *r, float min_seed, float max_seed);

/* Feed one observed interval (ms). Updates the cluster estimates. */
void  cw_rwe_update(cw_rwe_t *r, float sample_ms);

/* Geometric-mean decision boundary between the two clusters. */
float cw_rwe_boundary(const cw_rwe_t *r);

/* true if sample belongs to the long (max) cluster. */
bool  cw_rwe_is_max(const cw_rwe_t *r, float sample_ms);

/* 0.0 at the boundary .. 1.0 at a cluster center (log-domain margin). */
float cw_rwe_confidence(const cw_rwe_t *r, float sample_ms);

#ifdef __cplusplus
}
#endif

#endif /* CW_RWE_H */
