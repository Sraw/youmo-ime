/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Themes, tables and backups are zips the user picked: none may write outside the directory they are extracted to. */
class ZipStreamTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dest by lazy { folder.newFolder("dest") }

    /** A null content makes a directory entry. */
    private fun zip(vararg entries: Pair<String, String?>): ZipInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                content?.let { out.write(it.encodeToByteArray()) }
                out.closeEntry()
            }
        }
        return ZipInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    @Test
    fun filesAndDirectoriesAreExtractedAndTheTopLevelReturned() {
        val top = zip("a.json" to "{}", "img/" to null, "img/b.png" to "png").use { it.extract(dest) }
        assertEquals(setOf("a.json", "img"), top.map { it.name }.toSet())
        assertEquals("{}", File(dest, "a.json").readText())
        assertEquals("png", File(dest, "img/b.png").readText())
    }

    @Test
    fun aFileWhoseDirectoriesHaveNoEntriesIsExtracted() {
        zip("x/y/z.txt" to "z").use { it.extract(dest) }
        assertEquals("z", File(dest, "x/y/z.txt").readText())
    }

    @Test
    fun aFileInASiblingWhoseNameStartsWithTheDestinationIsRejected() {
        assertThrows(SecurityException::class.java) {
            zip("../destx/f" to "f").use { it.extract(dest) }
        }
        assertFalse(File(folder.root, "destx").exists())
    }

    @Test
    fun aFileOutsideTheDestinationIsRejected() {
        assertThrows(SecurityException::class.java) {
            zip("../f" to "f").use { it.extract(dest) }
        }
        assertFalse(File(folder.root, "f").exists())
    }

    @Test
    fun aDirectoryOutsideTheDestinationIsRejected() {
        for (name in listOf("../outside/", "../destx/")) {
            assertThrows(name, SecurityException::class.java) {
                zip(name to null).use { it.extract(dest) }
            }
        }
        assertFalse(File(folder.root, "outside").exists())
        assertFalse(File(folder.root, "destx").exists())
    }

    @Test
    fun aDirectoryEntryForTheDestinationItselfIsAllowed() {
        val top = zip("./" to null, "a" to "x").use { it.extract(dest) }
        assertEquals(listOf("a"), top.map { it.name })
        assertEquals("x", File(dest, "a").readText())
    }

    @Test
    fun theCheckSeesEachEntrysCountItsSizeAndTheTotalBefore() {
        val seen = mutableListOf<Triple<Int, Long, Long>>()
        zip("a" to "12345", "d/" to null, "b" to "").use { it.extract(dest) { c, s, t -> seen += Triple(c, s, t) } }
        // before each entry (size 0) and after each chunk; a directory is an entry of the count too
        assertEquals(listOf(Triple(1, 0L, 0L), Triple(1, 5L, 0L), Triple(2, 0L, 5L), Triple(3, 0L, 5L)), seen)
    }

    @Test
    fun emptyDirectoriesCountTowardsALimit() {
        // a zip of nothing but directories would pass any count of files
        val many = (1..5).map { "d$it/" to null }.toTypedArray()
        assertThrows(IllegalStateException::class.java) {
            zip(*many).use { z -> z.extract(dest) { count, _, _ -> check(count <= 3) } }
        }
    }

    @Test
    fun flatUnpacksTheTopsFilesOnlyAndStillCountsAndMetersTheRest() {
        val seen = mutableListOf<Triple<Int, Long, Long>>()
        val top = zip("a" to "12", "__MACOSX/" to null, "__MACOSX/._a" to "xyz", "b" to "4").use {
            it.extract(dest, flat = true) { c, s, t -> seen += Triple(c, s, t) }
        }
        assertEquals(setOf("a", "b"), top.map { it.name }.toSet())
        assertEquals(setOf("a", "b"), dest.list()!!.toSet())
        // the folder's file read through the check (a zip bomb in one is stopped as at the top), not kept
        assertEquals(
            listOf(Triple(1, 0L, 0L), Triple(1, 2L, 0L), Triple(2, 0L, 2L), Triple(3, 0L, 2L), Triple(3, 3L, 2L), Triple(4, 0L, 5L), Triple(4, 1L, 5L)),
            seen,
        )
    }

    @Test
    fun aCheckThatThrowsStopsBeforeTheChunkPastTheLimitIsWritten() {
        val big = "x".repeat(3 * DEFAULT_BUFFER_SIZE)
        assertThrows(IllegalStateException::class.java) {
            zip("big" to big).use { z -> z.extract(dest) { _, size, _ -> check(size <= DEFAULT_BUFFER_SIZE) } }
        }
        // the zip's reader hands out chunks of up to a buffer: none past the limit reaches the disk
        assertTrue(File(dest, "big").length() <= DEFAULT_BUFFER_SIZE)
    }
}
