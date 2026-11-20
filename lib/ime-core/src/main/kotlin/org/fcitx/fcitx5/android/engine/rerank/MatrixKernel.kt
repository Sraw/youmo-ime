/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import java.nio.ByteBuffer

/**
 * `y[r] = scales[r] × (row r · x)` over int8 [q] row-major from index 0 (absolute gets); [x] and [y] must
 * differ. Nearly all sentence-model time is here; the app does it in C++, as ART runs [JVM] too slowly. The
 * app's [q] is the mapped model file, a direct buffer whose capacity must be exactly `rows × columns`. Results
 * may differ from [JVM]'s in the last bits (fused multiply-add): NativeMatrixKernelTest allows 1e-5.
 */
fun interface MatrixKernel {
    @Suppress("LongParameterList")
    fun times(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray)

    companion object {
        val JVM = MatrixKernel { q, scales, rows, columns, x, y ->
            for (r in 0 until rows) y[r] = dotQ(q, scales, columns, x, r)
        }
    }
}

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
