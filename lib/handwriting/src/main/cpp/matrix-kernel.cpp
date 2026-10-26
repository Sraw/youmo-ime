/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#include <jni.h>

#include <cstdint>
#include <cstring>
#include <vector>

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
 * y[r] = row r · x for N rows from q, as floats. Rows at a time, so x is loaded once for them;
 * two sums per row, eight in all, so no addition waits on the one before. Left to itself, clang
 * keeps one sum, and the loop runs at the speed of one addition after another: a third of this.
 * A row's eight lanes are added up in MatrixKernel.JVM's order (dotQ), the rows left over too:
 * only arm64's fused multiply-add, one instruction for two that ART keeps apart, moves the last bits.
 */
template <jint N>
void rowBlock(const int8_t *q, jint columns, const float *x, float *y) {
    f4 a[N][2] = {};
    jint i = 0;
    for (; i + Step <= columns; i += Step) {
        f4 x0 = floats(x + i), x1 = floats(x + i + 4);
        for (jint r = 0; r < N; r++) {
            const int8_t *row = q + static_cast<ptrdiff_t>(r) * columns + i;
            a[r][0] += x0 * floats(row);
            a[r][1] += x1 * floats(row + 4);
        }
    }
    for (jint r = 0; r < N; r++) {
        float s = sum(a[r][0]) + sum(a[r][1]);
        for (jint j = i; j < columns; j++) s += x[j] * static_cast<float>(q[static_cast<ptrdiff_t>(r) * columns + j]);
        y[r] = s;
    }
}

} // namespace

extern "C"
JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_core_NativeMatrixKernel_times(JNIEnv *env, jobject, jobject q, jfloatArray scales,
                                                            jint rows, jint columns, jfloatArray x, jfloatArray y) {
    // the weights where the model file is mapped, not copied (they are megabytes); null for a heap buffer
    auto *qs = static_cast<const int8_t *>(env->GetDirectBufferAddress(q));
    if (!qs) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "q is not a direct buffer");
        return;
    }
    // a wrong size would read or write past a buffer or an array: MatrixKernel's contract, checked, as it is cheap
    if (rows < 0 || columns < 0 ||
        env->GetDirectBufferCapacity(q) != static_cast<jlong>(rows) * columns ||
        env->GetArrayLength(scales) < rows || env->GetArrayLength(x) < columns || env->GetArrayLength(y) < rows) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "matrix and vector sizes differ");
        return;
    }
    // copied, not held critical: a row's or a column's floats, and q's pages, read in from the file as
    // first touched, would keep ART's GC thread flip waiting on them, and with it threads that need memory
    std::vector<float> ss(rows), xs(columns), ys(rows);
    env->GetFloatArrayRegion(scales, 0, rows, ss.data());
    env->GetFloatArrayRegion(x, 0, columns, xs.data());
    jint r = 0;
    for (; r + Rows <= rows; r += Rows) rowBlock<Rows>(qs + static_cast<ptrdiff_t>(r) * columns, columns, xs.data(), ys.data() + r);
    for (; r < rows; r++) rowBlock<1>(qs + static_cast<ptrdiff_t>(r) * columns, columns, xs.data(), ys.data() + r);
    for (r = 0; r < rows; r++) ys[r] *= ss[r];
    env->SetFloatArrayRegion(y, 0, rows, ys.data());
}
