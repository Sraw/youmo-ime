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

class EliasFanoTest {

    private fun read(bytes: ByteArray) = EliasFano(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))

    /** Run starts as the model has them: [owners] runs of random lengths, from 0 to their total. */
    private fun starts(owners: Int, meanRun: Int, random: Random): IntArray {
        val starts = IntArray(owners + 1)
        for (o in 0 until owners) starts[o + 1] = starts[o] + random.nextInt(2 * meanRun + 1)
        return starts
    }

    @Test
    fun runStartsOfEveryDensitySurviveARoundTrip() {
        val random = Random(7)
        // gaps of a fraction of one (most bigrams have no trigram) to thousands (a common word's bigrams)
        for (meanRun in listOf(0, 1, 2, 3, 40, 5000)) {
            for (owners in listOf(0, 1, 63, 64, 65, 1000)) {
                val values = starts(owners, meanRun, random)
                val packed = read(EliasFano.encode(values))
                assertEquals(values.size, packed.size)
                for (i in values.indices) assertEquals("run $meanRun, owners $owners, at $i", values[i], packed[i])
                for (i in 0 until values.size - 1) {
                    val run = packed.run(i)
                    assertEquals(values[i], run.start)
                    assertEquals(values[i + 1], run.end)
                }
                packed.checkRuns("t", 0, values.last())
            }
        }
    }

    @Test
    fun largeValuesAndLongEqualStretchesRoundTrip() {
        val values = intArrayOf(0, 0, 0, 1, Int.MAX_VALUE / 2, Int.MAX_VALUE / 2, Int.MAX_VALUE - 1)
        val packed = read(EliasFano.encode(values))
        for (i in values.indices) assertEquals(values[i], packed[i])
        // a run's two halves apart, an end as large as a value may be
        val last = packed.run(values.size - 2)
        assertEquals(Int.MAX_VALUE / 2, last.start)
        assertEquals(Int.MAX_VALUE - 1, last.end)
        val zeros = IntArray(300)
        read(EliasFano.encode(zeros)).also { z -> for (i in zeros.indices) assertEquals(0, z[i]) }
    }

    @Test
    fun runStartsTakeAFewBitsEachRatherThanTheWidthOfTheLargest() {
        // the pinyin model's shape: 14.4M bigrams, 24.8M trigrams; here a thousandth of it
        val values = starts(14_400, 2, Random(1)).let { s -> IntArray(s.size) { s[it] * 24_800 / s.last().coerceAtLeast(1) } }
        val bits = EliasFano.encode(values).size * 8.0 / values.size
        assertTrue("$bits bits a value", bits < 4.5)
        assertTrue(BitPacked.encode(values).size * 8.0 / values.size > 14)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decreasingValuesCannotBeStored() {
        EliasFano.encode(intArrayOf(0, 2, 1))
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun aRunPastTheLastValueIsOutOfBounds() {
        read(EliasFano.encode(intArrayOf(0, 3))).run(1)
    }

    @Test
    fun runTablesMustStartRightAndEndRight() {
        read(EliasFano.encode(intArrayOf(1, 1))).checkRuns("t", 1, 1)
        listOf(
            intArrayOf() to "empty",
            intArrayOf(1, 2, 5) to "does not start",
            intArrayOf(0, 2, 4) to "ends at 4",
            intArrayOf(0, 2, 6) to "ends at 6",
        ).forEach { (values, reason) -> assertRejected(EliasFano.encode(values), reason) }
    }

    @Test
    fun aDamagedTableIsCaughtAtLoadNotAtAKeyPress() {
        val values = starts(200, 3, Random(3))
        val good = EliasFano.encode(values)
        val b = ByteBuffer.wrap(good).order(ByteOrder.LITTLE_ENDIAN)
        val lowWidth = b.getInt(0)
        val upperWords = b.getInt(8)
        val upper = 16 + ((values.size.toLong() * lowWidth + 63) / 64).toInt() * 8
        val samples = upper + upperWords * 8

        // the upper bits zeroed, as a crash may leave a block: the values' bits are not there
        assertRejected(good.copyOf().also { d -> for (i in upper until samples) d[i] = 0 }, "of ${values.size} values")
        // a sample moved: lookups past it would land on the wrong bit
        assertRejected(good.copyOf().also { d -> ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).putInt(samples + 4, b.getInt(samples + 4) + 1) }, "sample 1")
        // the last value's bit lost: its lookup would run past the table
        val lost = good.copyOf()
        val l = ByteBuffer.wrap(lost).order(ByteOrder.LITTLE_ENDIAN)
        val last = (upper until samples step 8).last { l.getLong(it) != 0L }
        l.putLong(last, l.getLong(last).let { it xor java.lang.Long.highestOneBit(it) })
        assertRejected(lost, "of ${values.size} values")
        // a bit moved so that the values stay in order is wrong data a reader cannot tell from right:
        // the sections' checksums are for that (DataFile's verify)
    }

    @Test
    fun aTruncatedOrInconsistentHeaderIsRefused() {
        val good = EliasFano.encode(intArrayOf(0, 1, 5, 9))
        for (bad in listOf(good.copyOf(10), good.copyOf(good.size - 1))) {
            try {
                read(bad)
                fail("accepted ${bad.size} bytes")
            } catch (_: IllegalArgumentException) {
            }
        }
        // upper words that would overflow an int offset past the length check
        val huge = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8, Int.MAX_VALUE) }
        try {
            read(huge)
            fail("accepted ${Int.MAX_VALUE} upper words")
        } catch (_: IllegalArgumentException) {
        }
        val wrongSamples = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 2) }
        try {
            read(wrongSamples)
            fail("accepted a wrong sample count")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun assertRejected(bytes: ByteArray, reason: String) {
        try {
            read(bytes).checkRuns("t", 0, 5)
            fail("accepted, expected $reason")
        } catch (e: DataFormatException) {
            assertTrue(e.message, e.message!!.contains(reason))
        }
    }
}
