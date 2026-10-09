/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.libime

import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Random

/** Frames made by the zstd tool (see `resources/libime/README.md`), each checked by its digest. */
class ZstdTest {

    private fun decompressed(name: String) = Zstd.decompress(resource(name))

    private fun assertDigest(expected: String, size: Int, bytes: ByteArray) {
        assertEquals(size, bytes.size)
        assertEquals(expected, sha256(bytes))
    }

    @Test
    fun textAsTheBestLevelPacksIt() {
        // over 128 KiB: two blocks, Huffman literals in four streams, tables carried between them
        assertDigest(TEXT, 140000, decompressed("text.19.zst"))
    }

    @Test
    fun textAsTheFastestLevelPacksIt() {
        assertDigest(TEXT, 140000, decompressed("text.fast.zst"))
    }

    @Test
    fun textWithASmallWindow() {
        // short offsets over and over: the repeated ones
        assertDigest(TEXT, 140000, decompressed("text.window.zst"))
    }

    @Test
    fun randomBytesAreKeptRaw() {
        assertDigest("91382123a8e56dcc00ea9bf9080a94a30e6be48a26e7fdc1439f2b977c2bd47b", 1000, decompressed("random.zst"))
    }

    @Test
    fun zerosAreOneByteRepeated() {
        assertArrayEquals(ByteArray(100000), decompressed("zeros.zst"))
    }

    @Test
    fun framesFollowingEachOtherAreJoined() {
        assertDigest("1d2580f0c17ed2408c85b43cfbec5ff708cd667353f2d63daaf755394f1dd15b", 2000, decompressed("two-frames.zst"))
    }

    @Test
    fun aFrameWithoutItsChecksum() {
        assertDigest("31e55702c0cf3be8bb8e3298cdb8a9be64cdbeaf74f886ca906903a03a2ff20f", 1000, decompressed("unchecked.zst"))
    }

    @Test
    fun skippableFramesAreSkipped() {
        val frame = resource("unchecked.zst")
        val skippable = byteArrayOf(0x5A, 0x2A, 0x4D, 0x18, 3, 0, 0, 0, 1, 2, 3)
        val data = skippable + frame + skippable
        assertArrayEquals(Zstd.decompress(frame), Zstd.decompress(data))
    }

    @Test
    fun decompressingFromAnOffset() {
        val frame = resource("random.zst")
        assertArrayEquals(Zstd.decompress(frame), Zstd.decompress(byteArrayOf(9, 9, 9) + frame, 3))
    }

    @Test
    fun aFrameIsKnownByItsMagic() {
        assertTrue(Zstd.isFrame(resource("random.zst")))
        assertTrue(Zstd.isFrame(byteArrayOf(0) + resource("random.zst"), 1))
        assertFalse(Zstd.isFrame(byteArrayOf(0x28, 0xB5.toByte(), 0x2F)))
        assertFalse(Zstd.isFrame("not zstd".toByteArray()))
    }

    @Test
    fun whatIsNotZstdFails() {
        assertThrows(DataFormatException::class.java) { Zstd.decompress("not zstd".toByteArray()) }
    }

    @Test
    fun aFrameCutShortFails() {
        val frame = resource("text.19.zst")
        for (length in listOf(4, 5, 10, 100, frame.size / 2, frame.size - 5)) {
            assertThrows("cut at $length", DataFormatException::class.java) { Zstd.decompress(frame.copyOf(length)) }
        }
    }

    @Test
    fun aFrameNeedingADictionaryFails() {
        val frame = resource("unchecked.zst").copyOf()
        frame[4] = (frame[4].toInt() or 1).toByte() // a one-byte dictionary id
        assertThrows(DataFormatException::class.java) { Zstd.decompress(frame) }
    }

    @Test
    fun theReservedBitFails() {
        val frame = resource("unchecked.zst").copyOf()
        frame[4] = (frame[4].toInt() or 0x08).toByte()
        assertThrows(DataFormatException::class.java) { Zstd.decompress(frame) }
    }

    @Test
    fun moreThanTheLimitFails() {
        assertThrows(DataFormatException::class.java) { Zstd.decompress(resource("zeros.zst"), limit = 99999) }
        assertEquals(100000, Zstd.decompress(resource("zeros.zst"), limit = 100000).size)
    }

    @Test
    fun aBlockMakesNoMoreThan128KiB() {
        assertArrayEquals(ByteArray(2 * MAX_BLOCK) { 7 }, Zstd.decompress(rle(2, MAX_BLOCK)))
        assertThrows(DataFormatException::class.java) { Zstd.decompress(rle(1, MAX_BLOCK + 1)) }
    }

    @Test
    fun aFewBytesMakingMoreThan128MiBFail() {
        // as much as zhwiki's dictionary unpacks to (64.5 MiB) reads
        assertEquals(520 * MAX_BLOCK, Zstd.decompress(rle(520, MAX_BLOCK)).size)
        // four bytes a block: four kilobytes of file for 128 MiB
        assertThrows(DataFormatException::class.java) { Zstd.decompress(rle(1025, MAX_BLOCK)) }
    }

    /** A frame of [blocks] RLE blocks, each [size] bytes of 7, with no size or checksum said. */
    private fun rle(blocks: Int, size: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0, 0x58))
        repeat(blocks) {
            val header = (size shl 3) or (1 shl 1) or (if (it == blocks - 1) 1 else 0)
            out.write(byteArrayOf(header.toByte(), (header ushr 8).toByte(), (header ushr 16).toByte(), 7))
        }
        return out.toByteArray()
    }

    @Test
    fun theChecksumIsXxh64sLowHalf() {
        // XXH64's published values, seed 0; the fixtures check the long inputs
        assertEquals(0xEF46DB3751D8E999UL.toLong(), Zstd.xxh64(ByteArray(0), 0, 0))
        assertEquals(0xD24EC4F1A98C6E5BUL.toLong(), Zstd.xxh64(byteArrayOf(9, 'a'.code.toByte()), 1, 1))
        assertEquals(0x44BC2CF5AD770999UL.toLong(), Zstd.xxh64("abc".toByteArray(), 0, 3))
    }

    @Test
    fun aChecksumThatDoesNotMatchFails() {
        val frame = resource("random.zst").copyOf()
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte()
        assertThrows(DataFormatException::class.java) { Zstd.decompress(frame) }
    }

    @Test
    fun aSizeThatDoesNotMatchFails() {
        // random.zst says its size in two bytes after the descriptor, less 256
        val frame = resource("random.zst").copyOf()
        assertEquals(0x64, frame[4].toInt())
        for (change in listOf(-1, 1)) {
            val wrong = frame.copyOf()
            wrong[5] = (wrong[5] + change).toByte()
            assertThrows(DataFormatException::class.java) { Zstd.decompress(wrong) }
        }
    }

    @Test
    fun corruptFramesFailAsDataFormatOrDecodeToSomething() {
        // whatever the bytes, no other exception and no hang: imported files come from anywhere
        val random = Random(7)
        for (name in listOf("text.19.zst", "text.fast.zst", "text.window.zst", "zeros.zst", "two-frames.zst")) {
            val original = resource(name)
            repeat(300) {
                val frame = original.copyOf()
                repeat(1 + random.nextInt(3)) { frame[random.nextInt(frame.size)] = random.nextInt(256).toByte() }
                try {
                    Zstd.decompress(frame)
                } catch (_: DataFormatException) {
                }
            }
        }
    }

    companion object {
        private const val TEXT = "cfd5fa92102fe0b9e17460daad0a8a6e26bb9e6757835da0a03fcea93b9bf7b4"

        // RFC 8878's Block_Maximum_Size
        private const val MAX_BLOCK = 1 shl 17

        fun resource(name: String): ByteArray =
            ZstdTest::class.java.getResourceAsStream("/libime/$name")!!.use { it.readBytes() }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
