/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.host.QuickPhraseBook
import org.fcitx.fcitx5.android.host.QuickPhraseEntry
import org.fcitx.fcitx5.android.host.QuickPhraseFiles
import org.fcitx.fcitx5.android.host.QuickPhraseMine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The files of the user's quick phrases (design 051-quickphrase §3.1): each change read, made, written whole. */
class QuickPhraseManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dir by lazy { File(tmp.root, "quickphrase.d") }
    private val gh = QuickPhraseEntry("gh", "https://github.com")
    private val addr = QuickPhraseEntry("addr", "北京\n3 号楼 \"502\"")

    @Test
    @Uat("QU-15")
    fun putChangeAndRemoveRoundTripThroughTheFiles() {
        assertEquals(QuickPhraseManager.Put.Saved, QuickPhraseManager.put(null, gh, now = 1, dir = dir))
        QuickPhraseManager.put(null, addr, now = 2, dir = dir)
        assertEquals(listOf(addr, gh), QuickPhraseManager.load(dir))
        assertEquals(QuickPhraseManager.Put.Duplicate, QuickPhraseManager.put(null, gh, dir = dir))
        val moved = gh.copy(content = "https://github.com/Sraw")
        QuickPhraseManager.put(gh, moved, now = 3, dir = dir)
        assertEquals(listOf(moved, addr), QuickPhraseManager.load(dir))
        // the changed one's old time is gone with it
        assertEquals(setOf(moved, addr), QuickPhraseFiles.parseUsed(File(dir, "mine.used").readText()).keys)
        QuickPhraseManager.remove(addr, dir = dir)
        assertEquals(listOf(moved), QuickPhraseManager.load(dir))
        assertEquals(QuickPhraseFiles.formatMine(listOf(moved)), QuickPhraseManager.export(dir))
    }

    @Test
    @Uat("QU-15")
    fun usedFromTheKeyboardReordersButAnEntryDeletedMeanwhileStaysGone() {
        QuickPhraseManager.put(null, gh, now = 1, dir = dir)
        QuickPhraseManager.put(null, addr, now = 2, dir = dir)
        QuickPhraseManager.markUsed(gh, 5, dir = dir)
        assertEquals(listOf(gh, addr), QuickPhraseManager.load(dir))
        // the page deleted it before the keyboard's use was written
        QuickPhraseManager.remove(gh, dir = dir)
        QuickPhraseManager.markUsed(gh, 9, dir = dir)
        assertEquals(listOf(addr), QuickPhraseManager.load(dir))
        // and its old card's edit adds it back rather than losing it
        QuickPhraseManager.put(gh, gh.copy(content = "x"), now = 10, dir = dir)
        assertEquals(listOf(gh.copy(content = "x"), addr), QuickPhraseManager.load(dir))
    }

    @Test
    @Uat("QU-15")
    fun importAddsNewOnesAndCountsTheSkipped() {
        QuickPhraseManager.put(null, gh, now = 1, dir = dir)
        val long = QuickPhraseEntry("long", "x".repeat(QuickPhraseBook.MAX_CONTENT + 1))
        // a key over MAX_KEY cannot be one: counted (so the toast can say it), not dropped unsaid
        val badKey = "k".repeat(QuickPhraseBook.MAX_KEY + 1) + " x\n"
        val file = QuickPhraseFiles.formatMine(listOf(gh, addr, long)) + "bad\n" + badKey
        assertEquals(QuickPhraseManager.Imported(1, 1, 1, badKey = 1), QuickPhraseManager.import(file.toByteArray(), dir = dir))
        assertEquals(setOf(gh, addr), QuickPhraseManager.load(dir).toSet())
        assertEquals(QuickPhraseManager.Imported(0, 2, 1, badKey = 1), QuickPhraseManager.import(file.toByteArray(), dir = dir))
        // a file of only bad keys is told as such, not as "not a .mb file"
        assertEquals(QuickPhraseManager.Imported(0, 0, 0, badKey = 1), QuickPhraseManager.import(badKey.toByteArray(), dir = dir))
    }

    // review L11: a key in another case is found by the same keys, so it is the same entry
    @Test
    fun aKeyInAnotherCaseIsADuplicateButAnEntrysOwnCaseMayChange() {
        QuickPhraseManager.put(null, gh, now = 1, dir = dir)
        assertEquals(QuickPhraseManager.Put.Duplicate, QuickPhraseManager.put(null, gh.copy(key = "GH"), dir = dir))
        assertEquals(QuickPhraseManager.Put.Saved, QuickPhraseManager.put(gh, gh.copy(key = "Gh"), now = 2, dir = dir))
        assertEquals(listOf(gh.copy(key = "Gh")), QuickPhraseManager.load(dir))
    }

    @Test
    @Uat("QU-15")
    fun builtInTablesTurnOffAndOn() {
        assertTrue(QuickPhraseManager.builtInOn("latex.mb", dir))
        QuickPhraseManager.setBuiltIn("latex.mb", false, dir)
        assertFalse(QuickPhraseManager.builtInOn("latex.mb", dir))
        QuickPhraseManager.setBuiltIn("latex.mb", true, dir)
        assertTrue(QuickPhraseManager.builtInOn("latex.mb", dir))
    }

    @Test
    @Uat("QU-15", "QU-17")
    fun aDeletionUndoneIsPutBackWhereItWasWithItsTimeOfUse() {
        QuickPhraseManager.put(null, gh, now = 1, dir = dir)
        QuickPhraseManager.put(null, addr, now = 2, dir = dir)
        val third = QuickPhraseEntry("zz", "a third")
        QuickPhraseManager.put(null, third, dir = dir)
        // the file's own order, which load() (by time of use) hides: put back at its index, not
        // appended last (an undo once added it at the end)
        fun inFile() = QuickPhraseFiles.parseMine(File(dir, "mine.mb").readBytes())
        val before = inFile()
        val removed = QuickPhraseManager.remove(gh, dir = dir)!!
        assertNull(QuickPhraseManager.remove(gh, dir = dir))
        assertEquals(QuickPhraseManager.Put.Saved, QuickPhraseManager.restore(removed, dir = dir))
        assertEquals(before, inFile())
        QuickPhraseManager.remove(third, dir = dir)
        // its old time of use: after addr, not last as never used
        assertEquals(listOf(addr, gh), QuickPhraseManager.load(dir))
        // one the same added meanwhile is kept as it is
        val again = QuickPhraseManager.remove(gh, dir = dir)!!
        QuickPhraseManager.put(null, gh, now = 5, dir = dir)
        assertEquals(QuickPhraseManager.Put.Duplicate, QuickPhraseManager.restore(again, dir = dir))
    }

    // design 060-quick-input-folders: an upgrade's files of before read converted, their times kept,
    // written as version 2 at the next change
    @Test
    @Uat("QU-27")
    fun filesOfBeforeAreReadConvertedAndWrittenAnewAtTheNextChange() {
        dir.mkdirs()
        File(dir, "mine.mb").writeText("#wz百度 www.baidu.com\n#wz新浪 www.sina.com.cn\n地址 北京\n")
        File(dir, "mine.used").writeText("5\t#wz新浪\twww.sina.com.cn\n")
        val baidu = QuickPhraseEntry("#wz", "www.baidu.com", "百度")
        val sina = QuickPhraseEntry("#wz", "www.sina.com.cn", "新浪")
        val home = QuickPhraseEntry("", "北京", "地址")
        assertEquals(listOf(sina, baidu, home), QuickPhraseManager.load(dir))
        // nothing written by a read
        assertFalse(File(dir, "mine.mb").readText().startsWith(QuickPhraseFiles.HEADER))
        QuickPhraseManager.put(home, home.copy(key = "dz"), now = 3, dir = dir)
        assertTrue(File(dir, "mine.mb").readText().startsWith(QuickPhraseFiles.HEADER + "\n#wz｜百度 www.baidu.com\n"))
        assertTrue(File(dir, "mine.used").readText().startsWith(QuickPhraseFiles.USED_HEADER + "\n"))
        assertEquals(listOf(sina, home.copy(key = "dz"), baidu), QuickPhraseManager.load(dir))
        // the keyboard's use of before the write is still found
        QuickPhraseManager.markUsed(baidu, 9, dir = dir)
        assertEquals(baidu, QuickPhraseManager.load(dir).first())
    }

    @Test
    @Uat("QU-26")
    fun foldersAreMadeRenamedAndDeletedWithNoEntryLostAndAMoveKeepsItsTime() {
        assertTrue(QuickPhraseManager.addFolder("网址", dir = dir))
        assertFalse(QuickPhraseManager.addFolder("网址", dir = dir))
        assertTrue(QuickPhraseManager.addFolder("空的", dir = dir))
        val filed = gh.copy(folder = "网址")
        QuickPhraseManager.put(null, filed, now = 1, dir = dir)
        QuickPhraseManager.put(null, addr, now = 2, dir = dir)
        assertEquals(QuickPhraseMine(listOf(addr, filed), listOf("网址", "空的")), QuickPhraseManager.loadAll(dir))
        // the keyboard's, of a read before a move: used and deleted all the same
        QuickPhraseManager.put(filed, gh, now = 1, dir = dir)
        QuickPhraseManager.markUsed(filed, 5, dir = dir)
        assertEquals(listOf(gh, addr), QuickPhraseManager.load(dir))
        QuickPhraseManager.put(gh, filed, now = 5, dir = dir)
        assertTrue(QuickPhraseManager.renameFolder("网址", "站点", dir = dir))
        assertFalse(QuickPhraseManager.renameFolder("网址", "x", dir = dir))
        assertFalse(QuickPhraseManager.renameFolder("站点", "空的", dir = dir))
        assertEquals(QuickPhraseMine(listOf(gh.copy(folder = "站点"), addr), listOf("站点", "空的")), QuickPhraseManager.loadAll(dir))
        assertEquals(1, QuickPhraseManager.deleteFolder("站点", dir = dir))
        assertEquals(-1, QuickPhraseManager.deleteFolder("站点", dir = dir))
        assertEquals(QuickPhraseMine(listOf(gh, addr), listOf("空的")), QuickPhraseManager.loadAll(dir))
        // deleted from the keyboard with its old folder, undone into it, made again
        val removed = QuickPhraseManager.remove(filed, dir = dir)!!
        assertEquals(gh, removed.entry)
        assertEquals(5L, removed.usedAt)
        // the export keeps folders, a computer's fcitx5 reads its keys
        QuickPhraseManager.put(null, QuickPhraseEntry("#wz", "www.baidu.com", "百度", "网址"), now = 7, dir = dir)
        assertEquals(
            QuickPhraseFiles.HEADER + "\n" + "addr \"北京\\n3 号楼 \\\"502\\\"\"\n" + "【目录】空的\n【目录】网址\n#wz｜百度 www.baidu.com\n",
            QuickPhraseManager.export(dir),
        )
    }

    @Test
    @Uat("QU-27")
    fun anImportOfBeforeOrAComputersIsConvertedIntoItsFoldersKeylessOnesCounted() {
        QuickPhraseManager.addFolder("网址", dir = dir)
        val file = "#wz百度 www.baidu.com\n地址 北京\n【目录】网址\ngh https://github.com\n"
        assertEquals(QuickPhraseManager.Imported(3, 0, 0, keyless = 1), QuickPhraseManager.import(file.toByteArray(), dir = dir))
        assertEquals(
            QuickPhraseMine(
                listOf(QuickPhraseEntry("#wz", "www.baidu.com", "百度"), QuickPhraseEntry("", "北京", "地址"), gh.copy(folder = "网址")),
                listOf("网址"),
            ),
            QuickPhraseManager.loadAll(dir),
        )
        // our own export imported again: all there already
        assertEquals(QuickPhraseManager.Imported(0, 3, 0), QuickPhraseManager.import(QuickPhraseManager.export(dir).toByteArray(), dir = dir))
    }
}
