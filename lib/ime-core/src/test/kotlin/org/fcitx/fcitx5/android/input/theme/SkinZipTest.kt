/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.scene.skin.SkinError
import org.fcitx.fcitx5.android.scene.skin.SkinError.Reason
import org.fcitx.fcitx5.android.scene.skin.SkinLimits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class SkinZipTest {

    private fun zip(vararg files: Pair<String, ByteArray>, stored: Boolean = false, utf8: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, if (utf8) Charsets.UTF_8 else SkinZip.GB18030).use { z ->
            for ((name, bytes) in files) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = bytes.size.toLong()
                    e.crc = CRC32().also { it.update(bytes) }.value
                }
                z.putNextEntry(e)
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun reason(b: ByteArray): Reason = assertThrows(SkinError::class.java) { SkinZip.read(b) }.reason

    @Test
    fun readsStoredAndDeflated() {
        val a = "[General]\nskin_name=x".toByteArray()
        val big = ByteArray(5000) { (it % 7).toByte() }
        for (stored in listOf(false, true)) {
            val files = SkinZip.read(zip("phoneTheme.ini" to a, "dir/" to ByteArray(0), "1080\\big.bin" to big, "./c/./d.txt" to a, stored = stored))
            assertEquals(setOf("phoneTheme.ini", "1080/big.bin", "c/d.txt"), files.keys)
            assertArrayEquals(big, files["1080/big.bin"])
        }
        // from a stream, as the import reads it
        assertEquals(setOf("a"), SkinZip.read(ByteArrayInputStream(zip("a" to a))).keys)
    }

    @Test
    fun skinFilesByTheirKind() {
        val yaml = "name: x\n".toByteArray()
        assertEquals(setOf("t.trime.yaml"), SkinZip.readSkin(yaml.inputStream(), "dl/t.trime.yaml").keys)
        assertEquals(Reason.TOO_BIG, assertThrows(SkinError::class.java) {
            SkinZip.readSkin(ByteArray(SkinLimits.MAX_TEXT_BYTES + 1).inputStream(), "big.yaml")
        }.reason)
        val flex = zip("extension.json" to "{}".toByteArray(), "stylesheets/a.json" to "{}".toByteArray())
        // a download: a folder with one .flex in it, read as that one
        val wrapped = zip("theme-main/README.md" to byteArrayOf(1), "theme-main/a.flex" to flex)
        assertEquals(setOf("extension.json", "stylesheets/a.json"), SkinZip.readSkin(wrapped.inputStream(), "theme-main.zip").keys)
        // an extension of its own beside one: as it is
        val own = zip("extension.json" to "{}".toByteArray(), "old/a.flex" to flex)
        assertEquals(setOf("extension.json", "old/a.flex"), SkinZip.readSkin(own.inputStream(), "x.flex").keys)
        val two = zip("a.flex" to flex, "b.flex" to flex)
        assertEquals(setOf("a.flex", "b.flex"), SkinZip.readSkin(two.inputStream(), "x.zip").keys)
    }

    @Test
    fun namesInChineseToolsEncoding() {
        val files = SkinZip.read(zip("皮肤/名.png" to byteArrayOf(1), utf8 = false))
        assertEquals(setOf("皮肤/名.png"), files.keys)
    }

    @Test
    fun aBrokenDirectoryIsReadEntryByEntry() {
        // the end record cut off: the local headers read in turn, deflated ones with sizes after their data
        val whole = zip("a.txt" to "hello".toByteArray(), "b/c.txt" to ByteArray(3000) { 1 })
        val cut = whole.copyOf(whole.size - 10)
        val files = SkinZip.read(cut)
        assertEquals(setOf("a.txt", "b/c.txt"), files.keys)
        assertEquals("hello", String(files.getValue("a.txt")))
        val stored = zip("a.txt" to "hello".toByteArray(), stored = true)
        assertEquals(setOf("a.txt"), SkinZip.read(stored.copyOf(stored.size - 5)).keys)
    }

    @Test
    fun namesOutOfTheSkinAreRefused() {
        for (name in listOf("../evil.ini", "a/../../b", "/etc/passwd", "C:/x", "a\\..\\..\\b", "a\u0001b")) {
            assertEquals(name, Reason.UNSAFE_NAME, reason(zip(name to byteArrayOf(1))))
        }
    }

    @Test
    fun notAZip() {
        assertEquals(Reason.SOGOU_ENCRYPTED, reason("Skin\u0003\u0000junk".toByteArray()))
        assertEquals(Reason.NOT_ZIP, reason("hello world".toByteArray()))
        assertEquals(Reason.NOT_ZIP, reason(ByteArray(0)))
        assertEquals(Reason.NOT_ZIP, reason(ByteArray(40).also { it[0] = 0x50; it[1] = 0x4b; it[2] = 3; it[3] = 4 }))
    }

    @Test
    fun encryptedAndOtherMethodsAreRefused() {
        val enc = zip("a" to byteArrayOf(1, 2, 3), stored = true)
        enc[6] = 1   // the local header's flags
        cen(enc) { enc[it + 8] = 1 }
        assertEquals(Reason.ENCRYPTED, reason(enc))
        val bzip = zip("a" to byteArrayOf(1, 2, 3), stored = true)
        bzip[8] = 12
        cen(bzip) { bzip[it + 10] = 12 }
        assertEquals(Reason.ENCRYPTED, reason(bzip))
    }

    // [f] at the central directory's entry
    private fun cen(b: ByteArray, f: (Int) -> Unit) {
        val at = (0 until b.size - 4).first { b[it] == 0x50.toByte() && b[it + 1] == 0x4b.toByte() && b[it + 2] == 1.toByte() && b[it + 3] == 2.toByte() }
        f(at)
    }

    @Test
    fun bombsAndSizes() {
        // 40 MB of zeros packs to a few KB: past the ratio long before it is all unpacked
        val bomb = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("bomb"))
                val zeros = ByteArray(1 shl 20)
                repeat(40) { z.write(zeros) }
                z.closeEntry()
            }
        }.toByteArray()
        assertEquals(Reason.UNPACKED_TOO_BIG, reason(bomb))
        assertEquals(Reason.UNPACKED_TOO_BIG, reason(bomb.copyOf(bomb.size - 10)))
        // too many files, in the directory and in a scan
        val many = zip(*Array(SkinLimits.MAX_ENTRIES + 1) { "f$it" to ByteArray(0) })
        assertEquals(Reason.TOO_MANY_FILES, reason(many))
        assertEquals(Reason.TOO_MANY_FILES, reason(many.copyOf(many.size - 10)))
        // past the archive's size, as bytes and as a stream (stopped at the cap, not read to its end)
        assertEquals(Reason.TOO_BIG, reason(ByteArray(SkinLimits.MAX_ARCHIVE_BYTES.toInt() + 1)))
        var read = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { read += it }
        }
        assertEquals(Reason.TOO_BIG, assertThrows(SkinError::class.java) { SkinZip.read(endless) }.reason)
        assertTrue(read < SkinLimits.MAX_ARCHIVE_BYTES + 1024 * 1024)
    }

    @Test
    fun fuzzed() {
        val rnd = Random(5)
        val seeds = listOf(
            zip("phoneTheme.ini" to "[General]".toByteArray(), "1080/a/phoneSkin.ini" to ByteArray(2000) { (it % 13).toByte() }),
            zip("port/gen.ini" to "[PANEL]".toByteArray(), "res/a.png" to ByteArray(500) { it.toByte() }, stored = true),
        )
        repeat(FUZZ) { i ->
            val b = seeds[i % seeds.size].copyOf()
            repeat(1 + rnd.nextInt(8)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            val x = if (i % 3 == 0) b.copyOf(rnd.nextInt(b.size + 1)) else b
            // a skin read or refused with a reason; never another exception
            try {
                SkinZip.read(x)
            } catch (e: SkinError) {
                assertTrue(e.reason.name, true)
            }
        }
    }

    private companion object {
        const val FUZZ = 2000
    }
}
