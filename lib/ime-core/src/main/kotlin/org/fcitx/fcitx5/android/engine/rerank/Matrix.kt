/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

/**
 * A weight matrix of [rows] × [columns], row-major: int8 with a scale per row as the model file
 * stores it (a quarter of the memory of floats), multiplied by a [MatrixKernel], or floats.
 */
internal class Matrix private constructor(
    val rows: Int,
    val columns: Int,
    private val q: ByteArray?,
    private val scales: FloatArray?,
    private val kernel: MatrixKernel?,
    private val f: FloatArray?,
) {

    /** `x · row r`. */
    fun dot(x: FloatArray, r: Int): Float = if (q != null) dotQ(q, scales!!, columns, x, r) else dotF(f!!, columns, x, r)

    /** `y[r] = x · row r` for every row. */
    fun times(x: FloatArray, y: FloatArray) {
        if (q != null) kernel!!.times(q, scales!!, rows, columns, x, y) else for (r in 0 until rows) y[r] = dotF(f!!, columns, x, r)
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
        times(x, y)
        for (o in 0 until rows) y[o] += bias[o]
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
        fun of(rows: Int, columns: Int, f: FloatArray) = Matrix(rows, columns, null, null, null, f)

        fun quantized(rows: Int, columns: Int, q: ByteArray, scales: FloatArray, kernel: MatrixKernel = MatrixKernel.JVM) =
            Matrix(rows, columns, q, scales, kernel, null)
    }
}
