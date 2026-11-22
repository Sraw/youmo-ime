/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import org.fcitx.fcitx5.android.device.CorePin
import java.nio.ByteBuffer

/**
 * `y[r] = scales[r] × (row r · x)` over int8 [q] row-major from index 0 (absolute gets); [x] and [y] must
 * differ. Nearly all sentence-model time is here; the app does it in C++, as ART runs [JVM] too slowly. The
 * app's [q] is the mapped model file, a direct buffer whose capacity must be exactly `rows × columns`. Every
 * kernel gives [JVM]'s bits (the native one is built without fused multiply-add), so no result depends on the phone.
 */
fun interface MatrixKernel {
    @Suppress("LongParameterList")
    fun times(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray)

    /** [m] vectors at once, [x] and [y] one vector after another: each the same sums as [times], the weights read once. */
    @Suppress("LongParameterList")
    fun timesMany(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) {
        val xi = FloatArray(columns)
        val yi = FloatArray(rows)
        for (v in 0 until m) {
            System.arraycopy(x, v * columns, xi, 0, columns)
            times(q, scales, rows, columns, xi, yi)
            System.arraycopy(yi, 0, y, v * rows, rows)
        }
    }

    /**
     * As [timesMany] with each vector rounded to int8 first ([quantize]): integer dot products, exact in any
     * order, so every kernel gives the same bits. A different function from [times], cheaper on dot-product hardware.
     */
    @Suppress("LongParameterList")
    fun timesManyQ8(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) {
        val xq = ByteArray(columns)
        for (v in 0 until m) {
            val sx = quantize(x, v * columns, columns, xq)
            for (r in 0 until rows) {
                val base = r * columns
                var s = 0
                for (i in 0 until columns) s += q[base + i] * xq[i]
                y[v * rows + r] = s.toFloat() * scales[r] * sx
            }
        }
    }

    /** [geluF] over [n] floats in place: the same bits from every kernel. */
    fun geluF(x: FloatArray, n: Int) {
        for (i in 0 until n) x[i] = geluF(x[i])
    }

    /** Each of [m] rows of [vocab] logits' [logSumF], into [out]. */
    fun logSumsF(logits: FloatArray, m: Int, vocab: Int, out: FloatArray) {
        for (v in 0 until m) out[v] = logSumF(logits, v * vocab, vocab)
    }

    /** Where the sessions run their pure computation; pinning changes the speed only, never the bits. */
    val pin: CorePin get() = CorePin.NONE

    companion object {
        val JVM = MatrixKernel { q, scales, rows, columns, x, y ->
            for (r in 0 until rows) y[r] = dotQ(q, scales, columns, x, r)
        }
    }
}

/** [x]'s [n] floats from [from] as int8 into [out], scaled by their largest magnitude; returns the scale back. */
fun quantize(x: FloatArray, from: Int, n: Int, out: ByteArray): Float {
    var max = 0f
    for (i in 0 until n) max = maxOf(max, Math.abs(x[from + i]))
    if (max == 0f) {
        java.util.Arrays.fill(out, 0, n, 0)
        return 0f
    }
    val inv = Q8 / max
    for (i in 0 until n) out[i] = Math.floor((x[from + i] * inv + 0.5f).toDouble()).toInt().coerceIn(-127, 127).toByte()
    return max / Q8
}

private const val Q8 = 127f

private const val LANES = 8

// eight sums, so the additions need not wait on each other
internal fun dotQ(q: ByteBuffer, scales: FloatArray, columns: Int, x: FloatArray, r: Int): Float {
    val base = r * columns
    var s0 = 0f; var s1 = 0f; var s2 = 0f; var s3 = 0f
    var s4 = 0f; var s5 = 0f; var s6 = 0f; var s7 = 0f
    var i = 0
    val end = columns - columns % LANES
    while (i < end) {
        val b = base + i
        s0 += x[i] * q[b]; s1 += x[i + 1] * q[b + 1]
        s2 += x[i + 2] * q[b + 2]; s3 += x[i + 3] * q[b + 3]
        s4 += x[i + 4] * q[b + 4]; s5 += x[i + 5] * q[b + 5]
        s6 += x[i + 6] * q[b + 6]; s7 += x[i + 7] * q[b + 7]
        i += LANES
    }
    var s = (s0 + s1) + (s2 + s3) + ((s4 + s5) + (s6 + s7))
    while (i < columns) {
        s += x[i] * q[base + i]
        i++
    }
    return s * scales[r]
}

internal fun dotF(w: FloatArray, columns: Int, x: FloatArray, r: Int): Float {
    val base = r * columns
    var s0 = 0f; var s1 = 0f; var s2 = 0f; var s3 = 0f
    var s4 = 0f; var s5 = 0f; var s6 = 0f; var s7 = 0f
    var i = 0
    val end = columns - columns % LANES
    while (i < end) {
        val b = base + i
        s0 += x[i] * w[b]; s1 += x[i + 1] * w[b + 1]
        s2 += x[i + 2] * w[b + 2]; s3 += x[i + 3] * w[b + 3]
        s4 += x[i + 4] * w[b + 4]; s5 += x[i + 5] * w[b + 5]
        s6 += x[i + 6] * w[b + 6]; s7 += x[i + 7] * w[b + 7]
        i += LANES
    }
    var s = (s0 + s1) + (s2 + s3) + ((s4 + s5) + (s6 + s7))
    while (i < columns) {
        s += x[i] * w[base + i]
        i++
    }
    return s
}

/**
 * e^x in floats, Cephes' expf: a fixed sequence of float operations (no fused multiply-add) that C++ repeats
 * exactly, so JVM and native agree to the bit. Within 2 ulp of e^x on [-87, 88].
 */
@Suppress("MagicNumber")
fun expF(x0: Float): Float {
    val x1 = x0.coerceIn(-87.3f, 88.3f)
    val fx = Math.floor((x1 * 1.442695f + 0.5f).toDouble()).toFloat()
    var x = x1 - fx * 0.6933594f
    x -= fx * -2.1219444e-4f
    val z = x * x
    var y = 1.9875691e-4f
    y = y * x + 1.3981999e-3f
    y = y * x + 8.333452e-3f
    y = y * x + 4.1665796e-2f
    y = y * x + 0.16666666f
    y = y * x + 0.5f
    y = y * z + x + 1f
    return y * Float.fromBits((fx.toInt() + 127) shl 23)
}

/** GELU with Abramowitz–Stegun 7.1.26's erf (within 1.5e-7) on [expF]. */
@Suppress("MagicNumber")
fun geluF(x: Float): Float {
    val z = Math.abs(x * 0.70710677f)
    val t = 1f / (1f + 0.3275911f * z)
    var p = 1.0614054f
    p = p * t + -1.4531521f
    p = p * t + 1.4214138f
    p = p * t + -0.28449672f
    p = p * t + 0.2548296f
    val e = 1f - p * t * expF(-(z * z))
    val erf = if (x >= 0f) e else -e
    return 0.5f * x * (1f + erf)
}

/**
 * ln x in floats, Cephes' logf: like [expF], a fixed sequence of float operations that C++ repeats exactly, as
 * libm's log may differ in the last bit between versions.
 */
@Suppress("MagicNumber", "ReturnCount")
fun logF(x0: Float): Float {
    if (x0.isNaN() || x0 < 0f) return Float.NaN
    if (x0 == 0f) return Float.NEGATIVE_INFINITY
    if (x0 == Float.POSITIVE_INFINITY) return x0
    var bits = x0.toRawBits()
    var e = -126
    if (bits < MIN_NORMAL_BITS) {
        bits = (x0 * 8388608f).toRawBits()
        e -= 23
    }
    e += bits ushr 23
    // the mantissa in [0.5, 1), as frexpf gives it
    var x = Float.fromBits((bits and 0x007fffff) or 0x3f000000)
    if (x < 0.70710677f) {
        e -= 1
        x = x + x - 1f
    } else {
        x -= 1f
    }
    val z = x * x
    var y = 0.070376836f
    y = y * x + -0.1151461f
    y = y * x + 0.116769984f
    y = y * x + -0.12420141f
    y = y * x + 0.14249323f
    y = y * x + -0.16668057f
    y = y * x + 0.20000714f
    y = y * x + -0.24999994f
    y = y * x + 0.3333333f
    y = y * x * z
    val fe = e.toFloat()
    y += -2.1219444e-4f * fe
    y += -0.5f * z
    x += y
    x += 0.6933594f * fe
    return x
}

private const val MIN_NORMAL_BITS = 0x00800000

/** log Σ e^l over [n] logits from [from]: max plus [logF] of the sum in doubles of [expF] terms. */
fun logSumF(logits: FloatArray, from: Int, n: Int): Float {
    var max = Float.NEGATIVE_INFINITY
    for (i in 0 until n) if (logits[from + i] > max) max = logits[from + i]
    var sum = 0.0
    for (i in 0 until n) sum += expF(logits[from + i] - max).toDouble()
    return max + logF(sum.toFloat())
}
