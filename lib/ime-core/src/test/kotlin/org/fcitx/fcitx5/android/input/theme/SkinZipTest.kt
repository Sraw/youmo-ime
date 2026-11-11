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
    fun aFinderZipOfOneFlexIsUnpackedLikeAnyOther() {
        // the bug: Finder adds __MACOSX/._ink.flex beside ink.flex; it was counted as a second .flex, so the
        // inner one was never unpacked and the import failed with a file-name error
        val flex = zip("extension.json" to "{}".toByteArray(), "stylesheets/a.json" to "{}".toByteArray())
        val finder = zip("ink.flex" to flex, "__MACOSX/._ink.flex" to byteArrayOf(0, 5, 22, 7))
        assertEquals(setOf("extension.json", "stylesheets/a.json"), SkinZip.readSkin(finder.inputStream(), "ink.zip").keys)
        // nor is a `._extension.json` beside it an extension of the outer ZIP's own
        val dot = zip("ink.flex" to flex, "._extension.json" to byteArrayOf(0))
        assertEquals(setOf("extension.json", "stylesheets/a.json"), SkinZip.readSkin(dot.inputStream(), "ink.zip").keys)
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
    fun anEmptyDirectoryIsReadEntryByEntry() {
        // an end record that says no files (a tool's that left it so): the local headers read in turn
        val b = zip("a.txt" to "hello".toByteArray(), "b.txt" to ByteArray(3000) { 2 })
        val end = (b.size - 22 downTo 0).first { b[it] == 0x50.toByte() && b[it + 1] == 0x4b.toByte() && b[it + 2] == 5.toByte() && b[it + 3] == 6.toByte() }
        // the files on this disk and in all
        for (o in 8 until 12) b[end + o] = 0
        val files = SkinZip.read(b)
        assertEquals(setOf("a.txt", "b.txt"), files.keys)
        assertArrayEquals(ByteArray(3000) { 2 }, files.getValue("b.txt"))
    }

    @Test
    fun aZippedExtensionIsUnpackedWithinTheZipsBudget() {
        // a budget of 300 KB (the rule, at a test's size): 200 KB unpacked in the .flex (within it alone), 100 KB more
        // in the ZIP's own files: past it together
        val budget = 300L * 1024
        val rnd = Random(3)
        fun noise(n: Int) = ByteArray(n) { rnd.nextInt(4).toByte() }
        val kb = 1024
        val flex = zip("extension.json" to "{}".toByteArray(), "stylesheets/a.bin" to noise(100 * kb), "stylesheets/b.bin" to noise(100 * kb))
        assertEquals(3, SkinZip.readSkin(ByteArrayInputStream(zip("x.flex" to flex, stored = true)), "x.zip", budget).size)
        val both = zip("x.flex" to flex, "pad.bin" to noise(100 * kb), stored = true)
        assertTrue(both.size < budget)
        val e = assertThrows(SkinError::class.java) { SkinZip.readSkin(ByteArrayInputStream(both), "x.zip", budget) }
        assertEquals(Reason.UNPACKED_TOO_BIG, e.reason)
    }

    @Test
    fun aDeflatedFileCutShortIsRefused() {
        // the directory says fewer packed bytes than the stream needs: half a file is never taken as one
        val b = zip("a.bin" to ByteArray(20_000) { (it * 31 % 251).toByte() })
        cen(b) { at -> for (o in 0 until 4) b[at + 20 + o] = (if (o == 0) 16 else 0).toByte() }
        assertEquals(Reason.NOT_ZIP, reason(b))
    }

    @Test
    fun bytesBeforeTheZipAreSteppedOver() {
        // a self-extractor's code before the entries, the offsets counted from the file's start (as `zip -A` writes)
        val z = zip("a.txt" to "hello".toByteArray(), "b.txt" to ByteArray(3000) { 2 })
        val pre = ByteArray(100) { 7 }
        val b = pre + z
        fun add(at: Int) {
            val v = (b[at].toInt() and 0xFF) or (b[at + 1].toInt() and 0xFF shl 8) or (b[at + 2].toInt() and 0xFF shl 16) or (b[at + 3].toInt() shl 24)
            val n = v + pre.size
            for (o in 0 until 4) b[at + o] = (n shr (8 * o)).toByte()
        }
        val end = (b.size - 22 downTo 0).first { b[it] == 0x50.toByte() && b[it + 1] == 0x4b.toByte() && b[it + 2] == 5.toByte() && b[it + 3] == 6.toByte() }
        add(end + 16)
        (pre.size until end).filter { b[it] == 0x50.toByte() && b[it + 1] == 0x4b.toByte() && b[it + 2] == 1.toByte() && b[it + 3] == 2.toByte() }.forEach { add(it + 42) }
        val files = SkinZip.read(b)
        assertEquals("hello", String(files.getValue("a.txt")))
        assertArrayEquals(ByteArray(3000) { 2 }, files.getValue("b.txt"))
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
            // a skin read or refused with a reason; any other exception escapes and fails the test
            try {
                SkinZip.read(x)
            } catch (_: SkinError) {
                // refused with a reason: as it should be
            }
        }
    }

    private companion object {
        const val FUZZ = 2000
    }
}
