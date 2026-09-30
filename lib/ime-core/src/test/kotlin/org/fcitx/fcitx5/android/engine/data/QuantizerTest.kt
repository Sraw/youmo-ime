/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

class QuantizerTest {

    private fun decode(q: Quantizer) = Quantizer.decodeTable(ByteBuffer.wrap(q.codebook()).order(ByteOrder.LITTLE_ENDIAN))

    @Test
    fun manyValuesDecodeClosely() {
        val random = Random(7)
        val values = FloatArray(100_000) { -8f * random.nextFloat() }
        val q = Quantizer.build(values, exactZero = false)
        val table = decode(q)
        val worst = values.maxOf { abs(table[q.encode(it)] - it) }
        // 256 equal-count bins over a spread of 8: each is ~0.03 wide
        assertTrue("worst error $worst", worst < 0.05f)
    }

    @Test
    fun repeatedValuesDecodeExactlyWhenThereAreFewerDistinctOnesThanLevels() {
        // like ARPA output: many values, few distinct ones, the commonest far more common
        val random = Random(3)
        val levels = FloatArray(200) { -it / 10f }
        val values = FloatArray(50_000) { levels[minOf(199, (random.nextDouble() * random.nextDouble() * 200).toInt())] }
        for (exactZero in listOf(false, true)) {
            val q = Quantizer.build(values, exactZero)
            val table = decode(q)
            values.forEach { assertEquals(it, table[q.encode(it)], 0f) }
        }
    }

    @Test
    fun aRunOfEqualValuesIsNeverSplitAcrossBins() {
        // half of all values are -0.5: with bins by position they would fill ~128 bins, and the
        // first of those would also hold smaller values and decode -0.5 to something else
        val random = Random(5)
        val values = FloatArray(100_000) { if (it % 2 == 0) -0.5f else -8f * random.nextFloat() }
        val q = Quantizer.build(values, exactZero = false)
        // a few near neighbours may share its bin, but they cannot pull it far
        assertEquals(-0.5f, decode(q)[q.encode(-0.5f)], 1e-4f)
    }

    @Test
    fun zeroStaysExactlyZeroWhenReserved() {
        val values = floatArrayOf(0f, -0.5f, 0f, -1.25f, -0.01f)
        val q = Quantizer.build(values, exactZero = true)
        assertEquals(0, q.encode(0f))
        assertEquals(0f, decode(q)[0], 0f)
        // a value close to zero must not collapse onto it
        assertTrue(q.encode(-0.01f) != 0)
    }

    @Test
    fun fewerValuesThanLevelsDecodeExactly() {
        val values = floatArrayOf(-3f, -1f, -2f, -1f)
        val q = Quantizer.build(values, exactZero = false)
        val table = decode(q)
        values.forEach { assertEquals(it, table[q.encode(it)], 0f) }
    }

    @Test
    fun valuesOutsideTheBuiltRangeClampToTheEnds() {
        val q = Quantizer.build(FloatArray(1000) { -it / 100f }, exactZero = false)
        val table = decode(q)
        assertEquals(table[255], table[q.encode(5f)], 0f)
        assertEquals(table[0], table[q.encode(-100f)], 0f)
    }

    @Test
    fun encodingIsMonotonic() {
        val q = Quantizer.build(FloatArray(5000) { -it / 500f }, exactZero = false)
        val codes = (0 until 5000).map { q.encode(-it / 500f) }
        assertEquals(codes.sortedDescending(), codes)
    }

    @Test(expected = DataFormatException::class)
    fun aCodebookOfTheWrongSizeIsRejected() {
        Quantizer.decodeTable(ByteBuffer.allocate(100))
    }

    @Test(expected = DataFormatException::class)
    fun aCodebookHoldingNaNIsRejected() {
        val bytes = Quantizer.build(floatArrayOf(-1f, -2f), exactZero = false).codebook()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(4, Float.NaN)
        Quantizer.decodeTable(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
    }
}
