/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#include <jni.h>

#include <cstdint>
#include <cstring>
#include <vector>
#if defined(__linux__)
#include <sched.h>
#endif
#if defined(__aarch64__)
#include <sys/auxv.h>
#endif

#include "matrix-q8.h"

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
 * A row's eight lanes are added up in MatrixKernel.JVM's order (dotQ), the rows left over too, and the target
 * is built without fused multiply-add (ART keeps the two apart): the JVM's bits.
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

/*
 * rowBlock for V vectors at once: each weight is converted once for all of them, and each (row, vector)
 * pair sums exactly as rowBlock does, so the floats are the same bits.
 */
template <jint N, jint V>
void rowBlockMany(const int8_t *q, jint columns, const float *x, jint rows, float *y) {
    f4 a[N][V][2] = {};
    jint i = 0;
    for (; i + Step <= columns; i += Step) {
        f4 w[N][2];
        for (jint r = 0; r < N; r++) {
            const int8_t *row = q + static_cast<ptrdiff_t>(r) * columns + i;
            w[r][0] = floats(row);
            w[r][1] = floats(row + 4);
        }
        for (jint v = 0; v < V; v++) {
            f4 x0 = floats(x + static_cast<ptrdiff_t>(v) * columns + i), x1 = floats(x + static_cast<ptrdiff_t>(v) * columns + i + 4);
            for (jint r = 0; r < N; r++) {
                a[r][v][0] += x0 * w[r][0];
                a[r][v][1] += x1 * w[r][1];
            }
        }
    }
    for (jint v = 0; v < V; v++) {
        const float *xv = x + static_cast<ptrdiff_t>(v) * columns;
        for (jint r = 0; r < N; r++) {
            float s = sum(a[r][v][0]) + sum(a[r][v][1]);
            for (jint j = i; j < columns; j++) s += xv[j] * static_cast<float>(q[static_cast<ptrdiff_t>(r) * columns + j]);
            y[static_cast<ptrdiff_t>(v) * rows + r] = s;
        }
    }
}

template <jint V>
void rowsMany(const int8_t *qs, jint columns, const float *x, jint rows, float *y) {
    jint r = 0;
    for (; r + Rows <= rows; r += Rows) rowBlockMany<Rows, V>(qs + static_cast<ptrdiff_t>(r) * columns, columns, x, rows, y + r);
    for (; r < rows; r++) rowBlockMany<1, V>(qs + static_cast<ptrdiff_t>(r) * columns, columns, x, rows, y + r);
}

void throwIllegal(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
}

// the weights where the model file is mapped, not copied (they are megabytes), and every size: a wrong one
// would read or write past a buffer or an array (MatrixKernel's contract, checked, as it is cheap)
const int8_t *checked(JNIEnv *env, jobject q, jfloatArray scales, jint rows, jint columns, jfloatArray x, jint m, jfloatArray y) {
    auto *qs = static_cast<const int8_t *>(env->GetDirectBufferAddress(q));
    if (!qs) {
        throwIllegal(env, "q is not a direct buffer");
        return nullptr;
    }
    if (rows < 0 || columns < 0 || m < 0 ||
        env->GetDirectBufferCapacity(q) != static_cast<jlong>(rows) * columns ||
        env->GetArrayLength(scales) < rows || env->GetArrayLength(x) < static_cast<jlong>(m) * columns ||
        env->GetArrayLength(y) < static_cast<jlong>(m) * rows) {
        throwIllegal(env, "matrix and vector sizes differ");
        return nullptr;
    }
    return qs;
}

// CpuNative.SDOT_*
[[maybe_unused]] constexpr jint SdotPassed = 0, SdotElsewhere = 1, SdotFailed = 2;

} // namespace

extern "C"
JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_core_NativeMatrixKernel_times(JNIEnv *env, jobject, jobject q, jfloatArray scales,
                                                            jint rows, jint columns, jfloatArray x, jfloatArray y) {
    const int8_t *qs = checked(env, q, scales, rows, columns, x, 1, y);
    if (!qs) return;
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

extern "C"
JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_core_NativeMatrixKernel_timesMany(JNIEnv *env, jobject, jobject q, jfloatArray scales,
                                                                jint rows, jint columns, jfloatArray x, jint m, jfloatArray y) {
    const int8_t *qs = checked(env, q, scales, rows, columns, x, m, y);
    if (!qs) return;
    std::vector<float> ss(rows), xs(static_cast<size_t>(m) * columns), ys(static_cast<size_t>(m) * rows);
    env->GetFloatArrayRegion(scales, 0, rows, ss.data());
    env->GetFloatArrayRegion(x, 0, m * columns, xs.data());
    jint v = 0;
    for (; v + 2 <= m; v += 2) rowsMany<2>(qs, columns, xs.data() + static_cast<size_t>(v) * columns, rows, ys.data() + static_cast<size_t>(v) * rows);
    for (; v < m; v++) rowsMany<1>(qs, columns, xs.data() + static_cast<size_t>(v) * columns, rows, ys.data() + static_cast<size_t>(v) * rows);
    for (v = 0; v < m; v++)
        for (jint r = 0; r < rows; r++) ys[static_cast<size_t>(v) * rows + r] *= ss[r];
    env->SetFloatArrayRegion(y, 0, m * rows, ys.data());
}

extern "C"
JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_core_NativeMatrixKernel_timesManyQ8(JNIEnv *env, jobject, jobject q, jfloatArray scales,
                                                                  jint rows, jint columns, jfloatArray x, jint m, jfloatArray y) {
    const int8_t *qs = checked(env, q, scales, rows, columns, x, m, y);
    if (!qs) return;
    std::vector<float> ss(rows), xs(static_cast<size_t>(m) * columns), ys(static_cast<size_t>(m) * rows), sx(m);
    std::vector<int8_t> xq(static_cast<size_t>(m) * columns);
    std::vector<int32_t> dots(static_cast<size_t>(m) * rows);
    env->GetFloatArrayRegion(scales, 0, rows, ss.data());
    env->GetFloatArrayRegion(x, 0, m * columns, xs.data());
    for (jint v = 0; v < m; v++) sx[v] = youmo::quantize(xs.data() + static_cast<size_t>(v) * columns, columns, xq.data() + static_cast<size_t>(v) * columns);
    youmo::dotsQ8(qs, columns, xq.data(), m, rows, dots.data());
    for (jint v = 0; v < m; v++)
        for (jint r = 0; r < rows; r++) {
            size_t k = static_cast<size_t>(v) * rows + r;
            float f = static_cast<float>(dots[k]) * ss[r];
            ys[k] = f * sx[v];
        }
    env->SetFloatArrayRegion(y, 0, m * rows, ys.data());
}

extern "C"
JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_core_NativeMatrixKernel_geluF(JNIEnv *env, jobject, jfloatArray x, jint n) {
    if (n < 0 || env->GetArrayLength(x) < n) return throwIllegal(env, "sizes differ");
    std::vector<float> xs(n);
    env->GetFloatArrayRegion(x, 0, n, xs.data());
    youmo::geluF(xs.data(), n);
    env->SetFloatArrayRegion(x, 0, n, xs.data());
}

extern "C"
JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_core_NativeMatrixKernel_logSumsF(JNIEnv *env, jobject, jfloatArray logits, jint m, jint vocab, jfloatArray out) {
    if (m < 0 || vocab < 0 || env->GetArrayLength(logits) < static_cast<jlong>(m) * vocab || env->GetArrayLength(out) < m)
        return throwIllegal(env, "sizes differ");
    std::vector<float> ls(static_cast<size_t>(m) * vocab), os(m);
    env->GetFloatArrayRegion(logits, 0, m * vocab, ls.data());
    for (jint v = 0; v < m; v++) os[v] = youmo::logSumF(ls.data() + static_cast<size_t>(v) * vocab, vocab);
    env->SetFloatArrayRegion(out, 0, m, os.data());
}

extern "C"
JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_core_NativeMatrixKernel_mathF(JNIEnv *env, jobject, jint log, jfloatArray x, jint n) {
    if (n < 0 || env->GetArrayLength(x) < n) return throwIllegal(env, "sizes differ");
    std::vector<float> xs(n);
    env->GetFloatArrayRegion(x, 0, n, xs.data());
    for (float &f : xs) f = log ? youmo::logF(f) : youmo::expF(f);
    env->SetFloatArrayRegion(x, 0, n, xs.data());
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_fcitx_fcitx5_android_core_NativeMatrixKernel_dotsQ8(JNIEnv *env, jobject, jbyteArray q, jint rows, jint columns,
                                                             jbyteArray xq, jint m, jintArray dots) {
    if (rows < 0 || columns < 0 || m < 0 || env->GetArrayLength(q) != static_cast<jlong>(rows) * columns ||
        env->GetArrayLength(xq) != static_cast<jlong>(m) * columns || env->GetArrayLength(dots) != static_cast<jlong>(m) * rows) {
        throwIllegal(env, "sizes differ");
        return 0;
    }
    std::vector<int8_t> qs(static_cast<size_t>(rows) * columns), xs(static_cast<size_t>(m) * columns);
    std::vector<int32_t> ds(static_cast<size_t>(m) * rows);
    env->GetByteArrayRegion(q, 0, rows * columns, qs.data());
    env->GetByteArrayRegion(xq, 0, m * columns, xs.data());
    jint ran = youmo::dotsQ8(qs.data(), columns, xs.data(), m, rows, ds.data());
    env->SetIntArrayRegion(dots, 0, m * rows, ds.data());
    return ran;
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_fcitx_fcitx5_android_core_NativeMatrixKernel_useKernel(JNIEnv *env, jobject, jint kind, jboolean dotprodAllowed) {
    if (kind < youmo::KernelPortable || kind > youmo::KernelSdot) {
        throwIllegal(env, "no such kernel");
        return 0;
    }
    return youmo::useKernel(kind, dotprodAllowed);
}

extern "C"
JNIEXPORT jlongArray JNICALL
Java_org_fcitx_fcitx5_android_core_NativeMatrixKernel_hwcaps(JNIEnv *env, jobject) {
    // zeros off arm64: x86's AT_HWCAP is CPUID's bits, which would read as ARM features
    jlong caps[2] = {0, 0};
#if defined(__aarch64__)
    caps[0] = static_cast<jlong>(getauxval(AT_HWCAP));
    caps[1] = static_cast<jlong>(getauxval(AT_HWCAP2));
#endif
    jlongArray out = env->NewLongArray(2);
    if (out) env->SetLongArrayRegion(out, 0, 2, caps);
    return out;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_fcitx_fcitx5_android_core_NativeMatrixKernel_affinityGet(JNIEnv *, jobject) {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof set, &set) != 0) return 0;
    jlong mask = 0;
    for (int c = 0; c < 64; c++)
        if (CPU_ISSET(c, &set)) mask |= jlong{1} << c;
    return mask;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_fcitx_fcitx5_android_core_NativeMatrixKernel_affinitySet(JNIEnv *, jobject, jlong mask) {
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int c = 0; c < 64; c++)
        if ((static_cast<uint64_t>(mask) >> c) & 1u) CPU_SET(c, &set);
    return sched_setaffinity(0, sizeof set, &set) == 0 ? 0 : -1;
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_fcitx_fcitx5_android_core_NativeMatrixKernel_sdotSelfTest(JNIEnv *, jobject, jint cpu) {
#if defined(__aarch64__)
    // a kernel isolating cores (core_ctl) may run a thread pinned to one elsewhere: then that core is untested
    if (sched_getcpu() != cpu) return SdotElsewhere;
    int8_t a[16], b[16];
    int32_t expected = 0;
    for (int i = 0; i < 16; i++) {
        // from cpu, so nothing is known at build time; the extremes included
        a[i] = static_cast<int8_t>(i == 0 ? INT8_MIN : (i * 37 + cpu * 11) % 256 - 128);
        b[i] = static_cast<int8_t>(i == 1 ? INT8_MAX : (i * 53 + cpu * 7 + 3) % 256 - 128);
        expected += static_cast<int32_t>(a[i]) * b[i];
    }
    int32_t got = youmo::sdot16(a, b);
    if (got != expected) return SdotFailed;
    return sched_getcpu() == cpu ? SdotPassed : SdotElsewhere;
#else
    (void)cpu;
    return SdotFailed;
#endif
}
