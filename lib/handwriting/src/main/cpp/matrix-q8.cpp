/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#include "matrix-q8.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <limits>
#if defined(__aarch64__)
#include <arm_neon.h>
#include <sys/auxv.h>
#endif

namespace youmo {

namespace {

inline float fromBits(uint32_t bits) {
    float f;
    std::memcpy(&f, &bits, sizeof f);
    return f;
}

inline uint32_t toBits(float f) {
    uint32_t bits;
    std::memcpy(&bits, &f, sizeof bits);
    return bits;
}

// Kotlin's Float.toInt() for the values these functions meet: NaN is 0, where a C++ cast is undefined
inline int32_t toInt(float f) { return f == f ? static_cast<int32_t>(f) : 0; }

} // namespace

float quantize(const float *x, jint n, int8_t *out) {
    float max = 0;
    // Kotlin's maxOf: a NaN stays, as fmax would drop it
    for (jint i = 0; i < n; i++) {
        float a = std::fabs(x[i]);
        if (max == max && !(max >= a)) max = a;
    }
    if (max == 0) {
        std::memset(out, 0, static_cast<size_t>(n));
        return 0;
    }
    float inv = 127.0f / max;
    for (jint i = 0; i < n; i++) {
        float f = std::floor(x[i] * inv + 0.5f);
        // NaN (zero times an infinite inv) is 0 as in Kotlin's toInt
        out[i] = static_cast<int8_t>(f != f ? 0 : (f > 127 ? 127 : (f < -127 ? -127 : static_cast<int32_t>(f))));
    }
    return max / 127.0f;
}

float expF(float x0) {
    float x1 = x0 < -87.3f ? -87.3f : (x0 > 88.3f ? 88.3f : x0);
    float fx = std::floor(x1 * 1.442695f + 0.5f);
    float x = x1 - fx * 0.6933594f;
    x -= fx * -2.1219444e-4f;
    float z = x * x;
    float y = 1.9875691e-4f;
    y = y * x + 1.3981999e-3f;
    y = y * x + 8.333452e-3f;
    y = y * x + 4.1665796e-2f;
    y = y * x + 0.16666666f;
    y = y * x + 0.5f;
    y = y * z + x + 1.0f;
    return y * fromBits(static_cast<uint32_t>(toInt(fx) + 127) << 23);
}

float logF(float x0) {
    if (x0 != x0 || x0 < 0) return std::numeric_limits<float>::quiet_NaN();
    if (x0 == 0) return -std::numeric_limits<float>::infinity();
    if (x0 == std::numeric_limits<float>::infinity()) return x0;
    uint32_t bits = toBits(x0);
    int32_t e = -126;
    if (bits < 0x00800000u) {
        bits = toBits(x0 * 8388608.0f);
        e -= 23;
    }
    e += static_cast<int32_t>(bits >> 23);
    float x = fromBits((bits & 0x007fffffu) | 0x3f000000u);
    if (x < 0.70710677f) {
        e -= 1;
        x = x + x - 1.0f;
    } else {
        x -= 1.0f;
    }
    float z = x * x;
    float y = 0.070376836f;
    y = y * x + -0.1151461f;
    y = y * x + 0.116769984f;
    y = y * x + -0.12420141f;
    y = y * x + 0.14249323f;
    y = y * x + -0.16668057f;
    y = y * x + 0.20000714f;
    y = y * x + -0.24999994f;
    y = y * x + 0.3333333f;
    y = y * x * z;
    float fe = static_cast<float>(e);
    y += -2.1219444e-4f * fe;
    y += -0.5f * z;
    x += y;
    x += 0.6933594f * fe;
    return x;
}

void geluF(float *xs, jint n) {
    for (jint i = 0; i < n; i++) {
        float x = xs[i];
        float z = std::fabs(x * 0.70710677f);
        float t = 1.0f / (1.0f + 0.3275911f * z);
        float p = 1.0614054f;
        p = p * t + -1.4531521f;
        p = p * t + 1.4214138f;
        p = p * t + -0.28449672f;
        p = p * t + 0.2548296f;
        float e = 1.0f - p * t * expF(-(z * z));
        float erf = x >= 0.0f ? e : -e;
        xs[i] = 0.5f * x * (1.0f + erf);
    }
}

float logSumF(const float *logits, jint n) {
    float max = -std::numeric_limits<float>::infinity();
    for (jint i = 0; i < n; i++)
        if (logits[i] > max) max = logits[i];
    double sum = 0;
    for (jint i = 0; i < n; i++) sum += static_cast<double>(expF(logits[i] - max));
    return max + logF(static_cast<float>(sum));
}

bool hwcapDotprod() {
#if defined(__aarch64__)
    return (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) != 0;
#else
    return false;
#endif
}

namespace {

void dotsPortable(const int8_t *q, jint columns, const int8_t *xq, jint m, jint rows, int32_t *dots) {
    for (jint r = 0; r < rows; r++) {
        const int8_t *row = q + static_cast<ptrdiff_t>(r) * columns;
        for (jint v = 0; v < m; v++) {
            const int8_t *x = xq + static_cast<ptrdiff_t>(v) * columns;
            int32_t s = 0;
            for (jint i = 0; i < columns; i++) s += static_cast<int32_t>(row[i]) * x[i];
            dots[static_cast<ptrdiff_t>(v) * rows + r] = s;
        }
    }
}

#if defined(__aarch64__)

/*
 * ARMv8.0 without SDOT (Cortex-A75, the Snapdragon 845's A55): SMULL and SMLAL2 add two int8 products in int16,
 * SADALP pairs of those into int32. Two products with an activation in [-127, 127] stay within ±32512, so the
 * int16 never overflows and the sums are exact. The A75 issues the multiplies on F0 and SADALP on F1.
 */
template <jint R, jint V>
void blockMlal(const int8_t *q, jint columns, const int8_t *xq, jint rows, int32_t *dots) {
    int32x4_t a[R][V];
    for (jint r = 0; r < R; r++)
        for (jint v = 0; v < V; v++) a[r][v] = vdupq_n_s32(0);
    jint i = 0;
    for (; i + 16 <= columns; i += 16) {
        int8x16_t w[R];
        for (jint r = 0; r < R; r++) w[r] = vld1q_s8(q + static_cast<ptrdiff_t>(r) * columns + i);
        for (jint v = 0; v < V; v++) {
            int8x16_t x = vld1q_s8(xq + static_cast<ptrdiff_t>(v) * columns + i);
            for (jint r = 0; r < R; r++) {
                int16x8_t p = vmull_s8(vget_low_s8(w[r]), vget_low_s8(x));
                p = vmlal_high_s8(p, w[r], x);
                a[r][v] = vpadalq_s16(a[r][v], p);
            }
        }
    }
    for (jint r = 0; r < R; r++)
        for (jint v = 0; v < V; v++) {
            const int8_t *row = q + static_cast<ptrdiff_t>(r) * columns;
            const int8_t *x = xq + static_cast<ptrdiff_t>(v) * columns;
            int32_t s = vaddvq_s32(a[r][v]);
            for (jint j = i; j < columns; j++) s += static_cast<int32_t>(row[j]) * x[j];
            dots[static_cast<ptrdiff_t>(v) * rows + r] = s;
        }
}

template <jint V>
void rowsMlal(const int8_t *q, jint columns, const int8_t *xq, jint rows, int32_t *dots) {
    jint r = 0;
    for (; r + 4 <= rows; r += 4) blockMlal<4, V>(q + static_cast<ptrdiff_t>(r) * columns, columns, xq, rows, dots + r);
    for (; r < rows; r++) blockMlal<1, V>(q + static_cast<ptrdiff_t>(r) * columns, columns, xq, rows, dots + r);
}

void dotsMlal(const int8_t *q, jint columns, const int8_t *xq, jint m, jint rows, int32_t *dots) {
    jint v = 0;
    for (; v + 3 <= m; v += 3) rowsMlal<3>(q, columns, xq + static_cast<ptrdiff_t>(v) * columns, rows, dots + static_cast<ptrdiff_t>(v) * rows);
    for (; v + 2 <= m; v += 2) rowsMlal<2>(q, columns, xq + static_cast<ptrdiff_t>(v) * columns, rows, dots + static_cast<ptrdiff_t>(v) * rows);
    for (; v < m; v++) rowsMlal<1>(q, columns, xq + static_cast<ptrdiff_t>(v) * columns, rows, dots + static_cast<ptrdiff_t>(v) * rows);
}

#endif

int best() {
#if defined(__aarch64__)
    return hwcapDotprod() ? KernelSdot : KernelMlal;
#else
    return KernelPortable;
#endif
}

// the one global the kernel writes: set at the engine's start, read at every product
std::atomic<int> active{0};

} // namespace

int useKernel(int kind, bool dotprodAllowed) {
    int k = kind;
#if defined(__aarch64__)
    if (k == KernelSdot && !dotprodAllowed && !hwcapDotprod()) k = KernelMlal;
#else
    (void)dotprodAllowed;
    k = KernelPortable;
#endif
    active.store(k, std::memory_order_relaxed);
    return k;
}

/*
 * SDOT where set and the columns are 16s; else, for one vector the portable loop (the A75's MLAL is 0.78× of it
 * there), for several MLAL, unless an activation is -128 (quantize never gives one), whose square overflows MLAL's int16.
 */
int dotsQ8(const int8_t *q, jint columns, const int8_t *xq, jint m, jint rows, int32_t *dots) {
    int k = active.load(std::memory_order_relaxed);
    if (k == 0) k = best();
#if defined(__aarch64__)
    if (k == KernelSdot && columns % 16 == 0) {
        dotsQ8Sdot(q, columns, xq, m, rows, dots);
        return KernelSdot;
    }
    const int8_t *end = xq + static_cast<ptrdiff_t>(m) * columns;
    if (k != KernelPortable && m >= 2 && std::find(xq, end, INT8_MIN) == end) {
        dotsMlal(q, columns, xq, m, rows, dots);
        return KernelMlal;
    }
#else
    (void)k;
#endif
    dotsPortable(q, columns, xq, m, rows, dots);
    return KernelPortable;
}

} // namespace youmo
