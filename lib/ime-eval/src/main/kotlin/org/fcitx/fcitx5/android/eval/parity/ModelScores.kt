/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval.parity

import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import org.fcitx.fcitx5.android.engine.rerank.SentenceModel
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.math.abs

/**
 * A sentence model's score difference of two candidates after a context, as [Comparator] weighs a
 * swap: `logP(a | context) - logP(b | context)`, the model's weights multiplied by [kernel].
 */
class ModelScores(file: File, kernel: MatrixKernel = MatrixKernel.JVM) {
    private val model = RandomAccessFile(file, "r").use {
        SentenceModel.load(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()), unpack = false, kernel = kernel)
    }

    fun delta(context: String, a: String, b: String): Float? {
        val s = model.Scorer().score(context, listOf(a, b))
        return (s[0] - s[1]).takeIf { it.isFinite() }
    }

    companion object {
        /**
         * The app's arm64 kernel (`matrix-kernel.cpp`): MatrixKernel.JVM's order, each multiply-add
         * fused, which is all that moves its last bits from the JVM's (x86_64's SSE has no fused
         * multiply-add: the redroid's kernel adds as the JVM's does, bit for bit).
         */
        val FUSED = MatrixKernel { q, scales, rows, columns, x, y ->
            for (r in 0 until rows) {
                val base = r * columns
                val s = FloatArray(LANES)
                var i = 0
                val end = columns - columns % LANES
                while (i < end) {
                    for (l in 0 until LANES) s[l] = Math.fma(x[i + l], q[base + i + l].toFloat(), s[l])
                    i += LANES
                }
                var sum = ((s[0] + s[1]) + (s[2] + s[3])) + ((s[4] + s[5]) + (s[6] + s[7]))
                while (i < columns) {
                    sum = Math.fma(x[i], q[base + i].toFloat(), sum)
                    i++
                }
                y[r] = sum * scales[r]
            }
        }

        // the sum below adds s[0..7] by hand, in the arm64 kernel's order: change both together
        private const val LANES = 8

        /** [measure]'s result: the largest change, the 99.9th percentile, how many pairs were weighed. */
        data class Shifts(val max: Float, val p999: Float, val pairs: Int)

        /**
         * How far the score differences of neighbouring candidates in [outputs] move between the
         * JVM's kernel and the arm64 one ([FUSED]): the largest change, the 99.9th percentile and how
         * many pairs were weighed. ε is set above the largest, so a swap from the kernel is allowed.
         */
        fun measure(model: File, outputs: List<Output>, pairsPerState: Int = 3, limit: Int = Int.MAX_VALUE): Shifts {
            val jvm = ModelScores(model)
            val fused = ModelScores(model, FUSED)
            val shifts = ArrayList<Float>()
            outputs@ for (o in outputs) {
                for (st in o.states) {
                    if (shifts.size >= limit) break@outputs
                    val context = st.text.substring(0, st.sel.first.coerceIn(0, st.text.length))
                    st.cands.take(pairsPerState + 1).zipWithNext().forEach { (a, b) ->
                        val x = jvm.delta(context, a, b)
                        val y = fused.delta(context, a, b)
                        if (x != null && y != null) shifts += abs(x - y)
                    }
                }
            }
            if (shifts.isEmpty()) return Shifts(0f, 0f, 0)
            shifts.sort()
            return Shifts(shifts.last(), shifts[((shifts.size - 1) * PERCENTILE).toInt()], shifts.size)
        }

        private const val PERCENTILE = 0.999
    }
}
