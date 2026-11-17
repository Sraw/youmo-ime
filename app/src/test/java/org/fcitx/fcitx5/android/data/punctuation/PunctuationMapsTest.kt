/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PunctuationMapsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val builtIn = ". 。\n, ，\n\" “ ”\n"
    private fun maps() = PunctuationMaps(tmp.root) { lang -> if (lang == "zh_CN") builtIn.byteInputStream() else null }

    @Test
    fun `no user file, the built-in table`() {
        assertEquals(
            listOf(PunctuationMapEntry(".", "。", ""), PunctuationMapEntry(",", "，", ""), PunctuationMapEntry("\"", "“", "”")),
            maps().load("zh_CN"),
        )
        assertEquals(emptyList<PunctuationMapEntry>(), maps().load("zh_TW"))
    }

    // A4: a table the user edited that does not read is the built-in one on the keyboard, the user told,
    // the file as it was (nothing writes a table since 0.6.1: R26 F8279)
    @Test
    fun `a user file that cannot be read is told and kept`() {
        val file = File(tmp.root, "punctuation/punc.mb.zh_CN")
        file.parentFile!!.mkdirs()
        file.writeText(", 、\n")
        assumeTrue("running as root: a file cannot be made unreadable", file.setReadable(false) && !file.canRead())
        val told = mutableListOf<String>()
        val maps = PunctuationMaps(tmp.root, onUnreadable = { told += it }) { lang -> if (lang == "zh_CN") builtIn.byteInputStream() else null }
        assertEquals(3, maps.load("zh_CN").size)
        assertEquals(listOf("zh_CN"), told)
        file.setReadable(true)
        assertEquals(", 、\n", file.readText())
    }

    @Test
    fun `with no data directory the built-in table`() {
        assertEquals(3, PunctuationMaps(null) { builtIn.byteInputStream() }.load("zh_CN").size)
    }

    @Test
    fun `a user file replaces the built-in table whole`() {
        // punctuation.cpp:410-430: the user's file is loaded over the system one, which load() clears
        File(tmp.root, "punctuation").mkdirs()
        File(tmp.root, "punctuation/punc.mb.zh_CN").writeText(", 、\n")
        assertEquals(listOf(PunctuationMapEntry(",", "、", "")), maps().load("zh_CN"))
        assertEquals(3, maps().builtIn("zh_CN").size)
    }

    @Test
    fun `a user file that cannot be read leaves the built-in table`() {
        // F3903: an unreadable file is not "no punctuation"
        val file = File(tmp.root, "punctuation/punc.mb.zh_CN").apply { parentFile!!.mkdirs(); writeText(", 、\n") }
        file.setReadable(false)
        assumeFalse("root reads it anyway", file.canRead())
        assertEquals(3, maps().load("zh_CN").size)
    }

    // what the upgrade's reset and a restore go by: done only where no table is left, and a directory that
    // cannot be listed is not taken for an empty one
    @Test
    fun `dropping the edited tables says whether none is left`() {
        val data = tmp.newFolder("data")
        assertTrue(PunctuationMaps.dropEdited(data))
        val dir = File(data, PunctuationMaps.DIR)
        dir.writeText("not a directory")
        assertFalse(PunctuationMaps.dropEdited(data))
        dir.delete()
        dir.mkdirs()
        File(dir, "punc.mb.zh_CN").writeText(", 、\n")
        File(dir, ".libime").writeText("kept")
        assertTrue(PunctuationMaps.dropEdited(data))
        assertEquals(listOf(".libime"), dir.list()!!.toList())
    }
}
