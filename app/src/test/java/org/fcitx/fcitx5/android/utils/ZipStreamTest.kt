/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
}
