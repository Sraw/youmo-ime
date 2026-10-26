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
 * The C++ kernel the phone reranks with, against [MatrixKernel.JVM], which every JVM test and
 * lib/ime-eval use: a slip in its row or column tails would only rank worse on the phone.
 * CI runs it on an x86_64 emulator only (emulator_test.yml), so on the SSE build: the arm64 one the
 * phones have, NEON and fused multiply-add, is covered only when it is run on an arm64 device by hand
 * (`./gradlew :app:connectedTextDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.core.NativeMatrixKernelTest`).
 */
class NativeMatrixKernelTest {

    private companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            // as NativeMatrixKernel.loaded does; the kernel needs no running engine
            System.loadLibrary("youmo-matrix")
        }
    }

    private val random = Random(20261009)

    /**
     * [q] as the app hands it over (Safetensors.matrix): read-only, a slice of the mapped file that
     * starts [offset] bytes in, with more of the file after it. A kernel that read from where the
     * mapping starts would give wrong numbers, still finite.
     */
    private fun mapped(q: ByteArray, offset: Int): ByteBuffer {
        val file = ByteArray(offset + q.size + offset) { Byte.MAX_VALUE }
        q.copyInto(file, offset)
        val buffer = ByteBuffer.allocateDirect(file.size).put(file)
        // through Buffer: ByteBuffer.position(int) returning ByteBuffer is Java 9, not Android's
        (buffer as Buffer).position(offset)
        (buffer as Buffer).limit(offset + q.size)
        return buffer.slice().asReadOnlyBuffer()
    }

    /**
     * Both kernels on a random [rows] × [columns], the C++ one given the matrix in a direct buffer
     * of its own, or [mapped] at [offset]. Not to the bit: arm64 fuses each multiply-add,
     * which the JVM does not, so each result may move by a few roundings of the sum of its terms.
     */
    private fun assertMatches(rows: Int, columns: Int, offset: Int? = null) {
        val q = ByteArray(rows * columns).also { random.nextBytes(it) }
        val scales = FloatArray(rows) { (random.nextFloat() - 0.5f) / 16 }
        val x = FloatArray(columns) { random.nextGaussian().toFloat() }
        // NaN where the kernel writes nothing
        val expected = FloatArray(rows) { Float.NaN }
        val actual = FloatArray(rows) { Float.NaN }
        MatrixKernel.JVM.times(ByteBuffer.wrap(q), scales, rows, columns, x, expected)
        // a buffer of its own has its position left at the end: both kernels read from index 0
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
        // rows not a multiple of four, columns not of eight, fewer than eight, none at all
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
        // a heap buffer has no address, and says so rather than that sizes differ
        val heap = assertThrows(IllegalArgumentException::class.java) {
            NativeMatrixKernel.times(ByteBuffer.wrap(ByteArray(12)), FloatArray(3), 3, 4, FloatArray(4), FloatArray(3))
        }
        assertEquals("q is not a direct buffer", heap.message)
        // longer x, scales and y are not refused: only what is read or written past is
        val y = FloatArray(4)
        NativeMatrixKernel.times(ByteBuffer.allocateDirect(12).put(ByteArray(12) { 1 }), FloatArray(4) { 1f }, 3, 4, FloatArray(5) { 1f }, y)
        assertEquals(listOf(4f, 4f, 4f, 0f), y.toList())
    }
}
