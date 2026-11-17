/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class CustomLayoutFilesTest {

    private val qwerty = File(LAYOUTS, "qwerty.xml").readText()
    private val boards = File(LAYOUTS).listFiles()!!.filter { it.name.endsWith(".xml") }.associate { it.name.removeSuffix(".xml") to it.readText() }
    private val sources = LayoutCheck.Sources(boards - "grouped-bottom", boards.filterKeys { it == "grouped-bottom" })

    private fun restorable(name: String, text: String, app: String = "0.6.1") = CustomLayoutFiles.restorable(name, text.toByteArray(), sources, app)

    // the app's 26 keys made a layout of one's own of [id]
    private fun own(id: String) = qwerty.replaceFirst("id=\"qwerty\"", "id=\"$id\" based-on=\"qwerty\"")

    @Test
    fun aFileIsNamedOnlyByAnIdTheReaderTakes() {
        assertEquals("my-17.xml", CustomLayoutFiles.fileName("my-17"))
        for (bad in listOf("", "../x", "a/b", "A", "a--b", "-a", "a-", "a.b", "a b", "x".repeat(33), "名字")) {
            assertFalse(bad, CustomLayoutFiles.validId(bad))
            assertTrue(bad, runCatching { CustomLayoutFiles.fileName(bad) }.isFailure)
        }
        assertTrue(CustomLayoutFiles.validId("x".repeat(32)))
    }

    @Test
    fun onlyALayoutsFileIsReadForItsId() {
        assertEquals("k17", CustomLayoutFiles.idOf("k17.xml"))
        assertNull(CustomLayoutFiles.idOf(".converted"))
        assertNull(CustomLayoutFiles.idOf("k17.xml.saving"))
        assertNull(CustomLayoutFiles.idOf("..xml"))
        assertNull(CustomLayoutFiles.idOf("K17.xml"))
        assertNull(CustomLayoutFiles.idOf("k17.layout"))
    }

    @Test
    fun anExportIsNamedByTheLayoutsNameWithNothingAPathCouldTake() {
        assertEquals("17 键.layout", CustomLayoutFiles.exportName("17 键"))
        assertEquals("a_b_c.layout", CustomLayoutFiles.exportName("a/b\\c"))
        // what is not shown left out: a line break, a direction override turning `txt.layout` around
        assertEquals("xy.layout", CustomLayoutFiles.exportName("x\ny"))
        assertEquals("ab.layout", CustomLayoutFiles.exportName("a\u202Eb\u200B"))
        assertEquals("layout.layout", CustomLayoutFiles.exportName(".."))
        assertEquals("layout.layout", CustomLayoutFiles.exportName("  "))
        assertEquals("layout.layout", CustomLayoutFiles.exportName("\u202E"))
    }

    // a stream that hands out a few bytes a read, as a content provider may
    private class Trickle(private val bytes: ByteArray) : InputStream() {
        private var at = 0
        var read = 0
            private set

        override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xff else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= bytes.size) return -1
            val n = minOf(len, 7, bytes.size - at)
            System.arraycopy(bytes, at, b, off, n)
            at += n
            read += n
            return n
        }
    }

    @Test
    fun aFileIsReadNoFurtherThanTheLimitAndOnlyAsUtf8() {
        val max = CustomLayoutFiles.MAX_BYTES.toInt()
        val atMost = ByteArray(max) { 'a'.code.toByte() }
        assertEquals(String(atMost), (CustomLayoutFiles.read(Trickle(atMost)) as CustomLayoutFiles.Read.Text).text)
        // a byte more: refused, and no more read than the one past the limit
        val big = Trickle(ByteArray(max * 4) { 'a'.code.toByte() })
        assertSame(CustomLayoutFiles.Read.TooBig, CustomLayoutFiles.read(big))
        assertEquals(max + 1, big.read)
        assertSame(CustomLayoutFiles.Read.NotText, CustomLayoutFiles.read(ByteArrayInputStream(byteArrayOf(0x3c, 0xff.toByte(), 0xfe.toByte()))))
        // a PNG's head: not text
        assertSame(CustomLayoutFiles.Read.NotText, CustomLayoutFiles.read(ByteArrayInputStream(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a))))
        assertEquals("键", (CustomLayoutFiles.read(ByteArrayInputStream("键".toByteArray())) as CustomLayoutFiles.Read.Text).text)
        assertArrayEquals(ByteArray(0), CustomLayoutFiles.readAtMost(ByteArrayInputStream(ByteArray(0)), 10))
    }

    @Test
    fun aBackupsFileIsRestoredOnlyAsTheLayoutItsNameSays() {
        assertTrue(restorable("mine.xml", own("mine")))
        assertTrue("a byte-order mark", restorable("mine.xml", "\uFEFF" + own("mine")))
        assertFalse("renamed in the zip", restorable("other.xml", own("mine")))
        assertFalse("not a layout's name", restorable("../mine.xml", own("mine")))
        assertFalse("not read", restorable("mine.xml", "<keyboard"))
        assertFalse("not UTF-8", CustomLayoutFiles.restorable("mine.xml", byteArrayOf(0xff.toByte(), 0xfe.toByte()), sources, "0.6.1"))
        val big = own("mine").replace("</keyboard>", "<!--" + "x".repeat(CustomLayoutFiles.MAX_BYTES.toInt()) + "--></keyboard>")
        assertFalse("too big", restorable("mine.xml", big))
        // what the strict check only refuses is taken (the 信息 tells it), what would break the list or a key's name is not
        assertTrue("a name of hidden characters", restorable("mine.xml", own("mine").replaceFirst("name=\"", "name=\"&#x202E;")))
        assertFalse("a family the list has no section for", restorable("my-zhuyin.xml", boards.getValue("zhuyin").replaceFirst("id=\"zhuyin\"", "id=\"my-zhuyin\" based-on=\"qwerty\"")))
        assertFalse("an app's board's id", restorable("k18.xml", boards.getValue("k18").replaceFirst("id=\"k18\"", "id=\"k18\" based-on=\"k18\"")))
        assertFalse("a key name's prefix", restorable("g18.xml", own("g18")))
        assertFalse("what its base must be typed on gone", restorable("mine.xml", own("mine").replace("role=\"backspace\" ", "")))
        assertFalse("for a newer app", restorable("mine.xml", own("mine").replaceFirst("<defaults", "<meta min-app=\"9.0.0\"/>\n  <defaults")))
        // a number board whose pad lacks what a field of it types: not drawn, whatever the backup says (L5 review N1)
        val numbers = boards.getValue("number").replaceFirst("id=\"number\"", "id=\"my-num\" based-on=\"number\" pads=\"decimal\"")
        assertTrue("a number board", restorable("my-num.xml", numbers))
        assertFalse("its decimal pad without 5", restorable("my-num.xml", numbers.replace("send=\"sym:KP_5\"", "send=\"commit:x\"")))
        val names = (1..150).map { "l$it.xml" } + listOf(".converted", "x.xml.saving", "Bad.xml")
        val taken = CustomLayoutFiles.restoredNames(names)
        assertEquals(CustomLayoutFiles.MAX_LAYOUTS, taken.size)
        assertTrue(taken.all { CustomLayoutFiles.idOf(it) != null })
    }

    @Test
    fun theConvertedRecordsAreMergedEachLineOnce() {
        assertEquals("a@1\nb@2\nc@3\n", CustomLayoutFiles.mergeConverted("a@1\nb@2\n", "b@2\r\n\nc@3"))
        assertEquals("a@1\n", CustomLayoutFiles.mergeConverted(null, "a@1"))
        assertEquals("", CustomLayoutFiles.mergeConverted(null, null))
        val long = "x".repeat(CustomLayoutFiles.MAX_CONVERTED_LINE + 1)
        assertEquals("a@1\n", CustomLayoutFiles.mergeConverted("a@1", long))
        val many = (1..5000).joinToString("\n") { "p$it@1" }
        assertEquals(CustomLayoutFiles.MAX_CONVERTED_LINES, CustomLayoutFiles.convertedLines(many).size)
        // the longest lines, of three bytes a character: what is written is read again
        val wide = (1..CustomLayoutFiles.MAX_CONVERTED_LINES).joinToString("\n") { "$it@" + "包".repeat(CustomLayoutFiles.MAX_CONVERTED_LINE - 5) }
        assertTrue(CustomLayoutFiles.mergeConverted(null, wide).encodeToByteArray().size <= CustomLayoutFiles.MAX_CONVERTED_BYTES)
    }

    companion object {
        // the app's boards, as the build copies them in
        const val LAYOUTS = "../keyboard-scene/src/commonMain/layouts"
    }
}
