/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import org.fcitx.fcitx5.android.device.CorePin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random

class MatrixKernelTest {

    private val random = Random(5)

    private fun weights(rows: Int, columns: Int) = ByteBuffer.wrap(ByteArray(rows * columns) { (random.nextInt(255) - 127).toByte() })

    private fun bits(a: FloatArray) = IntArray(a.size) { a[it].toRawBits() }

    @Test
    fun timesManyIsTimesOnEachVectorToTheBit() {
        val (rows, columns, m) = Triple(9, 21, 4)
        val q = weights(rows, columns)
        val scales = FloatArray(rows) { (it + 1) / 128f }
        val x = FloatArray(m * columns) { random.nextGaussian().toFloat() }
        val many = FloatArray(m * rows)
        MatrixKernel.JVM.timesMany(q, scales, rows, columns, x, m, many)
        for (v in 0 until m) {
            val one = FloatArray(rows)
            MatrixKernel.JVM.times(q, scales, rows, columns, x.copyOfRange(v * columns, (v + 1) * columns), one)
            assertArrayEquals("vector $v", bits(one), bits(many.copyOfRange(v * rows, (v + 1) * rows)))
        }
    }

    @Test
    fun quantizeMapsTheLargestMagnitudeTo127() {
        val x = floatArrayOf(9f, 0.5f, -2f, -0.25f, 1f, 4f)
        val out = ByteArray(4)
        val scale = quantize(x, 1, 4, out)
        assertEquals(2f / 127, scale, 0f)
        assertArrayEquals(byteArrayOf(32, -127, -16, 64), out)

        val zeros = byteArrayOf(5, 5)
        assertEquals(0f, quantize(FloatArray(3), 1, 2, zeros), 0f)
        assertArrayEquals(ByteArray(2), zeros)
    }

    @Test
    fun timesManyQ8SumsQuantizedVectorsExactlyWhateverTheBatch() {
        val (rows, columns, m) = Triple(7, 33, 3)
        val q = weights(rows, columns)
        val scales = FloatArray(rows) { (it + 1) / 128f }
        val x = FloatArray(m * columns) { random.nextGaussian().toFloat() }
        val many = FloatArray(m * rows)
        MatrixKernel.JVM.timesManyQ8(q, scales, rows, columns, x, m, many)
        for (v in 0 until m) {
            val xq = ByteArray(columns)
            val sx = quantize(x, v * columns, columns, xq)
            val alone = FloatArray(rows)
            MatrixKernel.JVM.timesManyQ8(q, scales, rows, columns, x.copyOfRange(v * columns, (v + 1) * columns), 1, alone)
            val floats = FloatArray(rows)
            MatrixKernel.JVM.times(q, scales, rows, columns, x.copyOfRange(v * columns, (v + 1) * columns), floats)
            for (r in 0 until rows) {
                val dot = (0 until columns).sumOf { q[r * columns + it] * xq[it].toInt() }
                assertEquals("vector $v row $r", (dot.toFloat() * scales[r] * sx).toRawBits(), many[v * rows + r].toRawBits())
                assertEquals("vector $v row $r alone", many[v * rows + r].toRawBits(), alone[r].toRawBits())
                // rounding each of the columns by at most half a step of sx
                assertEquals("vector $v row $r", floats[r], many[v * rows + r], columns * 127 * scales[r] * sx / 2)
            }
        }
    }

    @Test
    fun expFIsWithinTwoUlpOfExp() {
        assertEquals(1f, expF(0f), 0f)
        repeat(100_000) {
            val x = random.nextFloat() * 174f - 86f
            val exact = Math.exp(x.toDouble())
            assertEquals("$x", exact, expF(x).toDouble(), 2 * Math.ulp(exact.toFloat()).toDouble())
        }
    }

    @Test
    fun logFIsWithinTwoUlpOfLog() {
        val samples = List(100_000) { Float.fromBits(random.nextInt(0x7f800000)) } +
            listOf(Float.MIN_VALUE, 1e-40f, java.lang.Float.MIN_NORMAL, 0.5f, 0.70710677f, 0.7071068f, 2f, 3e3f, Float.MAX_VALUE)
        for (x in samples) {
            val exact = Math.log(x.toDouble())
            assertEquals("$x", exact, logF(x).toDouble(), 2 * Math.ulp(exact.toFloat()).toDouble())
        }
        assertEquals(0f, logF(1f), 0f)
        assertEquals(Float.NEGATIVE_INFINITY, logF(0f), 0f)
        assertEquals(Float.NEGATIVE_INFINITY, logF(-0f), 0f)
        assertEquals(Float.POSITIVE_INFINITY, logF(Float.POSITIVE_INFINITY), 0f)
        assertTrue(logF(-1f).isNaN())
        assertTrue(logF(Float.NaN).isNaN())
    }

    @Test
    fun geluFIsWithinAMillionthOfTheExactGelu() {
        val xs = FloatArray(20_001) { -4f + it * 4e-4f }
        val gelus = xs.copyOf()
        MatrixKernel.JVM.geluF(gelus, xs.size)
        for (i in xs.indices) {
            val x = xs[i].toDouble()
            assertEquals("$x", 0.5 * x * (1 + erf(x / Math.sqrt(2.0))), gelus[i].toDouble(), 1e-6)
        }
    }

    // the Maclaurin series, in doubles exact enough for |z| < 3
    private fun erf(z: Double): Double {
        var term = z
        var sum = z
        var n = 0
        while (Math.abs(term) > 1e-17) {
            n++
            term *= -z * z / n
            sum += term / (2 * n + 1)
        }
        return 2 / Math.sqrt(Math.PI) * sum
    }

    @Test
    fun logSumsFIsEachRowsLogSumExp() {
        val (m, vocab) = 3 to 500
        val logits = FloatArray(m * vocab) { random.nextGaussian().toFloat() * 6 }
        val sums = FloatArray(m)
        MatrixKernel.JVM.logSumsF(logits, m, vocab, sums)
        for (v in 0 until m) {
            val row = (0 until vocab).map { logits[v * vocab + it].toDouble() }
            val max = row.max()
            val exact = max + Math.log(row.sumOf { Math.exp(it - max) })
            assertEquals("row $v", logSumF(logits, v * vocab, vocab).toRawBits(), sums[v].toRawBits())
            assertEquals("row $v", exact, sums[v].toDouble(), 1e-5)
        }
    }

    @Test
    fun theJvmKernelRunsInPlace() {
        assertSame(CorePin.NONE, MatrixKernel.JVM.pin)
        var runs = 0
        assertEquals(7, CorePin.NONE.run { runs++; 7 })
        assertEquals(1, runs)
    }
}
