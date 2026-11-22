/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
// built with +dotprod and called only where useKernel allowed SDOT: elsewhere its instructions are SIGILL
#include "matrix-q8.h"

#if defined(__aarch64__)
#include <arm_neon.h>

namespace youmo {

namespace {

template <jint R, jint V>
void block(const int8_t *q, jint columns, const int8_t *xq, jint rows, int32_t *dots) {
    int32x4_t a[R][V];
    for (jint r = 0; r < R; r++)
        for (jint v = 0; v < V; v++) a[r][v] = vdupq_n_s32(0);
    for (jint i = 0; i < columns; i += 16) {
        int8x16_t w[R];
        for (jint r = 0; r < R; r++) w[r] = vld1q_s8(q + static_cast<ptrdiff_t>(r) * columns + i);
        for (jint v = 0; v < V; v++) {
            int8x16_t x = vld1q_s8(xq + static_cast<ptrdiff_t>(v) * columns + i);
            for (jint r = 0; r < R; r++) a[r][v] = vdotq_s32(a[r][v], w[r], x);
        }
    }
    for (jint r = 0; r < R; r++)
        for (jint v = 0; v < V; v++) dots[static_cast<ptrdiff_t>(v) * rows + r] = vaddvq_s32(a[r][v]);
}

template <jint V>
void rowsOf(const int8_t *q, jint columns, const int8_t *xq, jint rows, int32_t *dots) {
    jint r = 0;
    for (; r + 4 <= rows; r += 4) block<4, V>(q + static_cast<ptrdiff_t>(r) * columns, columns, xq, rows, dots + r);
    for (; r < rows; r++) block<1, V>(q + static_cast<ptrdiff_t>(r) * columns, columns, xq, rows, dots + r);
}

} // namespace

void dotsQ8Sdot(const int8_t *q, jint columns, const int8_t *xq, jint m, jint rows, int32_t *dots) {
    jint v = 0;
    for (; v + 4 <= m; v += 4) rowsOf<4>(q, columns, xq + static_cast<ptrdiff_t>(v) * columns, rows, dots + static_cast<ptrdiff_t>(v) * rows);
    for (; v + 2 <= m; v += 2) rowsOf<2>(q, columns, xq + static_cast<ptrdiff_t>(v) * columns, rows, dots + static_cast<ptrdiff_t>(v) * rows);
    for (; v < m; v++) rowsOf<1>(q, columns, xq + static_cast<ptrdiff_t>(v) * columns, rows, dots + static_cast<ptrdiff_t>(v) * rows);
}

// out of line, so the self-test's SDOT runs on the core it was pinned to and is not folded away
__attribute__((noinline)) int32_t sdot16(const int8_t *a, const int8_t *b) {
    return vaddvq_s32(vdotq_s32(vdupq_n_s32(0), vld1q_s8(a), vld1q_s8(b)));
}

} // namespace youmo
#endif
