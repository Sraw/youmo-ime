/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.abs

/**
 * The phone's C++ rerank kernel against [MatrixKernel.JVM]. CI's x86_64 emulator covers only the SSE build;
 * the arm64 NEON one needs a run on an arm64 device by hand.
 */
class NativeMatrixKernelTest {

    private companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            System.loadLibrary("youmo-matrix")
        }
    }

    private val random = Random(20261009)

    /** [q] as Safetensors.matrix hands it: a read-only slice starting [offset] bytes into a longer mapping. */
    private fun mapped(q: ByteArray, offset: Int): ByteBuffer {
        val file = ByteArray(offset + q.size + offset) { Byte.MAX_VALUE }
        q.copyInto(file, offset)
        val buffer = ByteBuffer.allocateDirect(file.size).put(file)
        // via Buffer: ByteBuffer.position(int) returning ByteBuffer is Java 9, not Android's
        (buffer as Buffer).position(offset)
        (buffer as Buffer).limit(offset + q.size)
        return buffer.slice().asReadOnlyBuffer()
    }

    /** Not to the bit: arm64 fuses each multiply-add, the JVM does not. */
    private fun assertMatches(rows: Int, columns: Int, offset: Int? = null) {
        val q = ByteArray(rows * columns).also { random.nextBytes(it) }
        val scales = FloatArray(rows) { (random.nextFloat() - 0.5f) / 16 }
        val x = FloatArray(columns) { random.nextGaussian().toFloat() }
        val expected = FloatArray(rows) { Float.NaN }
        val actual = FloatArray(rows) { Float.NaN }
        MatrixKernel.JVM.times(ByteBuffer.wrap(q), scales, rows, columns, x, expected)
        // a buffer of its own has its position at the end: both kernels read from index 0
        val buffer = if (offset == null) ByteBuffer.allocateDirect(q.size).put(q) else mapped(q, offset)
        NativeMatrixKernel.times(buffer, scales, rows, columns, x, actual)
        for (r in 0 until rows) {
            var terms = 0.0
            for (c in 0 until columns) terms += abs(x[c] * q[r * columns + c].toDouble())
            val tolerance = 1e-5 * terms * abs(scales[r])
            val difference = abs(expected[r].toDouble() - actual[r].toDouble())
            assertTrue("$rows × $columns, row $r: ${expected[r]} against ${actual[r]}", difference <= tolerance)
        }
    }

    @Test
    fun rowsAndColumnsLeftOverAreMultipliedAsTheJvmDoes() {
        for ((rows, columns) in listOf(7 to 13, 4 to 8, 9 to 1027, 0 to 0, 1 to 1, 5 to 3, 3 to 16, 2 to 7)) {
            assertMatches(rows, columns)
        }
    }

    @Test
    fun anySizeIsMultipliedAsTheJvmDoes() {
        repeat(50) { assertMatches(random.nextInt(40), random.nextInt(300)) }
    }

    @Test
    fun aMatrixPartWayIntoAMappedFileIsReadFromWhereItStarts() {
        // a tensor lies wherever the file's header leaves it, at no multiple of four
        for ((rows, columns) in listOf(7 to 13, 4 to 8, 9 to 1027, 1 to 1)) assertMatches(rows, columns, offset = 37)
    }

    @Test
    fun sizesThatDifferAreRefused() {
        @Suppress("LongParameterList")
        fun refused(q: Int, scales: Int, rows: Int, columns: Int, x: Int, y: Int) {
            assertThrows(IllegalArgumentException::class.java) {
                NativeMatrixKernel.times(ByteBuffer.allocateDirect(q), FloatArray(scales), rows, columns, FloatArray(x), FloatArray(y))
            }
        }
        refused(q = 11, scales = 3, rows = 3, columns = 4, x = 4, y = 3)
        refused(q = 13, scales = 3, rows = 3, columns = 4, x = 4, y = 3)
        refused(q = 12, scales = 2, rows = 3, columns = 4, x = 4, y = 3)
        refused(q = 12, scales = 3, rows = 3, columns = 4, x = 3, y = 3)
        refused(q = 12, scales = 3, rows = 3, columns = 4, x = 4, y = 2)
        refused(q = 0, scales = 0, rows = -1, columns = 0, x = 0, y = 0)
        refused(q = 0, scales = 1, rows = 1, columns = -1, x = 0, y = 1)
        val heap = assertThrows(IllegalArgumentException::class.java) {
            NativeMatrixKernel.times(ByteBuffer.wrap(ByteArray(12)), FloatArray(3), 3, 4, FloatArray(4), FloatArray(3))
        }
        assertEquals("q is not a direct buffer", heap.message)
        val y = FloatArray(4)
        NativeMatrixKernel.times(ByteBuffer.allocateDirect(12).put(ByteArray(12) { 1 }), FloatArray(4) { 1f }, 3, 4, FloatArray(5) { 1f }, y)
        assertEquals(listOf(4f, 4f, 4f, 0f), y.toList())
    }
}
