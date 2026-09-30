/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class DataFileTest {

    private fun sample() = DataFile.Writer(DataFile.KIND_TABLE, KIND_VERSION)
        .add(7, byteArrayOf(1, 2, 3))
        .add(2, BitPacked.encode(intArrayOf(5, 6)))
        .addMeta(1, mapOf("码长" to "4", "name" to "a=b"))
        .toByteArray()

    private fun open(bytes: ByteArray, kind: Int = DataFile.KIND_TABLE) = DataFile.open(ByteBuffer.wrap(bytes), kind, KIND_VERSION)

    private fun assertRejected(bytes: ByteArray, kind: Int = DataFile.KIND_TABLE, reason: String) {
        try {
            open(bytes, kind)
            fail("accepted")
        } catch (e: DataFormatException) {
            assertTrue(e.message, e.message!!.contains(reason))
        }
    }

    @Test
    fun sectionsComeBackAlignedAndIntact() {
        val bytes = sample()
        val file = open(bytes)
        val raw = file.section(7)
        assertEquals(3, raw.capacity())
        assertEquals(listOf<Byte>(1, 2, 3), (0 until 3).map { raw.get(it) })
        assertEquals(6, file.bitPacked(2)[1])
        assertEquals(mapOf("码长" to "4", "name" to "a=b"), file.meta(1))
        assertTrue(file.has(7))
        assertFalse(file.has(3))
        // every section starts on an 8-byte boundary so mapped reads can be aligned
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        (0 until header.getInt(16)).forEach { assertEquals(0L, header.getLong(24 + it * 24 + 8) % 8) }
    }

    @Test
    fun aSectionViewIsLittleEndianAndStartsAtZero() {
        val file = open(DataFile.Writer(DataFile.KIND_TABLE, KIND_VERSION).add(1, byteArrayOf(0, 0, 0, 0, 1, 0, 0, 0)).toByteArray())
        assertEquals(1, file.section(1).getInt(4))
    }

    @Test
    fun aMissingSectionIsAFormatError() {
        try {
            open(sample()).section(3)
            fail("returned a section that is not there")
        } catch (e: DataFormatException) {
            assertTrue(e.message!!.contains("missing section 3"))
        }
    }

    @Test
    fun theWrongKindIsRejected() {
        assertRejected(sample(), DataFile.KIND_PINYIN, "file kind 2")
    }

    @Test
    fun anotherFormatVersionIsRejected() {
        val bytes = sample()
        bytes[8] = (DataFile.VERSION + 1).toByte()
        assertRejected(bytes, reason = "container version")
    }

    @Test
    fun anotherLayoutOfTheSameKindIsRejected() {
        val bytes = sample()
        bytes[20] = (KIND_VERSION + 1).toByte()
        assertRejected(bytes, reason = "kind 2 format version 4")
    }

    @Test
    fun theFileMayStartPartWayIntoABuffer() {
        val bytes = byteArrayOf(9, 9, 9) + sample() + byteArrayOf(9)
        val buffer = ByteBuffer.wrap(bytes, 3, bytes.size - 4)
        val file = DataFile.open(buffer, DataFile.KIND_TABLE, KIND_VERSION)
        assertEquals(6, file.bitPacked(2)[1])
        assertEquals(3, buffer.position()) // the caller's buffer is left alone
    }

    @Test
    fun aSectionListedTwiceIsRejected() {
        val bytes = sample()
        // entries are sorted by id: 1, 2, 7; make the second claim to be the first
        bytes[24 + 24] = 1
        assertRejected(bytes, reason = "section 1 listed twice")
    }

    @Test
    fun aHugeSectionLengthCannotOverflowPastTheCheck() {
        val bytes = sample()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        header.putLong(24 + 16, Long.MAX_VALUE)
        assertRejected(bytes, reason = "out of bounds")
    }

    @Test
    fun somethingElseEntirelyIsRejected() {
        assertRejected("not a data file at all, but long enough".toByteArray(), reason = "not an engine data file")
        assertRejected(ByteArray(3), reason = "too short")
    }

    @Test
    fun aDamagedSectionIsRejectedUnlessTheCallerSkipsTheCheck() {
        val bytes = sample()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // the checksum sits beside each section's id: CRC32 of its bytes
        val at = header.getLong(24 + 24 + 8).toInt()
        val length = header.getLong(24 + 24 + 16).toInt()
        assertEquals(CRC32().apply { update(bytes, at, length) }.value.toInt(), header.getInt(24 + 24 + 4))
        bytes[at] = (bytes[at] + 1).toByte()
        assertRejected(bytes, reason = "section 2 is damaged")
        DataFile.open(ByteBuffer.wrap(bytes), DataFile.KIND_TABLE, KIND_VERSION, verify = false)
    }

    @Test
    fun noDamagedByteGoesUnnoticed() {
        val bytes = sample()
        val ids = listOf(1, 2, 7)
        val original = open(bytes).let { f -> ids.associateWith { f.bytes(it).toList() } }
        for (i in bytes.indices) {
            val damaged = bytes.copyOf().also { it[i] = (it[i].toInt() xor 0x40).toByte() }
            val sections = runCatching { open(damaged).let { f -> ids.associateWith { f.bytes(it).toList() } } }
            // rejected, or a section it holds went missing, or it hit padding and changed nothing
            sections.exceptionOrNull()?.let { assertTrue("byte $i: $it", it is DataFormatException) }
            sections.onSuccess { assertEquals("byte $i", original, it) }
        }
    }

    private fun DataFile.bytes(id: Int) = section(id).let { s -> ByteArray(s.capacity()) { s.get(it) } }

    @Test
    fun aTruncatedFileIsRejected() {
        val bytes = sample()
        assertRejected(bytes.copyOf(bytes.size - 2), reason = "out of bounds")
    }

    @Test
    fun aCorruptSectionCountIsRejected() {
        val bytes = sample()
        bytes[16] = 100
        assertRejected(bytes, reason = "section count")
    }

    @Test
    fun aSectionCountFarBeyondAnyLayoutIsRejectedEvenIfTheFileIsLargeEnough() {
        val bytes = ByteArray(1000 * 24 + 24).also { sample().copyInto(it) }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 1000)
        assertRejected(bytes, reason = "section count")
    }

    @Test
    fun anOversizedMetaSectionIsRejected() {
        val file = open(DataFile.Writer(DataFile.KIND_TABLE, KIND_VERSION).addMeta(1, mapOf("k" to "v".repeat(70_000))).toByteArray())
        try {
            file.meta(1)
            fail("accepted")
        } catch (e: DataFormatException) {
            assertTrue(e.message, e.message!!.contains("meta section"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSectionCannotBeAddedTwice() {
        DataFile.Writer(DataFile.KIND_TABLE, KIND_VERSION).add(1, ByteArray(1)).add(1, ByteArray(1))
    }

    @Test
    fun metaValuesCannotHoldLineBreaks() {
        listOf("a\nb", "a\rb").forEach { v ->
            try {
                DataFile.Writer(DataFile.KIND_TABLE, KIND_VERSION).addMeta(1, mapOf("k" to v))
                fail("accepted ${v.length}")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun aBadlyFormedBitPackedSectionIsAFormatError() {
        val file = open(DataFile.Writer(DataFile.KIND_TABLE, KIND_VERSION).add(1, ByteArray(8) { if (it == 0) 40 else 0 }).toByteArray())
        try {
            file.bitPacked(1)
            fail("accepted a 40-bit width")
        } catch (e: DataFormatException) {
            assertTrue(e.message!!.startsWith("section 1"))
        }
    }

    @Test
    fun aSectionTooShortForABitPackedHeaderIsAFormatError() {
        val file = open(DataFile.Writer(DataFile.KIND_TABLE, KIND_VERSION).add(1, ByteArray(4)).toByteArray())
        try {
            file.bitPacked(1)
            fail("read a header that is not there")
        } catch (e: DataFormatException) {
            assertTrue(e.message!!.contains("truncated header"))
        }
    }

    private companion object {
        const val KIND_VERSION = 3
    }
}
