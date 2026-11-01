/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.platform

import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * What the engine asks of [UserFiles] and [LogFile], the same of the disk ([JvmFiles]) and of
 * [MemoryFiles]: the tests that keep the user's files in memory see what the phone would. Where a
 * file is wanted and a directory stands, as the engine's tests of a record that does not open put one.
 */
abstract class UserFilesContractTest {
    /** An empty directory of the files under test. */
    abstract fun root(): UserFiles

    private val root by lazy { root() }

    private fun LogFile.text() = read().decodeToString()

    private fun LogOutput.text(s: String) = write(s.toByteArray())

    @Test
    fun aDirectoryAtAFilesPathIsTakenThoughNoFile() {
        root.dir("d").mkdirs()
        assertFalse(root.file("d").exists())
        assertTrue(root.file("d").taken())
        assertTrue(root.dir("d").exists())
        root.file("f").write(byteArrayOf(1))
        assertTrue(root.file("f").exists())
        assertTrue(root.file("f").taken())
        assertFalse(root.dir("f").exists())
        assertFalse(root.dir("f").mkdirs())
        // nor deeper: a file on the way makes no directory below it
        assertFalse(root.dir("f").dir("g").mkdirs())
        assertFalse(root.dir("f").dir("g").exists())
        assertThrows(IOException::class.java) { root.dir("f").dir("g").file("x").create() }
        assertFalse(root.file("none").taken())
    }

    @Test
    fun aDirectoryAtAFilesPathIsNeitherReadNorWritten() {
        root.dir("d").mkdirs()
        val d = root.file("d")
        listOf<() -> Any>({ d.read() }, { d.read(1) }, { d.input() }, { d.map() }, { d.create() }, { d.append() })
            .forEach { assertThrows(IOException::class.java) { it() } }
        assertThrows(IOException::class.java) { root.file("none").read() }
    }

    @Test
    fun aWriteNeedsItsDirectory() {
        val f = root.dir("no").file("f")
        assertThrows(IOException::class.java) { f.create() }
        assertThrows(IOException::class.java) { f.truncate(1) }
        assertThrows(IOException::class.java) { f.createNew() }
        f.mkdirs()
        f.write("x".toByteArray())
        assertEquals("x", f.text())
    }

    @Test
    fun aRenameReplacesAFileNotADirectory() {
        val a = root.file("a").apply { write("a".toByteArray()) }
        val b = root.file("b").apply { write("b".toByteArray()) }
        assertTrue(a.renameTo(b))
        assertFalse(a.exists())
        assertEquals("a", b.text())
        root.dir("d").mkdirs()
        assertFalse(b.renameTo(root.file("d")))
        assertEquals("a", b.text())
        assertFalse(root.file("none").renameTo(root.file("c")))
    }

    @Test
    fun anAppendOpenOnAFileWrittenAnewOrCutWritesOnAtItsNewEnd() {
        val f = root.file("log")
        f.append().use { out ->
            out.text("abc")
            out.flush()
            f.write("xy".toByteArray())
            out.text("d")
            out.flush()
            assertEquals("xyd", f.text())
            f.truncate(1)
            out.text("e")
            out.sync()
            assertEquals("xe", f.text())
        }
        assertEquals(2L, f.length())
    }

    // what a fold leans on: an append open on a file renamed over writes to the file that was, not the new one
    @Test
    fun anAppendOpenOnAFileRenamedOverWritesNoMoreToThatPath() {
        val a = root.file("a").apply { write("a".toByteArray()) }
        val b = root.file("b")
        b.append().use { out ->
            out.text("b")
            out.flush()
            assertTrue(a.renameTo(b))
            out.text("lost")
            out.flush()
        }
        assertEquals("a", b.text())
        // the other way: an append open on the file renamed goes with it
        val tmp = root.file("tmp")
        tmp.append().use { out ->
            out.text("x")
            out.flush()
            assertTrue(tmp.renameTo(b))
            out.text("y")
            out.flush()
        }
        assertEquals("xy", b.text())
    }

    @Test
    fun aCutLongerAddsZerosAndMakesAFileNotThere() {
        val f = root.file("m")
        f.truncate(3)
        assertArrayEquals(ByteArray(3), f.read())
        f.write("abcdef".toByteArray())
        f.truncate(2)
        assertEquals("ab", f.text())
        assertArrayEquals("ab".toByteArray(), f.read(2))
        assertThrows(IOException::class.java) { f.read(3) }
    }

    @Test
    fun aDeleteSaysWhetherItDeleted() {
        val f = root.file("f")
        assertFalse(f.delete())
        f.createNew()
        assertTrue(f.delete())
        assertFalse(f.taken())
        // an empty directory at a file's path goes, a full one stays
        root.dir("e").mkdirs()
        assertTrue(root.file("e").delete())
        assertFalse(root.dir("e").exists())
        root.dir("full").apply { mkdirs() }.file("x").createNew()
        assertFalse(root.file("full").delete())
        assertFalse(root.dir("full").delete())
        assertFalse(root.dir("none").delete())
        assertTrue(root.dir("none").deleteAll())
        assertTrue(root.dir("full").deleteAll())
        assertFalse(root.dir("full").exists())
        root.dir("empty").mkdirs()
        assertTrue(root.dir("empty").delete())
        // a file where a directory is wanted goes with it, as a directory's place is emptied before it is made
        root.file("plain").createNew()
        assertTrue(root.dir("plain").deleteAll())
        assertFalse(root.file("plain").taken())
        assertTrue(root.dir("plain").mkdirs())
    }

    @Test
    fun aFileIsMadeNewOnlyWhereNothingIs() {
        root.dir("d").mkdirs()
        assertFalse(root.file("d").createNew())
        val f = root.file("f")
        assertTrue(f.createNew())
        assertFalse(f.createNew())
        assertEquals(0L, f.length())
    }

    @Test
    fun aDirectoryAtAFilesPathMovesAsADirectory() {
        root.dir("d").apply { mkdirs() }.file("x").createNew()
        assertTrue(root.file("d").renameTo(root.file("e")))
        assertFalse(root.dir("d").exists())
        assertEquals(listOf("x"), root.dir("e").files())
    }

    @Test
    fun aDirectoryRenamesOntoNothingOrAnEmptyDirectory() {
        val a = root.dir("a").apply { mkdirs() }
        a.dir("sub").apply { mkdirs() }.file("x").write("x".toByteArray())
        assertTrue(a.renameTo(root.dir("b")))
        assertFalse(a.exists())
        assertEquals("x", root.dir("b").dir("sub").file("x").text())
        // onto an empty directory: replaced
        root.dir("c").mkdirs()
        assertTrue(root.dir("b").renameTo(root.dir("c")))
        assertEquals(listOf("sub"), root.dir("c").dirs())
        // not onto a full one, nor a file
        root.dir("full").apply { mkdirs() }.file("y").createNew()
        assertFalse(root.dir("c").renameTo(root.dir("full")))
        root.file("f").createNew()
        assertFalse(root.dir("c").renameTo(root.dir("f")))
        assertTrue(root.dir("c").exists())
        assertFalse(root.dir("none").renameTo(root.dir("other")))
    }

    // a directory's own length is the file system's (ext4's and f2fs's, the phone's, 4096 is what
    // MemoryFiles gives), read off each directory so that a tmpfs or overlayfs runs the test too
    @Test
    fun aSizeCountsTheFilesAndTheDirectoriesOwnLengths() {
        val d = root.dir("d").apply { mkdirs() }
        d.file("a").write(ByteArray(3))
        d.dir("s").apply { mkdirs() }.file("b").write(ByteArray(2))
        assertEquals(5 + root.file("d").length() + d.file("s").length(), d.size())
        assertEquals(0L, root.dir("none").size())
    }

    @Test
    fun filesAndDirectoriesAreListedByName() {
        listOf("c", "a", "b").forEach { root.file(it).createNew() }
        listOf("z", "y").forEach { root.dir(it).mkdirs() }
        assertEquals(listOf("a", "b", "c"), root.files())
        assertEquals(listOf("y", "z"), root.dirs())
        assertEquals(emptyList<String>(), root.dir("none").files())
    }

    @Test
    fun aFileKeepsTheTimeItIsSetToAndOneNotThereHasNone() {
        val file = root.file("a")
        assertEquals(0L, file.lastModified())
        assertFalse(file.setLastModified(1_600_000_000_000))
        file.create().use { it.text("x") }
        assertTrue(file.setLastModified(1_600_000_000_000))
        assertEquals(1_600_000_000_000, file.lastModified())
    }

    @Test
    fun aMappedFileReadsAsItsBytes() {
        val f = root.file("m").apply { write(byteArrayOf(1, 2, 3)) }
        val map = f.map()
        assertEquals(3, map.capacity())
        assertEquals(2.toByte(), map.get(1))
        assertEquals(0, root.file("e").apply { createNew() }.map().capacity())
    }
}

class JvmFilesContractTest : UserFilesContractTest() {
    @get:Rule
    val tmp = TemporaryFolder()

    override fun root(): UserFiles = JvmFiles(tmp.newFolder())

    // a stat that fails reads as neither file nor directory (a dangling link here, EIO on a FUSE on the
    // phone): listed as unknown, not dropped, else ConfigHome takes an old config for none (review F4889, F4983)
    @Test
    fun anEntryOfAKindThatDoesNotReadIsListedAsUnknown() {
        val dir = tmp.newFolder()
        File(dir, "f").writeText("x")
        File(dir, "d").mkdir()
        Files.createSymbolicLink(File(dir, "gone").toPath(), File(dir, "nowhere").toPath())
        assertEquals(UserFiles.Listing(listOf("f"), listOf("d"), listOf("gone")), JvmFiles(dir).listing())
    }
}

class MemoryFilesContractTest : UserFilesContractTest() {
    override fun root(): UserFiles = MemoryFiles()

    // the contract reads each directory's length off the files under test; the phone's (ext4,
    // f2fs) is what MemoryFiles must give, so a size there is as the phone's
    @Test
    fun aDirectoryIsAsLongAsOnThePhone() {
        val root = root()
        root.dir("d").mkdirs()
        assertEquals(4096L, root.file("d").length())
    }
}
