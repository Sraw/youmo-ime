/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import java.nio.ByteBuffer

/**
 * A row-major weight matrix: int8 with per-row scale as stored (a quarter of floats' memory), multiplied by a
 * [MatrixKernel] on int8-rounded vectors ([MatrixKernel.timesManyQ8]); or floats.
 */
internal sealed class Matrix(val rows: Int, val columns: Int) {

    abstract fun dot(x: FloatArray, r: Int): Float

    abstract fun times(x: FloatArray, y: FloatArray)

    /** [m] vectors laid one after another in [x], into [y] likewise: each as [times] gives it. */
    open fun timesMany(x: FloatArray, m: Int, y: FloatArray) {
        val xi = FloatArray(columns)
        val yi = FloatArray(rows)
        for (v in 0 until m) {
            System.arraycopy(x, v * columns, xi, 0, columns)
            times(xi, yi)
            System.arraycopy(yi, 0, y, v * rows, rows)
        }
    }

    fun applyMany(x: FloatArray, m: Int, bias: FloatArray, y: FloatArray) {
        timesMany(x, m, y)
        for (v in 0 until m) for (o in 0 until rows) y[v * rows + o] += bias[o]
    }

    abstract fun row(r: Int, out: FloatArray)

    fun apply(x: FloatArray, bias: FloatArray, y: FloatArray) {
        times(x, y)
        for (o in 0 until rows) y[o] += bias[o]
    }

    /** As floats: four times the memory, no per-use conversion. */
    abstract fun unpacked(): Matrix

    private class Quantized(
        rows: Int,
        columns: Int,
        private val q: ByteBuffer,
        private val scales: FloatArray,
        private val kernel: MatrixKernel,
    ) : Matrix(rows, columns) {

        // per thread (ime-eval scores on several threads with one model)
        private val xq = ThreadLocal.withInitial { ByteArray(columns) }

        // the same sum as the kernel's row r, so a logit read alone matches the one in its softmax
        override fun dot(x: FloatArray, r: Int): Float {
            val xq = xq.get()
            val sx = quantize(x, 0, columns, xq)
            val base = r * columns
            var s = 0
            for (i in 0 until columns) s += q[base + i] * xq[i]
            return s.toFloat() * scales[r] * sx
        }

        override fun times(x: FloatArray, y: FloatArray) {
            kernel.timesManyQ8(q, scales, rows, columns, x, 1, y)
        }

        override fun timesMany(x: FloatArray, m: Int, y: FloatArray) {
            kernel.timesManyQ8(q, scales, rows, columns, x, m, y)
        }

        override fun row(r: Int, out: FloatArray) {
            val base = r * columns
            val scale = scales[r]
            for (i in 0 until columns) out[i] = q[base + i] * scale
        }

        override fun unpacked(): Matrix {
            val out = FloatArray(rows * columns)
            val row = FloatArray(columns)
            for (r in 0 until rows) {
                row(r, row)
                System.arraycopy(row, 0, out, r * columns, columns)
            }
            return Floats(rows, columns, out)
        }
    }

    /**
     * Stored column-major so [times] adds a column at a time into all rows' sums, which the JIT vectorises (a row
     * at a time is one float chain it may not reorder). Separate arrays, since a loop over two parts of one array
     * may overlap and is not vectorised. Each row still sums in [dotF]'s order, so results match it bit for bit.
     */
    private class Floats(rows: Int, columns: Int, f: FloatArray) : Matrix(rows, columns) {
        private val cols = Array(columns) { c -> FloatArray(rows) { r -> f[r * columns + c] } }

        // per thread (ime-eval scores on several threads with one model)
        private val sums = ThreadLocal.withInitial { Array(LANES) { FloatArray(rows) } }

        override fun dot(x: FloatArray, r: Int): Float {
            var s0 = 0f; var s1 = 0f; var s2 = 0f; var s3 = 0f
            var s4 = 0f; var s5 = 0f; var s6 = 0f; var s7 = 0f
            var i = 0
            val end = columns - columns % LANES
            while (i < end) {
                s0 += x[i] * cols[i][r]; s1 += x[i + 1] * cols[i + 1][r]
                s2 += x[i + 2] * cols[i + 2][r]; s3 += x[i + 3] * cols[i + 3][r]
                s4 += x[i + 4] * cols[i + 4][r]; s5 += x[i + 5] * cols[i + 5][r]
                s6 += x[i + 6] * cols[i + 6][r]; s7 += x[i + 7] * cols[i + 7][r]
                i += LANES
            }
            var s = (s0 + s1) + (s2 + s3) + ((s4 + s5) + (s6 + s7))
            while (i < columns) {
                s += x[i] * cols[i][r]
                i++
            }
            return s
        }

        override fun times(x: FloatArray, y: FloatArray) {
            val sums = sums.get()
            // a local bound: the JIT vectorises only over one it knows is fixed
            val n = rows
            for (lane in sums) java.util.Arrays.fill(lane, 0f)
            val end = columns - columns % LANES
            for (c in 0 until end) {
                val lane = sums[c % LANES]
                val xc = x[c]
                val col = cols[c]
                for (k in 0 until n) lane[k] += xc * col[k]
            }
            val s0 = sums[0]; val s1 = sums[1]; val s2 = sums[2]; val s3 = sums[3]
            val s4 = sums[4]; val s5 = sums[5]; val s6 = sums[6]; val s7 = sums[7]
            for (k in 0 until n) y[k] = (s0[k] + s1[k]) + (s2[k] + s3[k]) + ((s4[k] + s5[k]) + (s6[k] + s7[k]))
            for (c in end until columns) {
                val xc = x[c]
                val col = cols[c]
                for (k in 0 until n) y[k] += xc * col[k]
            }
        }

        override fun row(r: Int, out: FloatArray) {
            for (c in 0 until columns) out[c] = cols[c][r]
        }

        override fun unpacked(): Matrix = this
    }

    companion object {
        private const val LANES = 8

        fun of(rows: Int, columns: Int, f: FloatArray): Matrix = Floats(rows, columns, f)

        fun quantized(rows: Int, columns: Int, q: ByteBuffer, scales: FloatArray, kernel: MatrixKernel = MatrixKernel.JVM): Matrix =
            Quantized(rows, columns, q, scales, kernel)
    }
}
