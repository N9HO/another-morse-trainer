/* cw_rwe.c  --  Mills Ratio-Weighted Estimation implementation */
#include "cw_rwe.h"
#include <math.h>

void cw_rwe_init(cw_rwe_t *r, float ratio_ideal, float min_seed, float max_seed)
{
    r->ratio_ideal = ratio_ideal;
    r->k_base = 1.0f;
    r->q      = 10.0f;
    r->p      = 2.0f;
    r->min_est = (min_seed > 0.0f) ? min_seed : 1.0f;
    r->max_est = (max_seed > r->min_est) ? max_seed : r->min_est * ratio_ideal;
    r->len   = CW_RWE_RING;
    r->count = 0;
    r->pos   = 0;
    for (int i = 0; i < CW_RWE_RING; ++i) r->ring[i] = 0.0f;
}

void cw_rwe_set_params(cw_rwe_t *r, float ratio_ideal, float k_base, float q, float p)
{
    if (ratio_ideal > 1.0f) r->ratio_ideal = ratio_ideal;
    if (k_base      > 0.0f) r->k_base      = k_base;
    if (q          >= 0.0f) r->q           = q;
    if (p           > 0.0f) r->p           = p;
}

void cw_rwe_reseed(cw_rwe_t *r, float min_seed, float max_seed)
{
    if (min_seed <= 0.0f) return;
    r->min_est = min_seed;
    r->max_est = (max_seed > min_seed) ? max_seed : min_seed * r->ratio_ideal;
    r->count = 0;
    r->pos   = 0;
    for (int i = 0; i < CW_RWE_RING; ++i) r->ring[i] = 0.0f;
}

float cw_rwe_boundary(const cw_rwe_t *r)
{
    float prod = r->min_est * r->max_est;
    return (prod > 0.0f) ? sqrtf(prod) : r->min_est;
}

bool cw_rwe_is_max(const cw_rwe_t *r, float sample_ms)
{
    return sample_ms > cw_rwe_boundary(r);
}

float cw_rwe_confidence(const cw_rwe_t *r, float sample_ms)
{
    if (sample_ms <= 0.0f || r->min_est <= 0.0f || r->max_est <= 0.0f)
        return 0.0f;
    float lmin = logf(r->min_est);
    float lmax = logf(r->max_est);
    float lbnd = 0.5f * (lmin + lmax);
    float half = 0.5f * (lmax - lmin);
    if (half <= 1e-6f) return 0.5f;
    float c = fabsf(logf(sample_ms) - lbnd) / half;
    if (c > 1.0f) c = 1.0f;
    return c;
}

void cw_rwe_update(cw_rwe_t *r, float s)
{
    if (s <= 0.0f) return;

    /* push into ring */
    r->ring[r->pos] = s;
    r->pos = (r->pos + 1) % r->len;
    if (r->count < r->len) r->count++;

    /* window extremes */
    float mx = -1e30f, mn = 1e30f;
    for (int i = 0; i < r->count; ++i) {
        float v = r->ring[i];
        if (v > mx) mx = v;
        if (v < mn) mn = v;
    }
    if (mn <= 0.0f) return;

    /* ratio-weighted attenuation: w = k + q*|(r-rho)/rho|^p, alpha = 2^-w */
    float ratio = mx / mn;
    float err   = (ratio - r->ratio_ideal) / r->ratio_ideal;
    float w     = r->k_base + r->q * powf(fabsf(err), r->p);
    int   shift = (int)(w + 0.5f);
    if (shift < 1)  shift = 1;
    if (shift > 16) shift = 16;
    float alpha = ldexpf(1.0f, -shift);   /* 2^-shift */

    /* Guard by current boundary so an all-short or all-long window cannot
       drag the opposite cluster. Only pull max_est toward an upper-cluster
       extreme, and min_est toward a lower-cluster extreme. */
    float bnd = cw_rwe_boundary(r);
    if (mx >= bnd) r->max_est += (mx - r->max_est) * alpha;
    if (mn <  bnd) r->min_est += (mn - r->min_est) * alpha;

    /* keep ordering + a minimum separation so the boundary stays meaningful */
    if (r->max_est < r->min_est * 1.2f)
        r->max_est = r->min_est * 1.2f;
}
