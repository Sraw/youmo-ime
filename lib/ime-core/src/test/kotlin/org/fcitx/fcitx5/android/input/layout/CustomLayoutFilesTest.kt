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

    // a few bytes a read, as a content provider may
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
        // what the strict check only refuses is taken, what would break the list or a key's name is not
        assertTrue("a name of hidden characters", restorable("mine.xml", own("mine").replaceFirst("name=\"", "name=\"&#x202E;")))
        assertFalse("a family the list has no section for", restorable("my-zhuyin.xml", boards.getValue("zhuyin").replaceFirst("id=\"zhuyin\"", "id=\"my-zhuyin\" based-on=\"qwerty\"")))
        assertFalse("an app's board's id", restorable("k18.xml", boards.getValue("k18").replaceFirst("id=\"k18\"", "id=\"k18\" based-on=\"k18\"")))
        assertFalse("a key name's prefix", restorable("g18.xml", own("g18")))
        assertFalse("what its base must be typed on gone", restorable("mine.xml", own("mine").replace("role=\"backspace\" ", "")))
        assertFalse("for a newer app", restorable("mine.xml", own("mine").replaceFirst("<defaults", "<meta min-app=\"9.0.0\"/>\n  <defaults")))
        // a number board whose pad lacks what a field of it types: not drawn, whatever the backup says
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

    // a pack imported again replaces its old stamp, the new line last (left in place it read as older than later lines)
    @Test
    fun aRecordKeepsEachPacksAndLayoutsLastLineTheNewestWhereFull() {
        assertEquals("b@1\na@2\n", CustomLayoutFiles.mergeConverted("a@1\nb@1\n", "a@2"))
        assertEquals("=t1-k18 k18 d2 a\n", CustomLayoutFiles.mergeConverted("=t1-k18 k18 d1 a", "=t1-k18 k18 d2 a"))
        // a pack's name may hold an @
        assertEquals("a@1\na@b@2\n", CustomLayoutFiles.mergeConverted("a@b@1\na@1", "a@b@2"))
        val full = (1..CustomLayoutFiles.MAX_CONVERTED_LINES).joinToString("\n") { "p$it@1" }
        val lines = CustomLayoutFiles.convertedLines("${CustomLayoutFiles.CURRENT}\n$full\nnew@1")
        assertEquals(CustomLayoutFiles.MAX_CONVERTED_LINES, lines.size)
        assertEquals(CustomLayoutFiles.CURRENT, lines.first())
        assertEquals("new@1", lines.last())
        // p1 imported again is the newest, kept when full; p2, the oldest now, dropped
        val again = CustomLayoutFiles.convertedLines("$full\np1@2\nnew@1")
        assertEquals(listOf("p1@2", "new@1"), again.takeLast(2))
        assertFalse("p2@1" in again)
    }

    // the user's file over t1-k18 made P's next import t1-k18-2; both deleted, P's last write is t1-k18: updates go there
    @Test
    fun aLayoutWrittenAgainIsThePacksLastAfterOnesWrittenSince() {
        val r = CustomLayoutFiles.record("#l7\nP@1\n=t1-k18 k18 d1 P\nP@2\n=t1-k18-2 k18 d2 P\nP@3\n=t1-k18 k18 d3 P")
        assertEquals(CustomLayoutFiles.Made("t1-k18", "k18", "d3", "P"), r.madeOf("P", "k18"))
    }

    @Test
    fun aRecordReadsItsPacksLayoutsAndWhetherCurrent() {
        val r = CustomLayoutFiles.record("#l7\nmint@5\n=t1a2b3c4d-k18 k18 0123456789abcdef mint 2\n=Bad k18 x y\n=t9-nine nine\nold@1")
        assertTrue(r.current)
        assertEquals(listOf("mint@5", "old@1"), r.packs)
        assertEquals(setOf("mint", "old"), r.packNames)
        val made = CustomLayoutFiles.Made("t1a2b3c4d-k18", "k18", "0123456789abcdef", "mint 2")
        assertEquals(listOf(made), r.made)
        assertEquals(made, r.madeOf("mint 2", "k18"))
        assertEquals(null, r.madeOf("mint 2", "nine"))
        assertEquals("=t1a2b3c4d-k18 k18 0123456789abcdef mint 2", made.line)
        assertFalse(CustomLayoutFiles.record("p@1").current)
        assertFalse(CustomLayoutFiles.record(null).current)
    }

    // a pack whose file name begins with `=` is a pack's line of its own, not dropped
    @Test
    fun aPackNamedWithAnEqualsSignFirstIsRecordedAsAPack() {
        val r = CustomLayoutFiles.record("#l7\n=mint.theme@5\n=odd one.theme@6\n=t1-k18 k18 d1 =mint.theme\n=mint.theme@7")
        // in the order last written: mint's new stamp after odd one's
        assertEquals(listOf("=odd one.theme@6", "=mint.theme@7"), r.packs)
        assertEquals(setOf("=mint.theme", "=odd one.theme"), r.packNames)
        assertEquals(listOf(CustomLayoutFiles.Made("t1-k18", "k18", "d1", "=mint.theme")), r.made)
    }

    @Test
    fun aFreeIdIsTheOneWantedElseTheNextNumbered() {
        assertEquals("t1-k18", CustomLayoutFiles.freeId("t1-k18") { false })
        assertEquals("t1-k18-3", CustomLayoutFiles.freeId("t1-k18") { it in setOf("t1-k18", "t1-k18-2") })
        val long = "a".repeat(LayoutCheck.MAX_ID)
        assertEquals("a".repeat(LayoutCheck.MAX_ID - 2) + "-2", CustomLayoutFiles.freeId(long) { it == long })
        assertEquals(16, CustomLayoutFiles.digest("x".encodeToByteArray()).length)
        assertEquals(CustomLayoutFiles.digest("x".encodeToByteArray()), CustomLayoutFiles.digest("x".encodeToByteArray()))
    }

    // what a pack's conversion writes over, and what it keeps
    @Test
    fun aPacksBoardReplacesOnlyTheLayoutItMadeAsItIs() {
        val record = CustomLayoutFiles.record("=t1-k18 k18 d1 mint\nmint@1")
        fun target(files: Map<String, String>, same: Boolean = false, file: String = "mint") =
            CustomLayoutFiles.target(record, file, "k18", "t1-k18", same) { files[it] }
        // its last one as written: replaced (an update)
        assertEquals(CustomLayoutFiles.Target("t1-k18", CustomLayoutFiles.Put.REPLACE), target(mapOf("t1-k18" to "d1")))
        // changed by the user since (exported, edited, imported over it): kept, the pack's under the next id
        assertEquals(CustomLayoutFiles.Target("t1-k18-2", CustomLayoutFiles.Put.NEW), target(mapOf("t1-k18" to "d2")))
        // the same import listed again (a start killed in the middle): the one it made, kept as the user has it
        assertEquals(CustomLayoutFiles.Target("t1-k18", CustomLayoutFiles.Put.KEEP), target(mapOf("t1-k18" to "d2"), same = true))
        // deleted: made again
        assertEquals(CustomLayoutFiles.Target("t1-k18", CustomLayoutFiles.Put.NEW), target(emptyMap()))
        // another pack of the same hash, or the user's own of that id: kept
        assertEquals(CustomLayoutFiles.Target("t1-k18-2", CustomLayoutFiles.Put.NEW), target(mapOf("t1-k18" to "d1"), file = "other"))
        assertEquals(CustomLayoutFiles.Target("t1-k18-3", CustomLayoutFiles.Put.NEW), target(mapOf("t1-k18" to "", "t1-k18-2" to "x"), file = "other"))
        // a file already what the board makes (killed before the record was written) is written over, not copied to -2
        fun unrecorded(files: Map<String, String>, writes: Map<String, String>) =
            CustomLayoutFiles.target(CustomLayoutFiles.record("#l7"), "mint", "k18", "t1-k18", same = false, writes = { writes[it] }) { files[it] }
        assertEquals(CustomLayoutFiles.Target("t1-k18", CustomLayoutFiles.Put.REPLACE), unrecorded(mapOf("t1-k18" to "d1"), mapOf("t1-k18" to "d1")))
        assertEquals(CustomLayoutFiles.Target("t1-k18-2", CustomLayoutFiles.Put.REPLACE), unrecorded(mapOf("t1-k18" to "d9", "t1-k18-2" to "d2"), mapOf("t1-k18" to "d1", "t1-k18-2" to "d2")))
        assertEquals(CustomLayoutFiles.Target("t1-k18-2", CustomLayoutFiles.Put.NEW), unrecorded(mapOf("t1-k18" to "d9"), mapOf("t1-k18" to "d1")))
    }

    companion object {
        // the app's boards, as the build copies them in
        const val LAYOUTS = "../keyboard-scene/src/commonMain/layouts"
    }
}
