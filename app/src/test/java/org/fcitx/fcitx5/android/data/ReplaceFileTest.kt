/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** A file or directory replaced whole or not at all, with nothing left beside it. */
class ReplaceFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun names() = folder.root.list()!!.sorted()

    @Test
    fun aFileIsReplacedByWhatWasWritten() {
        val dest = File(folder.root, "a").apply { writeText("old") }
        replaceFile(dest, File(folder.root, "a.new")) {
            it.writeText("new")
            true
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
        replaceFile(dest, temp) {
            it.writeText("nothing to keep")
            false
        }
        // a directory where the file goes: the rename fails
        val dir = File(folder.root, "d").apply { mkdir() }
        assertThrows(IOException::class.java) { replaceFile(dir, temp) { it.writeText("x"); true } }
        assertEquals("old", dest.readText())
        assertEquals(listOf("a", "d"), names())
    }

    @Test
    fun aDirectoryIsReplacedByWhatWasStaged() {
        val dest = File(folder.root, "engine").apply { mkdir() }
        File(dest, "log").writeText("old")
        File(dest, "kept").writeText("kept")
        replaceDirectory(dest, File(folder.root, "engine.import")) { staged ->
            dest.copyRecursively(staged)
            File(staged, "log").writeText("new")
        }
        assertEquals("new", File(dest, "log").readText())
        assertEquals("kept", File(dest, "kept").readText())
        assertEquals(listOf("engine"), names())
    }

    @Test
    fun aDirectoryNotFullyStagedIsLeftAsItWas() {
        val dest = File(folder.root, "engine").apply { mkdir() }
        File(dest, "log").writeText("old")
        assertThrows(NoSuchFileException::class.java) {
            replaceDirectory(dest, File(folder.root, "engine.import")) { staged ->
                dest.copyRecursively(staged)
                File(folder.root, "missing").copyRecursively(staged, overwrite = true)
            }
        }
        assertEquals("old", File(dest, "log").readText())
        assertEquals(listOf("engine"), names())
    }

    @Test
    fun aDirectoryLeftAsideByAReplaceKilledBetweenItsRenamesIsPutBackNotDeleted() {
        val dest = File(folder.root, "engine")
        val staged = File(folder.root, "engine.import")
        // as a kill after `dest` was moved aside left them
        File(folder.root, "engine.old").apply { mkdir() }.resolve("log").writeText("learned")
        staged.mkdir()
        restoreDirectory(dest)
        assertEquals("learned", File(dest, "log").readText())
        assertEquals(listOf("engine", "engine.import"), names())
        // the next import starts from it rather than deleting it
        dest.renameTo(File(folder.root, "engine.old"))
        replaceDirectory(dest, staged) { s ->
            if (dest.isDirectory) dest.copyRecursively(s)
            File(s, "imported").writeText("new")
        }
        assertEquals("learned", File(dest, "log").readText())
        assertEquals("new", File(dest, "imported").readText())
        assertEquals(listOf("engine"), names())
    }

    @Test
    fun aDirectoryThatCannotBeReplacedIsPutBack() {
        val dest = File(folder.root, "engine").apply { mkdir() }
        File(dest, "log").writeText("old")
        // nothing staged: the rename into place fails once `dest` is aside
        assertThrows(IOException::class.java) { replaceDirectory(dest, File(folder.root, "engine.import")) {} }
        assertEquals("old", File(dest, "log").readText())
        assertEquals(listOf("engine"), names())
    }
}
