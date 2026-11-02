/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random

class MatrixTest {

    private val random = Random(3)

    private class Weights(val rows: Int, val columns: Int, val q: ByteArray, val scales: FloatArray) {
        // what the int8 weights stand for: whole numbers over 128, exact as floats
        val floats = FloatArray(q.size) { q[it] * scales[it / columns] }

        /** `x · row r` by the textbook, in doubles. */
        fun dot(x: FloatArray, r: Int) = (0 until columns).sumOf { x[it].toDouble() * floats[r * columns + it] }.toFloat()
    }

    private fun weights(rows: Int, columns: Int) =
        Weights(rows, columns, ByteArray(rows * columns) { (random.nextInt(255) - 127).toByte() }, FloatArray(rows) { (it + 1) / 128f })

    @Test
    fun everyColumnCountsWhateverTheWidth() {
        // short of, at and past the eight sums the kernels keep
        for (columns in listOf(1, 7, 8, 12, 17)) {
            val w = weights(3, columns)
            val x = FloatArray(columns) { random.nextGaussian().toFloat() }
            val bias = FloatArray(w.rows) { it.toFloat() }
            val quantized = Matrix.quantized(w.rows, columns, ByteBuffer.wrap(w.q), w.scales)
            for ((form, m) in listOf("int8" to quantized, "floats" to Matrix.of(w.rows, columns, w.floats), "unpacked" to quantized.unpacked())) {
                val y = FloatArray(w.rows)
                m.times(x, y)
                val applied = FloatArray(w.rows)
                m.apply(x, bias, applied)
                val row = FloatArray(columns)
                for (r in 0 until w.rows) {
                    val at = "$form, $columns wide, row $r"
                    assertEquals(at, w.dot(x, r), m.dot(x, r), 1e-3f)
                    assertEquals(at, w.dot(x, r), y[r], 1e-3f)
                    assertEquals(at, w.dot(x, r) + bias[r], applied[r], 1e-3f)
                    m.row(r, row)
                    assertArrayEquals(at, w.floats.copyOfRange(r * columns, (r + 1) * columns), row, 0f)
                }
            }
        }
    }

    @Test
    fun floatsGiveRowMajorDotsToTheBit() {
        // a large table and small ones, columns short of a multiple of the eight sums
        for ((rows, columns) in listOf(3 to 12, 1100 to 17, 600 to 192)) {
            val f = FloatArray(rows * columns) { random.nextGaussian().toFloat() }
            val x = FloatArray(columns) { random.nextGaussian().toFloat() }
            val m = Matrix.of(rows, columns, f)
            val y = FloatArray(rows)
            m.times(x, y)
            for (r in 0 until rows) {
                val expected = dotF(f, columns, x, r)
                assertEquals("$rows × $columns, row $r", expected.toRawBits(), y[r].toRawBits())
                assertEquals("$rows × $columns, row $r", expected.toRawBits(), m.dot(x, r).toRawBits())
            }
        }
    }

    @Test
    fun int8IsMultipliedByItsKernelAndFloatsAreAlreadyUnpacked() {
        val w = weights(2, 5)
        val asked = ArrayList<Pair<Int, Int>>()
        val kernel = MatrixKernel { q, scales, rows, columns, x, y -> asked += rows to columns; MatrixKernel.JVM.times(q, scales, rows, columns, x, y) }
        val y = FloatArray(2)
        Matrix.quantized(2, 5, ByteBuffer.wrap(w.q), w.scales, kernel).times(FloatArray(5) { 1f }, y)
        assertEquals(listOf(2 to 5), asked)
        // unpacked, it is floats: the kernel is not asked again
        Matrix.quantized(2, 5, ByteBuffer.wrap(w.q), w.scales, kernel).unpacked().times(FloatArray(5) { 1f }, y)
        assertEquals(listOf(2 to 5), asked)
        val floats = Matrix.of(2, 5, w.floats)
        assertSame(floats, floats.unpacked())
    }
}
