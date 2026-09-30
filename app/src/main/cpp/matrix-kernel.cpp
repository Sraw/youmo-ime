/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#include <jni.h>

#include <cstdint>
#include <cstring>

namespace {

// Clang's vectors: NEON on arm64, SSE on x86_64
typedef float f4 __attribute__((ext_vector_type(4)));
typedef int8_t b4 __attribute__((ext_vector_type(4)));

inline f4 floats(const int8_t *q) {
    b4 b;
    std::memcpy(&b, q, sizeof b);
    return __builtin_convertvector(b, f4);
}

inline f4 floats(const float *x) {
    f4 f;
    std::memcpy(&f, x, sizeof f);
    return f;
}

inline float sum(f4 v) { return (v.x + v.y) + (v.z + v.w); }

constexpr jint Rows = 4;
constexpr jint Step = 8;

/*
 * y[r] = row r · x for Rows rows from q, as floats. Rows at a time, so x is loaded once for them;
 * two sums per row, eight in all, so no addition waits on the one before. Left to itself, clang
 * keeps one sum, and the loop runs at the speed of one addition after another: a third of this.
 */
void fourRows(const int8_t *q, jint columns, const float *x, float *y) {
    f4 a[Rows][2] = {};
    jint i = 0;
    for (; i + Step <= columns; i += Step) {
        f4 x0 = floats(x + i), x1 = floats(x + i + 4);
        for (jint r = 0; r < Rows; r++) {
            const int8_t *row = q + static_cast<ptrdiff_t>(r) * columns + i;
            a[r][0] += x0 * floats(row);
            a[r][1] += x1 * floats(row + 4);
        }
    }
    for (jint r = 0; r < Rows; r++) {
        float s = sum(a[r][0] + a[r][1]);
        for (jint j = i; j < columns; j++) s += x[j] * static_cast<float>(q[static_cast<ptrdiff_t>(r) * columns + j]);
        y[r] = s;
    }
}

float dot(const int8_t *q, const float *x, jint n) {
    float s = 0;
    for (jint i = 0; i < n; i++) s += x[i] * static_cast<float>(q[i]);
    return s;
}

} // namespace

extern "C"
JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_core_NativeMatrixKernel_times(JNIEnv *env, jobject, jbyteArray q, jfloatArray scales,
                                                            jint rows, jint columns, jfloatArray x, jfloatArray y) {
    // a wrong size would read or write past an array: MatrixKernel's contract, checked, as it is cheap
    if (rows < 0 || columns < 0 ||
        env->GetArrayLength(q) != static_cast<jlong>(rows) * columns ||
        env->GetArrayLength(scales) < rows || env->GetArrayLength(x) < columns || env->GetArrayLength(y) < rows) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "matrix and vector sizes differ");
        return;
    }
    // critical: the arrays as they are, no copies (the weights are megabytes); nothing below
    // calls back into Java or blocks
    // null only when out of memory, with the exception thrown: nothing more is asked of the VM
    auto *qs = static_cast<int8_t *>(env->GetPrimitiveArrayCritical(q, nullptr));
    if (!qs) return;
    auto *ss = static_cast<float *>(env->GetPrimitiveArrayCritical(scales, nullptr));
    if (!ss) {
        env->ReleasePrimitiveArrayCritical(q, qs, JNI_ABORT);
        return;
    }
    auto *xs = static_cast<float *>(env->GetPrimitiveArrayCritical(x, nullptr));
    if (!xs) {
        env->ReleasePrimitiveArrayCritical(scales, ss, JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(q, qs, JNI_ABORT);
        return;
    }
    auto *ys = static_cast<float *>(env->GetPrimitiveArrayCritical(y, nullptr));
    if (ys) {
        jint r = 0;
        for (; r + Rows <= rows; r += Rows) fourRows(qs + static_cast<ptrdiff_t>(r) * columns, columns, xs, ys + r);
        for (; r < rows; r++) ys[r] = dot(qs + static_cast<ptrdiff_t>(r) * columns, xs, columns);
        for (r = 0; r < rows; r++) ys[r] *= ss[r];
        env->ReleasePrimitiveArrayCritical(y, ys, 0);
    }
    env->ReleasePrimitiveArrayCritical(x, xs, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(scales, ss, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(q, qs, JNI_ABORT);
}
