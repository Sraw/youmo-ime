/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FcitxUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `mine.mb` and `mine.used` of version 2 (design 060-quick-input-folders): notes, folders, the old files read. */
class QuickPhraseFilesTest {

    private val baidu = QuickPhraseEntry("#wz", "www.baidu.com", "百度")
    private val sina = QuickPhraseEntry("#wz", "www.sina.com.cn", "新浪", folder = "网址")
    private val gh = QuickPhraseEntry("gh", "https://github.com", folder = "网址")
    private val home = QuickPhraseEntry("", "北京市海淀区\n3 号楼", "地址")

    // the owner's file of before: notes typed into keys, one key all Chinese
    private val v1 = "#wz百度 www.baidu.com\n#wz新浪 www.sina.com.cn\n地址 \"北京市海淀区\\n3 号楼\"\ngh https://github.com\n"

    @Test
    @Uat("QU-27")
    fun aFileOfBeforeHasItsKeysSplitFromTheFirstCharacterNotAsciiOnAndNoneLost() {
        val mine = QuickPhraseFiles.readMine(v1.toByteArray())
        assertEquals(
            listOf(baidu, QuickPhraseEntry("#wz", "www.sina.com.cn", "新浪"), home, QuickPhraseEntry("gh", "https://github.com")),
            mine.entries,
        )
        assertEquals(emptyList<String>(), mine.folders)
        assertEquals(listOf(home), mine.entries.filter { it.keyless })
        // the rule, and that version 1 keeps whatever follows as it is
        assertEquals("a1-" to "中b c", QuickPhraseFiles.split("a1-中b c"))
        assertEquals("abc" to "", QuickPhraseFiles.split("abc"))
        // one bar dropped, whichever version: a file of now a build before rewrote has no version line
        assertEquals("a" to "x", QuickPhraseFiles.split("a｜x"))
        assertEquals("a" to "｜x", QuickPhraseFiles.split("a｜｜x"))
        assertEquals("a" to "x y", QuickPhraseFiles.split("a｜x\u00a0y"))
        // an old key and content the same as another's but for its Chinese tail stays two entries
        val twins = "#wz百度 x\n#wz新浪 x\n"
        assertEquals(2, QuickPhraseFiles.parseMine(twins.toByteArray()).size)
    }

    @Test
    @Uat("QU-26", "QU-27")
    fun version2RoundTripsNotesFoldersEmptyFoldersAndKeylessOnes() {
        val spaced = QuickPhraseEntry("w", "x", "a b", folder = "工作 邮箱")
        val entries = listOf(baidu, sina, home, gh, spaced)
        val text = QuickPhraseFiles.formatMine(entries, listOf("网址", "空的"))
        assertEquals(
            QuickPhraseFiles.HEADER + "\n" +
                "#wz｜百度 www.baidu.com\n" +
                "｜地址 \"北京市海淀区\\n3 号楼\"\n" +
                "【目录】网址\n" +
                "#wz｜新浪 www.sina.com.cn\n" +
                "gh https://github.com\n" +
                "【目录】空的\n" +
                "【目录】工作 邮箱\n" +
                "w｜a b x\n",
            text,
        )
        val back = QuickPhraseFiles.readMine(text.toByteArray())
        assertEquals(listOf(baidu, home, sina, gh, spaced), back.entries)
        assertEquals(listOf("网址", "空的", "工作 邮箱"), back.folders)
        // written again as it was
        assertEquals(text, QuickPhraseFiles.formatMine(back.entries, back.folders))
    }

    @Test
    fun fcitxOnAComputerAndOlderBuildsReadVersion2AsKeysWithTheirNotes() {
        val text = QuickPhraseFiles.formatMine(listOf(baidu, sina, gh), listOf("网址"))
        // QuickPhraseTable.parse is fcitx's reader and the older builds' import: the version and folder lines skipped
        assertEquals(
            listOf("#wz｜百度" to "www.baidu.com", "#wz｜新浪" to "www.sina.com.cn", "gh" to "https://github.com"),
            QuickPhraseTable.parse(text.toByteArray()),
        )
    }

    @Test
    @Uat("QU-27")
    fun usedOfBeforeFindsTheEntriesItsKeysBecameAndVersion2KeepsNotesNotFolders() {
        val old = "5\t#wz百度\twww.baidu.com\n7\t地址\t\"北京市海淀区\\n3 号楼\"\n"
        assertEquals(mapOf(baidu to 5L, home to 7L), QuickPhraseFiles.parseUsed(old))
        // by entry out of its folder: a move keeps the time
        val used = mapOf(baidu to 5L, sina.unfiled to 6L, QuickPhraseEntry("gone", "x") to 1L)
        val text = QuickPhraseFiles.formatUsed(used, listOf(baidu, sina))
        assertTrue(text.startsWith(QuickPhraseFiles.USED_HEADER + "\n"))
        assertEquals(mapOf(baidu to 5L, sina.unfiled to 6L), QuickPhraseFiles.parseUsed(text))
        assertTrue("5\t#wz｜百度\t" in text)
        // the book orders a filed entry by its time all the same
        val book = QuickPhraseBook(QuickPhraseTable.EMPTY, listOf(baidu, sina), QuickPhraseFiles.parseUsed(text))
        assertEquals(listOf(sina, baidu), book.mine)
    }

    @Test
    @Uat("QU-27")
    fun anImportTakesKeylessOnesCountedTheirFoldersAndSkipsALongNote() {
        val file = QuickPhraseFiles.parse((v1 + "【目录】新的\nab" + "长".repeat(QuickPhraseBook.MAX_NOTE + 1) + " y\nz 1\n").toByteArray())
        val merged = QuickPhraseFiles.merge(listOf(baidu), file.entries, listOf("网址"), file.folders)
        assertEquals(1, merged.duplicates)
        assertEquals(1, merged.badKey)
        assertEquals(1, merged.keyless)
        assertEquals(listOf("网址", "新的"), merged.folders)
        assertEquals(QuickPhraseEntry("z", "1", folder = "新的"), merged.entries.last())
        assertEquals(5, merged.entries.size)
    }

    @Test
    fun keysAreVisibleAsciiNotesAndFoldersOneLine() {
        assertTrue(QuickPhraseFiles.validKey("#wz"))
        assertTrue(QuickPhraseFiles.validKey("~".repeat(QuickPhraseBook.MAX_KEY)))
        assertFalse(QuickPhraseFiles.validKey("~".repeat(QuickPhraseBook.MAX_KEY + 1)))
        assertFalse(QuickPhraseFiles.validKey("wz百度"))
        assertFalse(QuickPhraseFiles.validKey("a b"))
        assertFalse(QuickPhraseFiles.validKey("😀"))
        assertTrue(QuickPhraseFiles.validNote("百度 首页"))
        assertFalse(QuickPhraseFiles.validNote("a\nb"))
        assertFalse(QuickPhraseFiles.validNote("a\tb"))
        assertFalse(QuickPhraseFiles.validNote(" a"))
        assertTrue(QuickPhraseFiles.validNote("长".repeat(QuickPhraseBook.MAX_NOTE)))
        assertFalse(QuickPhraseFiles.validNote("长".repeat(QuickPhraseBook.MAX_NOTE + 1)))
        for (shelf in listOf("全部", "未分类", "未分類", "All", "No folder")) assertFalse(shelf, QuickPhraseFiles.validFolder(shelf))
        assertFalse(QuickPhraseFiles.validFolder("a\u00a0b"))
        assertTrue(QuickPhraseFiles.validFolder("网址"))
        assertFalse(QuickPhraseFiles.validFolder(" 网址"))
        assertFalse(QuickPhraseFiles.validFolder(""))
        assertFalse(QuickPhraseFiles.validFolder("长".repeat(QuickPhraseBook.MAX_FOLDER + 1)))
        assertTrue(baidu.sameAs(baidu.copy(key = "#WZ", folder = "x")))
        assertFalse(baidu.sameAs(baidu.copy(note = "新浪")))
    }

    // the code review of 060-quick: a file of now written again by a build before 0.6.0 (a downgrade,
    // an edit there, an upgrade back) has no version line but keeps its bars
    @Test
    @Uat("QU-27")
    fun aFileOfNowABuildBeforeWroteAgainReadsAsItWasMeantNotWithBarsPiledUp() {
        val text = QuickPhraseFiles.formatMine(listOf(baidu, sina, home), listOf("网址"))
        // what 0.5.x writes back: its own entries, `key value`, no version or folder lines
        val rewritten = QuickPhraseTable.parse(text.toByteArray()).joinToString("") { "${it.first} ${FcitxUtils.escapeForValue(it.second)}\n" }
        val back = QuickPhraseFiles.readMine(rewritten.toByteArray())
        assertEquals(listOf(baidu, home, sina.copy(folder = "")), back.entries)
        assertEquals(text.replace("【目录】网址\n", ""), QuickPhraseFiles.formatMine(back.entries, back.folders))
    }

    @Test
    @Uat("QU-27")
    fun aLineWithNeitherKeyNorNoteIsNoEntryReadOrImported() {
        val file = (QuickPhraseFiles.HEADER + "\n｜ 内容 有空格\n｜ 内容\nx｜ y\n").toByteArray()
        assertEquals(listOf(QuickPhraseEntry("x", "y")), QuickPhraseFiles.readMine(file).entries)
        assertEquals(listOf(QuickPhraseEntry("x", "y")), QuickPhraseFiles.parse("｜ 内容\nx｜ y\n".toByteArray()).entries)
        // one made elsewhere and merged all the same: skipped as a bad key, never written as ` 内容`
        val merged = QuickPhraseFiles.merge(emptyList(), listOf(QuickPhraseEntry("", "内容"), home))
        assertEquals(1, merged.badKey)
        assertEquals(listOf(home), merged.entries)
        assertEquals(emptyMap<QuickPhraseEntry, Long>(), QuickPhraseFiles.parseUsed(QuickPhraseFiles.USED_HEADER + "\n5\t｜\tx\n"))
    }

    @Test
    @Uat("QU-26", "QU-27")
    fun version2ReadsCrLfLinesABomAndBarsOrFolderMarksInsideContent() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val crlf = QuickPhraseFiles.HEADER + "\r\n【目录】网址\r\n#wz｜百度 www.baidu.com\r\n"
        for (bytes in listOf(crlf.toByteArray(), bom + crlf.toByteArray())) {
            val read = QuickPhraseFiles.parse(bytes)
            assertTrue(read.v2)
            assertEquals(listOf("网址"), read.folders)
            assertEquals(listOf(baidu.copy(folder = "网址")), read.entries)
        }
        // a bar and a folder's mark in the content are the content's: written and read back as they were
        val inside = listOf(QuickPhraseEntry("a", "x｜y【目录】z", "注"), QuickPhraseEntry("b", "【目录】网址"), QuickPhraseEntry("c", "｜"))
        val text = QuickPhraseFiles.formatMine(inside)
        assertEquals(QuickPhraseMine(inside, emptyList()), QuickPhraseFiles.readMine(text.toByteArray()))
        // a folder line of a name that cannot be one (a shelf's): its entries in none
        val shelf = QuickPhraseFiles.readMine((QuickPhraseFiles.HEADER + "\n【目录】全部\nk v\n").toByteArray())
        assertEquals(QuickPhraseMine(listOf(QuickPhraseEntry("k", "v")), emptyList()), shelf)
    }

    @Test
    @Uat("QU-25")
    fun aNotesTabsAndNoBreakSpacesAreSpacesSoItReadsBackAsItIsWritten() {
        assertEquals("a b c 首页", QuickPhraseFiles.note(" a\tb\u00a0c\n首页 "))
        val entry = QuickPhraseEntry("w", "x", QuickPhraseFiles.note("工作\t邮箱\u00a0旧"), folder = QuickPhraseFiles.note("目录\u00a0一"))
        assertTrue(QuickPhraseFiles.validNote(entry.note))
        assertTrue(QuickPhraseFiles.validFolder(entry.folder))
        val text = QuickPhraseFiles.formatMine(listOf(entry), listOf(entry.folder))
        assertEquals(QuickPhraseMine(listOf(entry), listOf("目录 一")), QuickPhraseFiles.readMine(text.toByteArray()))
        // its time found again by the same note
        assertEquals(mapOf(entry.unfiled to 3L), QuickPhraseFiles.parseUsed(QuickPhraseFiles.formatUsed(mapOf(entry.unfiled to 3L), listOf(entry))))
    }

    @Test
    @Uat("QU-27")
    fun version2KeepsTheContentLimitAndEachEntryOnce() {
        val limit = "x".repeat(QuickPhraseBook.MAX_CONTENT)
        val text = QuickPhraseFiles.HEADER + "\n" +
            "a $limit\nb ${limit}y\n" +
            "#wz｜百度 x\n#WZ｜百度 x\n#wz｜新浪 x\n#wz x\n"
        val read = QuickPhraseFiles.readMine(text.toByteArray()).entries
        assertEquals(
            listOf(QuickPhraseEntry("a", limit), QuickPhraseEntry("#wz", "x", "百度"), QuickPhraseEntry("#wz", "x", "新浪"), QuickPhraseEntry("#wz", "x")),
            read,
        )
        val merged = QuickPhraseFiles.merge(listOf(QuickPhraseEntry("#WZ", "x", "百度")), QuickPhraseFiles.parse(text.toByteArray()).entries)
        assertEquals(1, merged.tooLong)
        assertEquals(2, merged.duplicates)
        assertEquals(4, merged.entries.size)
    }
}
