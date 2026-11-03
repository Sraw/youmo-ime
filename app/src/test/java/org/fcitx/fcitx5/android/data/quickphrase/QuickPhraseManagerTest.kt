/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.host.QuickPhraseEntry
import org.fcitx.fcitx5.android.host.QuickPhraseFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val file = QuickPhraseFiles.formatMine(listOf(gh, addr)) + "bad\n"
        assertEquals(1 to 1, QuickPhraseManager.import(file.toByteArray(), dir = dir))
        assertEquals(setOf(gh, addr), QuickPhraseManager.load(dir).toSet())
        assertEquals(0 to 2, QuickPhraseManager.import(file.toByteArray(), dir = dir))
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
}
