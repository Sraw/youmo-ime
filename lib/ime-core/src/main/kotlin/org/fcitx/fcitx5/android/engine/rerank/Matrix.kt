/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

/**
 * A weight matrix of [rows] × [columns], row-major: int8 with a scale per row as the model file
 * stores it (a quarter of the memory of floats), or floats.
 */
internal class Matrix private constructor(
    val rows: Int,
    val columns: Int,
    private val q: ByteArray?,
    private val scales: FloatArray?,
    private val f: FloatArray?,
) {

    /** `x[from until from + columns] · row r`. */
    fun dot(x: FloatArray, from: Int, r: Int): Float = if (q != null) dotQ(x, from, r) else dotF(x, from, r)

    // eight sums, so the additions need not wait on each other
    private fun dotQ(x: FloatArray, from: Int, r: Int): Float {
        val q = q!!
        val base = r * columns
        var s0 = 0f; var s1 = 0f; var s2 = 0f; var s3 = 0f
        var s4 = 0f; var s5 = 0f; var s6 = 0f; var s7 = 0f
        var i = 0
        val end = columns - columns % LANES
        while (i < end) {
            val b = base + i
            val a = from + i
            s0 += x[a] * q[b]; s1 += x[a + 1] * q[b + 1]
            s2 += x[a + 2] * q[b + 2]; s3 += x[a + 3] * q[b + 3]
            s4 += x[a + 4] * q[b + 4]; s5 += x[a + 5] * q[b + 5]
            s6 += x[a + 6] * q[b + 6]; s7 += x[a + 7] * q[b + 7]
            i += LANES
        }
        var s = (s0 + s1) + (s2 + s3) + ((s4 + s5) + (s6 + s7))
        while (i < columns) {
            s += x[from + i] * q[base + i]
            i++
        }
        return s * scales!![r]
    }

    private fun dotF(x: FloatArray, from: Int, r: Int): Float {
        val w = f!!
        val base = r * columns
        var s0 = 0f; var s1 = 0f; var s2 = 0f; var s3 = 0f
        var s4 = 0f; var s5 = 0f; var s6 = 0f; var s7 = 0f
        var i = 0
        val end = columns - columns % LANES
        while (i < end) {
            val b = base + i
            val a = from + i
            s0 += x[a] * w[b]; s1 += x[a + 1] * w[b + 1]
            s2 += x[a + 2] * w[b + 2]; s3 += x[a + 3] * w[b + 3]
            s4 += x[a + 4] * w[b + 4]; s5 += x[a + 5] * w[b + 5]
            s6 += x[a + 6] * w[b + 6]; s7 += x[a + 7] * w[b + 7]
            i += LANES
        }
        var s = (s0 + s1) + (s2 + s3) + ((s4 + s5) + (s6 + s7))
        while (i < columns) {
            s += x[from + i] * w[base + i]
            i++
        }
        return s
    }

    /** Row [r] as floats, into [out]. */
    fun row(r: Int, out: FloatArray) {
        val base = r * columns
        if (q != null) {
            val scale = scales!![r]
            for (i in 0 until columns) out[i] = q[base + i] * scale
        } else {
            System.arraycopy(f!!, base, out, 0, columns)
        }
    }

    /** `y[o] = x · row o + bias[o]` for every row. */
    fun apply(x: FloatArray, bias: FloatArray, y: FloatArray) {
        for (o in 0 until rows) y[o] = dot(x, 0, o) + bias[o]
    }

    /** The same weights as floats: four times the memory, no conversion per use. */
    fun unpacked(): Matrix {
        if (q == null) return this
        val out = FloatArray(rows * columns)
        val row = FloatArray(columns)
        for (r in 0 until rows) {
            row(r, row)
            System.arraycopy(row, 0, out, r * columns, columns)
        }
        return of(rows, columns, out)
    }

    companion object {
        private const val LANES = 8

        fun of(rows: Int, columns: Int, f: FloatArray) = Matrix(rows, columns, null, null, f)

        fun quantized(rows: Int, columns: Int, q: ByteArray, scales: FloatArray) = Matrix(rows, columns, q, scales, null)
    }
}
