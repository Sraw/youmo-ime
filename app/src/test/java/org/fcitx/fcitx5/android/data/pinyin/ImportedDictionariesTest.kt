/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import io.mockk.every
import io.mockk.mockkObject
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries.Unread
import org.fcitx.fcitx5.android.engine.data.SourceException
import org.fcitx.fcitx5.android.engine.libime.LibimeFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ImportedDictionariesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dir by lazy { folder.newFolder("dictionaries") }

    // what libime kept of a dictionary imported from text (see ime-core's resources/libime)
    private val extra = javaClass.getResourceAsStream("/libime/extra.dict")!!.readBytes()

    private fun names() = dir.list()!!.sorted()

    // the dictionaries put aside, not the dot files noting which were unread
    private fun aside() = File(dir, ImportedDictionaries.LIBIME).list()?.filter { !it.startsWith(".") }?.sorted().orEmpty()

    @Test
    fun aDictionaryIsKeptAsTheEngineReadsIt() {
        val dest = File(dir, "a.txt")
        val words = ImportedDictionaries.write(sequenceOf("﻿你好\tni'hao", "junk", "绿 lü -1"), dest)
        assertEquals(2, words)
        assertEquals("你好 ni'hao 0.0\n绿 lü -1.0\n", dest.readText())
        assertEquals(listOf("a.txt"), names())
    }

    @Test
    fun aDictionaryWithNothingToReadIsNotWritten() {
        val dest = File(dir, "a.txt").apply { writeText("你 ni 0.0\n") }
        assertEquals(0, ImportedDictionaries.write(sequenceOf("junk", ""), dest))
        assertEquals("你 ni 0.0\n", dest.readText())
        assertEquals(listOf("a.txt"), names())
    }

    @Test
    fun aWordPackIsKeptAsItCameOnceReadThrough() {
        val dest = File(dir, "2026q1.words")
        val lines = sequenceOf("\uFEFF# youmo words 1", "# layer: 2026q1", "搭子 da'zi -5.6", "", "# end")
        assertEquals(1, ImportedDictionaries.writePack(lines, dest))
        assertEquals("# youmo words 1\n# layer: 2026q1\n搭子 da'zi -5.6\n\n# end\n", dest.readText())
        // one with no word, or a bad line, is not written
        assertEquals(0, ImportedDictionaries.writePack(sequenceOf("# youmo words 1"), File(dir, "empty.words")))
        try {
            ImportedDictionaries.writePack(sequenceOf("# youmo words 1", "搭子 da'zi"), File(dir, "bad.words"))
            fail("read a bad pack")
        } catch (e: SourceException) {
            assertEquals("bad.words:2: expected \"word pin'yin log10P\"", e.message)
        }
        assertEquals(listOf("2026q1.words"), names())
    }

    @Test
    fun libimesDictionariesTurnIntoText() {
        File(dir, "on.dict").writeBytes(extra)
        File(dir, "off.dict.disable").writeBytes(extra)
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf(".libime", "off.txt.disable", "on.txt"), names())
        assertEquals("骁骎 xiao'qin 0.0\n琮璟 cong'jing 0.0\n", File(dir, "on.txt").readText())
        assertEquals(listOf("off.dict.disable", "on.dict"), aside())
        // and again, nothing left to do
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf(".libime", "off.txt.disable", "on.txt"), names())
        assertEquals(listOf("off.dict.disable", "on.dict"), aside())
    }

    @Test
    fun whatCannotBeTurnedIsPutAside() {
        File(dir, "corrupt.dict").writeBytes(extra.copyOf(30))
        File(dir, "taken.dict").writeBytes(extra)
        File(dir, "taken.txt.disable").writeText("你 ni 0.0\n")
        File(dir, "other.dat").writeText("?")
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf(".libime", "other.dat", "taken.txt.disable"), names())
        assertEquals(listOf("corrupt.dict", "taken.dict"), aside())
        assertEquals("你 ni 0.0\n", File(dir, "taken.txt.disable").readText())
    }

    @Test
    fun oneTooLargeForTheHeapIsPutAsideNotReadAgain() {
        File(dir, "huge.dict").writeBytes(extra)
        mockkObject(LibimeFiles) {
            every { LibimeFiles.pinyinDictionary(any()) } throws OutOfMemoryError("Java heap space")
            ImportedDictionaries.migrate(dir)
        }
        assertEquals(listOf(".libime"), names())
        assertEquals(listOf("huge.dict"), aside())
    }

    @Test
    fun anEmptyDictionaryIsOnlyPutAside() {
        // libime's format, version 1: magic, version, a trie with no node
        File(dir, "empty.dict").writeBytes(byteArrayOf(0, 0x0f, 0xc6.toByte(), 0x13, 0, 0, 0, 1) + ByteArray(20))
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf(".libime"), names())
        assertFalse(File(dir, "empty.txt").exists())
    }

    @Test
    fun eachDictionaryPutAsideUnreadIsNotedWithWhy() {
        File(dir, "good.dict").writeBytes(extra)
        File(dir, "corrupt.dict.disable").writeBytes(extra.copyOf(30))
        // libime's format, version 1: magic, version, a trie with no node
        File(dir, "empty.dict").writeBytes(byteArrayOf(0, 0x0f, 0xc6.toByte(), 0x13, 0, 0, 0, 1) + ByteArray(20))
        File(dir, "taken.dict").writeBytes(extra)
        File(dir, "taken.txt").writeText("你 ni 0.0\n")
        ImportedDictionaries.migrate(dir)
        // one turned into text, or whose name a text one has, is no news
        assertEquals(
            listOf(Unread("corrupt.dict.disable", Unread.Reason.DAMAGED), Unread("empty.dict", Unread.Reason.NO_WORDS)),
            ImportedDictionaries.unread(dir).sortedBy { it.fileName },
        )
        File(dir, "huge.dict").writeBytes(extra)
        mockkObject(LibimeFiles) {
            every { LibimeFiles.pinyinDictionary(any()) } throws OutOfMemoryError("Java heap space")
            ImportedDictionaries.migrate(dir)
        }
        // after those before, all kept until told
        assertEquals(3, ImportedDictionaries.unread(dir).size)
        assertEquals(Unread("huge.dict", Unread.Reason.TOO_LARGE), ImportedDictionaries.unread(dir).last())
        assertEquals(listOf("corrupt.dict.disable", "empty.dict", "good.dict", "huge.dict", "taken.dict"), aside())
    }

    @Test
    fun whatTheUserWasToldOfIsForgottenNotWhatCameSince() {
        File(dir, "a.dict").writeBytes(extra.copyOf(30))
        ImportedDictionaries.migrate(dir)
        val told = ImportedDictionaries.unread(dir)
        assertEquals(listOf(Unread("a.dict", Unread.Reason.DAMAGED)), told)
        // put aside while the user read of a.dict
        File(dir, "b.dict").writeBytes(extra.copyOf(30))
        ImportedDictionaries.migrate(dir)
        ImportedDictionaries.forgetUnread(dir, told)
        assertEquals(listOf(Unread("b.dict", Unread.Reason.DAMAGED)), ImportedDictionaries.unread(dir))
        ImportedDictionaries.forgetUnread(dir, ImportedDictionaries.unread(dir))
        assertEquals(emptyList<Unread>(), ImportedDictionaries.unread(dir))
        assertFalse(File(dir, "${ImportedDictionaries.LIBIME}/${ImportedDictionaries.UNREAD}").exists())
        assertEquals(listOf("a.dict", "b.dict"), aside())
    }

    @Test
    fun thosePutAsideUnreadBeforeTheyWereNotedAreNotedOnce() {
        // as an older migration left them: put aside, nothing noted
        val libime = File(dir, ImportedDictionaries.LIBIME).apply { mkdir() }
        File(libime, "old.dict").writeBytes(extra.copyOf(30))
        File(libime, "old.dict.disable.1").writeBytes(extra.copyOf(30))
        File(libime, "on.dict").writeBytes(extra)
        File(dir, "on.txt").writeText("你 ni 0.0\n")
        File(libime, "off.dict.disable").writeBytes(extra)
        File(dir, "off.txt.disable").writeText("你 ni 0.0\n")
        File(dir, "new.dict").writeBytes(extra.copyOf(30))
        ImportedDictionaries.migrate(dir)
        // one with a text dictionary of its name is no news, one noted as it is put aside is noted once
        assertEquals(
            listOf(
                Unread("old.dict", Unread.Reason.DAMAGED),
                Unread("old.dict.disable.1", Unread.Reason.DAMAGED),
                Unread("new.dict", Unread.Reason.DAMAGED),
            ),
            ImportedDictionaries.unread(dir),
        )
        // told of, they are not noted again
        ImportedDictionaries.forgetUnread(dir, ImportedDictionaries.unread(dir))
        ImportedDictionaries.migrate(dir)
        assertEquals(emptyList<Unread>(), ImportedDictionaries.unread(dir))
        assertEquals(listOf("new.dict", "off.dict.disable", "old.dict", "old.dict.disable.1", "on.dict"), aside())
    }

    @Test
    fun oneAnOlderMigrationPutAsideIsReadAgainAndNotedOnlyIfItStillCannotBe() {
        val libime = File(dir, ImportedDictionaries.LIBIME).apply { mkdir() }
        // turned into text by an older migration, its text deleted since
        File(libime, "gone.dict").writeBytes(extra)
        File(libime, "old.dict").writeBytes(extra.copyOf(30))
        // libime's format, version 1: magic, version, a trie with no node
        File(libime, "empty.dict").writeBytes(byteArrayOf(0, 0x0f, 0xc6.toByte(), 0x13, 0, 0, 0, 1) + ByteArray(20))
        ImportedDictionaries.migrate(dir)
        assertEquals(
            listOf(Unread("empty.dict", Unread.Reason.NO_WORDS), Unread("old.dict", Unread.Reason.DAMAGED)),
            ImportedDictionaries.unread(dir),
        )
        // nor does the one deleted come back
        assertEquals(listOf(".libime"), names())
        assertEquals(listOf("empty.dict", "gone.dict", "old.dict"), aside())
    }

    @Test
    fun oneAnOlderMigrationPutAsideThatRunsTheHeapOutOrCannotBeReadIsNotedSo() {
        val libime = File(dir, ImportedDictionaries.LIBIME).apply { mkdir() }
        File(libime, "huge.dict").writeBytes(extra)
        File(libime, "lost.dict").writeBytes(extra.copyOf(30))
        mockkObject(LibimeFiles) {
            every { LibimeFiles.pinyinDictionary(match { it.size == extra.size }) } throws OutOfMemoryError("Java heap space")
            // as if the file could not be read
            every { LibimeFiles.pinyinDictionary(match { it.size == 30 }) } throws IOException("I/O error")
            ImportedDictionaries.migrate(dir)
        }
        assertEquals(
            listOf(Unread("huge.dict", Unread.Reason.TOO_LARGE), Unread("lost.dict", Unread.Reason.UNKNOWN)),
            ImportedDictionaries.unread(dir),
        )
    }

    @Test
    fun oneOfTheNameOfOneAnOlderMigrationPutAsideIsNotedApartFromIt() {
        // as an older migration left it: put aside, nothing noted
        val libime = File(dir, ImportedDictionaries.LIBIME).apply { mkdir() }
        // libime's format, version 1: magic, version, a trie with no node
        File(libime, "a.dict").writeBytes(byteArrayOf(0, 0x0f, 0xc6.toByte(), 0x13, 0, 0, 0, 1) + ByteArray(20))
        File(dir, "a.dict").writeBytes(extra.copyOf(30))
        ImportedDictionaries.migrate(dir)
        // the older first, each once
        assertEquals(
            listOf(Unread("a.dict", Unread.Reason.NO_WORDS), Unread("a.dict", Unread.Reason.DAMAGED)),
            ImportedDictionaries.unread(dir),
        )
        assertEquals(listOf("a.dict", "a.dict.1"), aside())
    }

    @Test
    fun oneFirstPutAsideIsNotTakenForOneAnOlderMigrationPutAside() {
        File(dir, "a.dict").writeBytes(extra.copyOf(30))
        ImportedDictionaries.migrate(dir)
        val told = ImportedDictionaries.unread(dir)
        assertEquals(listOf(Unread("a.dict", Unread.Reason.DAMAGED)), told)
        ImportedDictionaries.forgetUnread(dir, told)
        // told of, it is not noted again
        ImportedDictionaries.migrate(dir)
        assertEquals(emptyList<Unread>(), ImportedDictionaries.unread(dir))
    }

    @Test
    fun aDirectoryWithNoLibimeDictionaryIsLeftAsItIs() {
        File(dir, "a.txt").writeText("你 ni 0.0\n")
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf("a.txt"), names())
    }

    @Test
    fun aDictionaryOfANameAlreadyAsideKeepsBoth() {
        File(dir, "a.dict").writeBytes(extra)
        ImportedDictionaries.migrate(dir)
        File(dir, "a.txt").delete()
        File(dir, "a.dict").writeBytes(extra.copyOf(30))
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf("a.dict", "a.dict.1"), aside())
        assertEquals(extra.size.toLong(), File(dir, ".libime/a.dict").length())
    }

    @Test
    fun aDictionaryThatCannotBeWrittenFails() {
        // a directory where the text should go: the rename fails
        val dest = File(dir, "a.txt").apply { mkdir() }
        assertThrows(IOException::class.java) { ImportedDictionaries.write(sequenceOf("你 ni 0.0"), dest) }
        assertEquals(listOf("a.txt"), names())
    }
}
