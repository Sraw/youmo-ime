/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class BitPackedTest {

    private fun read(bytes: ByteArray) = BitPacked(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))

    @Test
    fun valuesOfEveryWidthSurviveARoundTripIncludingAcrossWordBoundaries() {
        val random = Random(42)
        for (width in 1..BitPacked.MAX_WIDTH) {
            val max = if (width == 31) Int.MAX_VALUE else (1 shl width) - 1
            // 67 values: the width never divides 64 evenly for long, so some straddle two words
            val values = IntArray(67) { if (it == 0) max else random.nextInt(max) }
            val packed = read(BitPacked.encode(values))
            assertEquals("width", width, packed.width)
            assertEquals(values.toList(), (0 until packed.size).map { packed[it] })
        }
    }

    @Test
    fun theWidthIsTheSmallestThatHoldsTheLargestValue() {
        assertEquals(1, BitPacked.widthFor(0))
        assertEquals(1, BitPacked.widthFor(1))
        assertEquals(2, BitPacked.widthFor(2))
        assertEquals(19, BitPacked.widthFor(270_713))
        assertEquals(31, BitPacked.widthFor(Int.MAX_VALUE))
    }

    @Test
    fun anEmptyArrayIsValid() {
        assertEquals(0, read(BitPacked.encode(IntArray(0))).size)
    }

    @Test
    fun lowerBoundFindsTheFirstValueNotBelowTheKeyWithinARange() {
        val packed = read(BitPacked.encode(intArrayOf(9, 1, 3, 3, 7, 0)))
        assertEquals(2, packed.lowerBound(1, 5, 3))
        assertEquals(4, packed.lowerBound(1, 5, 4))
        assertEquals(5, packed.lowerBound(1, 5, 8))
        assertEquals(1, packed.lowerBound(1, 5, 0))
        assertEquals(3, packed.lowerBound(3, 3, 1))
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun readingPastTheEndFails() {
        read(BitPacked.encode(intArrayOf(1, 2)))[2]
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTruncatedBufferIsRejected() {
        val bytes = BitPacked.encode(IntArray(100) { it })
        read(bytes.copyOf(bytes.size - 16))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNegativeSizeIsRejected() {
        val bytes = BitPacked.encode(intArrayOf(1, 2))
        bytes[7] = 0x80.toByte()
        read(bytes)
    }

    @Test
    fun thereIsNoPaddingAfterTheLastWord() {
        // 3 values of 31 bits = 93 bits = 2 words, the last value straddling both
        assertEquals(8 + 2 * 8, BitPacked.encode(intArrayOf(1, 2, Int.MAX_VALUE)).size)
        assertEquals(Int.MAX_VALUE, read(BitPacked.encode(intArrayOf(1, 2, Int.MAX_VALUE)))[2])
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeValuesCannotBeStored() {
        BitPacked.encode(intArrayOf(1, -1))
    }

    @Test
    fun runTablesMustStartRightNeverDecreaseAndEndRight() {
        read(BitPacked.encode(intArrayOf(0, 2, 2, 5))).checkRuns("t", 0, 5)
        read(BitPacked.encode(intArrayOf(1, 1))).checkRuns("t", 1, 1)
        listOf(
            intArrayOf() to "does not start",
            intArrayOf(1, 2, 5) to "does not start",
            intArrayOf(0, 3, 2, 5) to "decreases at 2",
            intArrayOf(0, 2, 4) to "ends at 4",
            intArrayOf(0, 2, 6) to "ends at 6",
        ).forEach { (values, reason) ->
            try {
                read(BitPacked.encode(values)).checkRuns("t", 0, 5)
                fail("accepted ${values.toList()}")
            } catch (e: DataFormatException) {
                assertTrue(e.message, e.message!!.contains(reason))
            }
        }
    }

    @Test
    fun valuesMustStayBelowALimit() {
        read(BitPacked.encode(intArrayOf(0, 4, 2))).checkBelow("t", 5)
        try {
            read(BitPacked.encode(intArrayOf(0, 5, 2))).checkBelow("t", 5)
            fail("accepted")
        } catch (e: DataFormatException) {
            assertTrue(e.message, e.message!!.contains("5 at 1"))
        }
    }
}
