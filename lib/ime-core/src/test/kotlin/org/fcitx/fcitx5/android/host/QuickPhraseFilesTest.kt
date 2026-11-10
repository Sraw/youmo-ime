/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.Uat
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
        assertEquals("a1-" to "中b c", QuickPhraseFiles.split("a1-中b c", v2 = false))
        assertEquals("abc" to "", QuickPhraseFiles.split("abc", v2 = false))
        assertEquals("a" to "｜x", QuickPhraseFiles.split("a｜x", v2 = false))
        assertEquals("a" to "x y", QuickPhraseFiles.split("a｜x y", v2 = true))
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
        assertTrue(QuickPhraseFiles.validFolder("网址"))
        assertFalse(QuickPhraseFiles.validFolder(" 网址"))
        assertFalse(QuickPhraseFiles.validFolder(""))
        assertFalse(QuickPhraseFiles.validFolder("长".repeat(QuickPhraseBook.MAX_FOLDER + 1)))
        assertTrue(baidu.sameAs(baidu.copy(key = "#WZ", folder = "x")))
        assertFalse(baidu.sameAs(baidu.copy(note = "新浪")))
    }
}
