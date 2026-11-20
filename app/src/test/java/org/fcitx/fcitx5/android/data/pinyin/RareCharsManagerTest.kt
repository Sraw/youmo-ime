/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import org.fcitx.fcitx5.android.engine.data.TestCharLevels
import org.fcitx.fcitx5.android.engine.user.RareChoice
import org.fcitx.fcitx5.android.engine.user.RareChoice.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Read, changes laid over it as it is, never written over what cannot be read. */
class RareCharsManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val levels = TestCharLevels.levels
    private val file get() = File(folder.root, "pinyin/rarechars.txt")

    @Test
    fun noFileIsOffAndTheFirstTurnOnHoldsExtensionB() = assertRows(
        "no file: off" to { assertEquals(RareChoice.NONE, RareCharsManager.load(file)) },
        "turned on: on, 扩展区 held, written" to {
            val made = RareCharsManager.update(listOf(Change.On(true)), levels, file)
            assertTrue(made.on)
            assertEquals(setOf(Level.EXT), made.groups)
            assertEquals("# youmo-rare-chars 1\non\t1\ngroups\tEXT\nallow\t\nblock\t\n", file.readText())
            assertEquals(made, RareCharsManager.load(file))
            // the write done through a file beside it: nothing of it left
            assertEquals(listOf("rarechars.txt"), file.parentFile!!.list()!!.toList())
        },
    )

    // the page and a user word's 「放行」 one after the other: each change laid over what the other wrote
    @Test
    fun twoWritersOneAfterTheOtherBothStay() {
        RareCharsManager.update(listOf(Change.On(true)), levels, file)
        RareCharsManager.update(listOf(Change.One(TestCharLevels.EXT, false)), levels, file)
        val saved = RareCharsManager.update(listOf(Change.One(TestCharLevels.OUT, true)), levels, file)
        assertEquals(setOf(TestCharLevels.EXT), saved.allow)
        assertEquals(setOf(TestCharLevels.OUT), saved.block)
    }

    @Test
    fun aLaterVersionsFileIsNotSavedOver() {
        val later = "# youmo-rare-chars 2\non\t1\ngroups\tEXT NEW\n"
        file.parentFile!!.mkdirs()
        file.writeText(later)
        val read = RareCharsManager.load(file)
        assertTrue(read.newer)
        assertFalse("a later version's is off here", read.gate(levels).holds(TestCharLevels.EXT))
        assertThrows(RareCharsManager.Newer::class.java) { RareCharsManager.update(listOf(Change.On(false)), levels, file) }
        assertEquals(later, file.readText())
    }

    // what cannot be read is not written over with the default
    @Test
    fun aFileThatCannotBeReadIsNotWrittenOver() {
        // a directory where the file is: reading it fails as storage gone would
        file.mkdirs()
        File(file, "x").writeText("theirs")
        assertThrows(IOException::class.java) { RareCharsManager.update(listOf(Change.On(true)), levels, file) }
        assertEquals("theirs", File(file, "x").readText())
    }

    @Test
    fun unknownLinesAndGroupsStayButResetClearsThem() {
        file.parentFile!!.mkdirs()
        file.writeText("# youmo-rare-chars 1\non\t1\ngroups\tEXT FUTURE\nlater\tthing\n")
        val kept = RareCharsManager.update(listOf(Change.One(TestCharLevels.OUT, true)), levels, file)
        assertTrue(file.readText(), "groups\tEXT FUTURE\n" in file.readText() && "later\tthing\n" in file.readText())
        assertEquals(setOf(TestCharLevels.OUT), kept.block)
        RareCharsManager.update(listOf(Change.Reset), levels, file)
        assertEquals("# youmo-rare-chars 1\non\t1\ngroups\tEXT\nallow\t\nblock\t\n", file.readText())
    }
}
