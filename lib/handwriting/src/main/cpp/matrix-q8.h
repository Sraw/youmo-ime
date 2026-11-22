/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#pragma once

#include <jni.h>

#include <cstddef>
#include <cstdint>

namespace youmo {

// NativeMatrixKernel's PORTABLE, MLAL and SDOT
constexpr int KernelPortable = 1, KernelMlal = 2, KernelSdot = 3;

// MatrixKernel.kt's quantize, expF, logF, geluF and logSumF: the same float operations in the same order,
// built without fused multiply-add, so the same bits as the JVM's
float quantize(const float *x, jint n, int8_t *out);
float expF(float x);
float logF(float x);
void geluF(float *x, jint n);
float logSumF(const float *logits, jint n);

// AT_HWCAP's ASIMDDP: ARMv8.2's SDOT, as the kernel cleared it for every core
bool hwcapDotprod();

// sets timesManyQ8's integer kernel: SDOT only with the hwcap or dotprodAllowed (the profile's self-test), else
// MLAL on arm64, PORTABLE elsewhere; returns the kernel now used
int useKernel(int kind, bool dotprodAllowed);

// dots[v * rows + r] = row r · xq[v] in int32 by the kernel set (or one that suits m and columns better); exact, so
// every kernel gives the same int32s. Returns the kernel that ran, which only a test can otherwise tell
int dotsQ8(const int8_t *q, jint columns, const int8_t *xq, jint m, jint rows, int32_t *dots);

#if defined(__aarch64__)
// in the file built with +dotprod: columns a multiple of 16
void dotsQ8Sdot(const int8_t *q, jint columns, const int8_t *xq, jint m, jint rows, int32_t *dots);
int32_t sdot16(const int8_t *a, const int8_t *b);
#endif

} // namespace youmo
