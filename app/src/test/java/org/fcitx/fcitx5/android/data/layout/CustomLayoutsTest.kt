/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.layout

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.layout.CustomLayoutFiles
import org.fcitx.fcitx5.android.input.layout.LayoutImport.Verdict
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** The user's own layouts as files (061-custom-layout §4): import, list, export, delete, restore. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class CustomLayoutsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "layouts") }

    @After
    fun forget() = CustomLayouts.forgetAll()

    // the app's board [base] made a layout of one's own [id], [name] and <meta version> if given
    private fun own(id: String, base: String = "qwerty", name: String? = null, version: String? = null): String {
        var t = BuiltInLayouts(appContext).text(base)!!.replaceFirst("id=\"$base\"", "id=\"$id\" based-on=\"$base\"")
        if (name != null) t = t.replaceFirst(Regex("name=\"[^\"]*\""), "name=\"$name\"")
        if (version != null) t = t.replaceFirst(Regex("(<keyboard[^>]*>)"), "$1<meta version=\"$version\"/>")
        return t
    }

    private fun open(text: String) = CustomLayouts.open(text.byteInputStream(), "0.6.1", null, dir)

    @Test
    fun aLayoutImportedIsListedInItsFamilyAndReplacedByItsId() {
        val first = open(own("my17", name = "17 键", version = "1.1")) as CustomLayouts.Opened.Judged
        val ok = first.verdict as Verdict.Ok
        assertNull(ok.replaces)
        CustomLayouts.save(ok.layout.id, first.text, dir)
        assertEquals(listOf("my17.xml"), dir.list()!!.toList())
        val listed = CustomLayouts.list(dir).single()
        assertEquals("17 键", listed.name)
        assertEquals("letters", listed.family)
        assertTrue(listed.cells.isNotEmpty())
        // again, a newer version: what it replaces said
        val again = open(own("my17", name = "17 键", version = "1.2")) as CustomLayouts.Opened.Judged
        val replaces = (again.verdict as Verdict.Ok).replaces!!
        assertEquals("1.1", replaces.version)
        CustomLayouts.save("my17", again.text, dir)
        assertEquals("1.2", CustomLayouts.list(dir).single().layout!!.meta!!.version)
        assertEquals(1, dir.list()!!.size)
    }

    @Test
    fun aFileTooBigOrNotTextOrWrongIsRefusedAndNothingKept() {
        val big = own("big").replace("</keyboard>", "<!--" + "x".repeat(CustomLayoutFiles.MAX_BYTES.toInt()) + "--></keyboard>")
        assertSame(CustomLayouts.Opened.TooBig, open(big))
        assertSame(CustomLayouts.Opened.NotText, CustomLayouts.open(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47).inputStream(), "0.6.1", null, dir))
        val refused = (open("<keyboard") as CustomLayouts.Opened.Judged).verdict as Verdict.Refused
        assertEquals("read", refused.errors.first().key)
        // a DOCTYPE (an entity bomb's way in) is not read
        val doctype = "<?xml version=\"1.0\"?><!DOCTYPE keyboard [<!ENTITY a \"aaaa\">]>" + own("dt").substringAfter("?>")
        assertTrue((open(doctype) as CustomLayouts.Opened.Judged).verdict is Verdict.Refused)
        assertTrue((open(own("../evil")) as CustomLayouts.Opened.Judged).verdict is Verdict.Refused)
        assertFalse(dir.exists() && dir.list()!!.isNotEmpty())
    }

    @Test
    fun theListReadsEachFileOnceTillItChangesAndLeavesNoHalfWrittenOne() {
        CustomLayouts.save("a", own("a"), dir)
        // one a kill left, and one being written (younger than a minute: kept)
        File(dir, "x.saving").apply { writeText("half") }.setLastModified(System.currentTimeMillis() - 120_000)
        File(dir, "y.saving").writeText("being written")
        File(dir, "notes.txt").writeText("not a layout")
        // a file renamed by hand: its id not its name's, listed as one that does not read (to be deleted)
        File(dir, "b.xml").writeText(own("c"))
        val first = CustomLayouts.list(dir)
        assertFalse(File(dir, "x.saving").exists())
        assertTrue(File(dir, "y.saving").exists())
        assertEquals(listOf("a", "b"), first.map { it.id })
        assertNull(first[1].layout)
        assertSame(first[0], CustomLayouts.list(dir)[0])
        CustomLayouts.save("a", own("a", name = "改了"), dir)
        assertEquals("改了", CustomLayouts.list(dir).single { it.id == "a" }.name)
        assertTrue(CustomLayouts.delete("b", dir))
        assertEquals(listOf("a"), CustomLayouts.list(dir).map { it.id })
    }

    @Test
    fun anExportIsTheFileAsItIs() {
        val text = "﻿" + own("mine")
        CustomLayouts.save("mine", text, dir)
        val out = ByteArrayOutputStream()
        CustomLayouts.export("mine", out, dir)
        assertEquals(text, out.toByteArray().decodeToString())
    }

    @Test
    fun aRestoreReplacesTheLayoutsItHasKeepsThePhonesOthersAndMergesTheRecord() {
        CustomLayouts.save("kept", own("kept"), dir)
        CustomLayouts.save("same", own("same", name = "手机的"), dir)
        File(dir, CustomLayoutFiles.CONVERTED).writeText("p@1\n")
        val backup = File(folder.root, "backup").apply { mkdirs() }
        File(backup, "same.xml").writeText(own("same", name = "备份的"))
        File(backup, "new.xml").writeText(own("new"))
        // not taken: renamed, not read, not a layout's name, too big
        File(backup, "renamed.xml").writeText(own("other"))
        File(backup, "broken.xml").writeText("<keyboard")
        File(backup, "evil.txt").writeText(own("evil"))
        File(backup, "huge.xml").writeText(own("huge").replace("</keyboard>", "<!--" + "x".repeat(CustomLayoutFiles.MAX_BYTES.toInt()) + "--></keyboard>"))
        File(backup, CustomLayoutFiles.CONVERTED).writeText("q@2\np@1\n")
        CustomLayouts.restore(backup, dir, "0.6.1")
        val listed = CustomLayouts.list(dir)
        assertEquals(listOf("kept", "new", "same"), listed.map { it.id }.sorted())
        assertEquals("备份的", listed.single { it.id == "same" }.name)
        assertEquals("p@1\nq@2\n", File(dir, CustomLayoutFiles.CONVERTED).readText())
        // a backup of none (0.6.0's): the phone's as they were
        CustomLayouts.restore(File(folder.root, "none"), dir, "0.6.1")
        assertEquals(3, CustomLayouts.list(dir).size)
    }

    @Test
    fun aRestoreKeepsThePhoneWithinTheMostLayoutsItsOwnFirst() {
        val max = CustomLayoutFiles.MAX_LAYOUTS
        val text = own("p0")
        // the phone full but one: its files written as they are (a save each would check nothing more)
        dir.mkdirs()
        (1 until max).forEach { File(dir, "p$it.xml").writeText(text.replaceFirst("id=\"p0\"", "id=\"p$it\"")) }
        val backup = File(folder.root, "backup").apply { mkdirs() }
        // one of the phone's ids (replaced, room or not), then new ones: only the first new one has room
        File(backup, "p1.xml").writeText(own("p1", name = "备份的"))
        listOf("q1", "q2", "q3").forEach { File(backup, "$it.xml").writeText(own(it)) }
        CustomLayouts.restore(backup, dir, "0.6.1")
        val ids = dir.list()!!.mapNotNull(CustomLayoutFiles::idOf).toSet()
        assertEquals(max, ids.size)
        assertTrue("q1" in ids && "q2" !in ids && "q3" !in ids)
        assertEquals("备份的", CustomLayouts.list(dir).single { it.id == "p1" }.name)
    }

    @Test
    fun aRestoreOfTheRecordAloneMakesTheFolderAndOneThatCannotFailsItself() {
        // the phone has no layouts' folder; the backup only the record
        val backup = File(folder.root, "backup").apply { mkdirs() }
        File(backup, CustomLayoutFiles.CONVERTED).writeText("p@1\n")
        CustomLayouts.restore(backup, dir, "0.6.1")
        assertEquals("p@1\n", File(dir, CustomLayoutFiles.CONVERTED).readText())
        // where the folder cannot be made (a file in its place): an IOException, for the import to log and go past
        val blocked = File(folder.root, "blocked").apply { writeText("not a folder") }
        assertTrue(runCatching { CustomLayouts.restore(backup, blocked, "0.6.1") }.exceptionOrNull() is IOException)
    }

    @Test
    fun aLayoutsNameIsShownWithoutWhatIsNotShown() {
        dir.mkdirs()
        // as a restore takes it (not strict): a direction override in its name
        File(dir, "rtl.xml").writeText(own("rtl", name = "a&#x202E;b"))
        assertEquals("ab", CustomLayouts.list(dir).single().name)
        File(dir, "rtl.xml").writeText(own("rtl", name = "&#x202E;"))
        assertEquals("rtl", CustomLayouts.list(dir).single().name)
    }

    @Test
    fun aNumberAndASymbolsLayoutAreListedInTheirFamilies() {
        CustomLayouts.save("big-num", own("big-num", "number"), dir)
        CustomLayouts.save("my-sym", own("my-sym", "symbols"), dir)
        CustomLayouts.save("my-k18", own("my-k18", "k18"), dir)
        assertEquals(mapOf("big-num" to "number", "my-sym" to "symbols", "my-k18" to "grouped"), CustomLayouts.list(dir).associate { it.id to it.family })
        // the grouped board's bottom row is the app's fragment it includes
        assertEquals(setOf("grouped-bottom"), CustomLayouts.list(dir).single { it.id == "my-k18" }.fragments.keys)
    }
}
