/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.fcitx.fcitx5.android.engine.platform.jvm.replaceFile
import java.io.File
import java.io.IOException

/** A file replaced whole or not at all, with nothing left beside it. */
class ReplaceFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun names() = folder.root.list()!!.sorted()

    @Test
    fun aFileIsReplacedByWhatWasWritten() {
        val dest = File(folder.root, "a").apply { writeText("old") }
        replaceFile(dest, File(folder.root, "a.new")) {
            it.writeText("new")
        }
        assertEquals("new", dest.readText())
        assertEquals(listOf("a"), names())
    }

    @Test
    fun aWriteThatFailsOrDeclinesLeavesTheFileAsItWas() {
        val dest = File(folder.root, "a").apply { writeText("old") }
        val temp = File(folder.root, "a.new")
        assertThrows(IOException::class.java) {
            replaceFile(dest, temp) {
                it.writeText("half")
                throw IOException("disk full")
            }
        }
        fun decline() {
            replaceFile(dest, temp) {
                it.writeText("nothing to keep")
                return
            }
        }
        decline()
        // a directory where the file goes: the rename fails
        val dir = File(folder.root, "d").apply { mkdir() }
        assertThrows(IOException::class.java) { replaceFile(dir, temp) { it.writeText("x") } }
        assertEquals("old", dest.readText())
        assertEquals(listOf("a", "d"), names())
    }

    @Test
    fun aWriteThatMakesNoFileLeavesTheFileAsItWas() {
        val dest = File(folder.root, "a").apply { writeText("old") }
        assertThrows(IOException::class.java) { replaceFile(dest, File(folder.root, "a.new")) {} }
        assertEquals("old", dest.readText())
        assertEquals(listOf("a"), names())
    }
}
