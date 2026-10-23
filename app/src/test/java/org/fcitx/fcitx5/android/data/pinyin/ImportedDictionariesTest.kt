/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries.Unread
import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

class ImportedDictionariesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dir by lazy { folder.newFolder("dictionaries") }

    // what libime kept of a dictionary imported from text (see ime-core's resources/libime)
    private val extra = javaClass.getResourceAsStream("/libime/extra.dict")!!.use { it.readBytes() }

    private fun names() = dir.list()!!.sorted()

    // the dictionaries put aside, not the dot files noting which were unread
    private fun aside() = File(dir, ImportedDictionaries.LIBIME).list()?.filter { !it.startsWith(".") }?.sorted().orEmpty()

    @Test
    fun aDictionaryIsKeptAsTheEngineReadsIt() {
        val dest = File(dir, "a.txt.gz")
        val words = ImportedDictionaries.write(sequenceOf("\uFEFF你好\tni'hao", "junk", "绿 lü -1"), dest)
        assertEquals(2, words)
        assertEquals("你好 ni'hao 0.0\n绿 lü -1.0\n", gunzip(dest))
        assertEquals(listOf("a.txt.gz"), names())
    }

    @Test
    fun aDictionaryWithNothingToReadIsNotWritten() {
        val dest = File(dir, "a.txt.gz").apply { writeText("你 ni 0.0\n") }
        assertEquals(0, ImportedDictionaries.write(sequenceOf("junk", ""), dest))
        assertEquals("你 ni 0.0\n", dest.readText())
        assertEquals(listOf("a.txt.gz"), names())
    }

    @Test
    fun aWordPackAnOlderBuildImportedIsGone() {
        File(dir, "a.words").writeText("# youmo words 1\n搭子 da'zi -5.6\n")
        File(dir, "b.words.disable").writeText("# youmo words 1\n")
        File(dir, "mine.txt").writeText("搭子 da'zi 0\n")
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf("mine.txt"), names())
    }

    @Test
    fun aWordPackGoesAndLibimesOfItsNameIsTurnedIntoText() {
        File(dir, "a.words").writeText("# youmo words 1\n搭子 da'zi -5.6\n")
        File(dir, "b.words.disable").writeText("# youmo words 1\n")
        File(dir, "mine.words").writeText("# youmo words 1\n")
        File(dir, "mine.txt").writeText("搭子 da'zi 0\n")
        File(dir, "b.dict").writeBytes(extra)
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf(".libime", "b.txt.gz", "mine.txt"), names())
    }

    // two dictionaries shown, the user's and the base: one an older build merged into the new words
    // (listed in INTO_NEW, or half written as its .tmp) is merged into the base, the list gone, as
    // it is again after a restore brings it back
    @Test
    fun dictionariesOnceMergedIntoTheNewWordsAreMergedIntoTheBase() {
        File(dir, "mine.txt").writeText("搭子 da'zi 0\n")
        File(dir, "theirs.txt.disable").writeText("搭子 da'zi 0\n")
        File(dir, ImportedDictionaries.INTO_NEW).writeText("mine\ntheirs\ngone\n")
        File(dir, "${ImportedDictionaries.INTO_NEW}.tmp").writeText("mine\n")
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf("mine.txt", "theirs.txt.disable"), names())
        File(dir, ImportedDictionaries.INTO_NEW).writeText("mine\n")
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf("mine.txt", "theirs.txt.disable"), names())
    }

    @Test
    fun libimesDictionariesTurnIntoText() {
        File(dir, "on.dict").writeBytes(extra)
        File(dir, "off.dict.disable").writeBytes(extra)
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf(".libime", "off.txt.gz.disable", "on.txt.gz"), names())
        assertEquals("骁骎 xiao'qin 0.0\n琮璟 cong'jing 0.0\n", gunzip(File(dir, "on.txt.gz")))
        assertEquals(listOf("off.dict.disable", "on.dict"), aside())
        // and again, nothing left to do
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf(".libime", "off.txt.gz.disable", "on.txt.gz"), names())
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
        ImportedDictionaries.migrate(dir) { throw OutOfMemoryError("Java heap space") }
        assertEquals(listOf(".libime"), names())
        assertEquals(listOf("huge.dict"), aside())
    }

    @Test
    fun anEmptyDictionaryIsOnlyPutAside() {
        // libime's format, version 1: magic, version, a trie with no node
        File(dir, "empty.dict").writeBytes(byteArrayOf(0, 0x0f, 0xc6.toByte(), 0x13, 0, 0, 0, 1) + ByteArray(20))
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf(".libime"), names())
        assertFalse(File(dir, "empty.txt.gz").exists())
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
        ImportedDictionaries.migrate(dir) { throw OutOfMemoryError("Java heap space") }
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
        ImportedDictionaries.migrate(dir) { data ->
            if (data.size == extra.size) throw OutOfMemoryError("Java heap space")
            // as if the file could not be read
            throw IOException("I/O error")
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
    fun oneAnOlderMigrationPutAsideIsNotedThoughOneOfItsNameIsTurnedIntoTextAfter() {
        // as an older migration left it: put aside, nothing noted
        val libime = File(dir, ImportedDictionaries.LIBIME).apply { mkdir() }
        File(libime, "x.dict").writeBytes(extra.copyOf(30))
        File(dir, "x.dict").writeBytes(extra)
        ImportedDictionaries.migrate(dir)
        // looked at before the new one is turned into x.txt: another dictionary, never told of
        assertEquals(listOf(Unread("x.dict", Unread.Reason.DAMAGED)), ImportedDictionaries.unread(dir))
        assertEquals(listOf(".libime", "x.txt.gz"), names())
        assertEquals("骁骎 xiao'qin 0.0\n琮璟 cong'jing 0.0\n", gunzip(File(dir, "x.txt.gz")))
        assertEquals(listOf("x.dict", "x.dict.1"), aside())
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
        File(dir, "a.txt.gz").delete()
        File(dir, "a.dict").writeBytes(extra.copyOf(30))
        ImportedDictionaries.migrate(dir)
        assertEquals(listOf("a.dict", "a.dict.1"), aside())
        assertEquals(extra.size.toLong(), File(dir, ".libime/a.dict").length())
    }

    @Test
    fun aDictionaryThatCannotBeWrittenFails() {
        // a directory where the text should go: the rename fails
        val dest = File(dir, "a.txt.gz").apply { mkdir() }
        assertThrows(IOException::class.java) { ImportedDictionaries.write(sequenceOf("你 ni 0.0"), dest) }
        assertEquals(listOf("a.txt.gz"), names())
    }

    // a dictionary is any of four files by its name: an older build's text, on or off, or one kept, on or off
    @Test
    fun aNameIsTakenByAnyOfItsFourFiles() {
        assertRows(listOf("a.txt", "a.txt.disable", "a.txt.gz", "a.txt.gz.disable"), { it }) { file ->
            dir.listFiles()!!.forEach { it.delete() }
            File(dir, file).writeText("你 ni 0.0\n")
            assertTrue(file, ImportedDictionaries.hasDictionary(dir, "a"))
            assertFalse(file, ImportedDictionaries.hasDictionary(dir, "b"))
            // and an import of its name made meanwhile is not renamed over it, refused as a name taken (F3603)
            val temp = File(dir, "a.txt.gz.tmp").apply { writeText("new") }
            assertThrows(file, ImportedDictionaries.NameTaken::class.java) { ImportedDictionaries.commit(temp, File(dir, "a.txt.gz")) }
            assertEquals(file, listOf(file, "a.txt.gz.tmp").sorted(), names())
        }
    }

    // a tmp another write of its path deleted is never renamed into place as an empty file (F3594)
    @Test
    fun aTmpGoneIsNotCommitted() {
        assertThrows(IOException::class.java) { ImportedDictionaries.commit(File(dir, "a.txt.gz.tmp"), File(dir, "a.txt.gz")) }
        assertEquals(emptyList<String>(), names())
    }

    // each of a dictionary's kept files deleted, though one before it could not be: whether all went (F3598).
    // Its text refused, its gzip stays too: the two never parted, the text not converted again (F3686)
    @Test
    fun aDeleteGoesOnPastAFileThatStays() {
        // a directory of a file's name, not empty: its delete fails
        File(dir, "a.txt.gz").apply { mkdir() }.resolve("inside").writeText("x")
        File(dir, "a.txt.gz.disable").writeText("kept")
        assertFalse(ImportedDictionaries.delete(dir, "a"))
        assertEquals(listOf("a.txt.gz"), names())
        File(dir, "b.txt").apply { mkdir() }.resolve("inside").writeText("x")
        File(dir, "b.txt.gz").writeText("kept")
        assertFalse(ImportedDictionaries.delete(dir, "b"))
        assertEquals(listOf("a.txt.gz", "b.txt", "b.txt.gz"), names())
    }

    // the page lists one row a name: the text if there is one (it wins, converted next), else the
    // kept one; kept both on and off with no text, the newer stays, the on one at one time (RN3)
    @Test
    fun oneOfANameIsListedOnceAndTheNewerOfTwoKeptStays() {
        File(dir, "a.txt").writeText("text")
        File(dir, "a.txt.gz.disable").writeText("kept")
        File(dir, "b.txt.gz").apply { writeText("old"); setLastModified(1_000_000) }
        File(dir, "b.txt.gz.disable").apply { writeText("new"); setLastModified(2_000_000) }
        File(dir, "c.txt.gz").apply { writeText("on"); setLastModified(1_000_000) }
        File(dir, "c.txt.gz.disable").apply { writeText("off"); setLastModified(1_000_000) }
        // texts both on and off by the same rule, before either is converted: not the off one always (F3596)
        File(dir, "d.txt").apply { writeText("new"); setLastModified(2_000_000) }
        File(dir, "d.txt.disable").apply { writeText("old"); setLastModified(1_000_000) }
        File(dir, "e.txt").apply { writeText("on"); setLastModified(1_000_000) }
        File(dir, "e.txt.disable").apply { writeText("off"); setLastModified(1_000_000) }
        assertEquals(listOf("a.txt", "b.txt.gz.disable", "c.txt.gz", "d.txt", "e.txt"), ImportedDictionaries.list(dir).map { it.name })
        // the text's conversion settles a's
        assertEquals(listOf("a.txt", "a.txt.gz.disable", "b.txt.gz.disable", "c.txt.gz", "d.txt", "e.txt"), names())
    }

    // turned and deleted by name: whichever of its files is there now, not the one listed (RS1)
    @Test
    fun aDictionaryIsTurnedAndDeletedByItsName() {
        File(dir, "a.txt.gz").writeText("kept")
        assertTrue(ImportedDictionaries.turn(dir, "a", false))
        assertEquals(listOf("a.txt.gz.disable"), names())
        assertTrue(ImportedDictionaries.turn(dir, "a", false))
        // an older build's text beside it (its delete refused after its conversion): both turned,
        // never parted, or the text is converted again and an upgrade told (F3686)
        File(dir, "a.txt").writeText("text")
        assertTrue(ImportedDictionaries.turn(dir, "a", false))
        assertEquals(listOf("a.txt.disable", "a.txt.gz.disable"), names())
        assertTrue(ImportedDictionaries.turn(dir, "a", true))
        assertEquals(listOf("a.txt", "a.txt.gz"), names())
        // a backup's gzip of the other state beside them (settle leaves the pair while the text is there):
        // that one is it, skipped, not a refused rename failing the turn (F3700)
        File(dir, "a.txt.gz.disable").writeText("backup")
        assertTrue(ImportedDictionaries.turn(dir, "a", false))
        assertEquals(listOf("a.txt.disable", "a.txt.gz", "a.txt.gz.disable"), names())
        File(dir, "a.txt.gz.disable").delete()
        assertTrue(ImportedDictionaries.turn(dir, "a", true))
        assertEquals(listOf("a.txt", "a.txt.gz"), names())
        // the gzip's rename refused (one of its name there, a directory): the text turned back
        File(dir, "a.txt.gz.disable").mkdir()
        assertFalse(ImportedDictionaries.turn(dir, "a", false))
        File(dir, "a.txt.gz.disable").delete()
        assertEquals(listOf("a.txt", "a.txt.gz"), names())
        assertFalse(ImportedDictionaries.turn(dir, "gone", true))
        assertTrue(ImportedDictionaries.delete(dir, "a"))
        assertEquals(emptyList<String>(), names())
    }

    // a conversion's gzip put in the text's place, on or off as the text is at the commit; dropped
    // if the text went or changed since it was read
    @Test
    fun aConversionIsCommittedAsTheTextIsThen() {
        val text = File(dir, "a.txt").apply { writeText("你 ni 0.0\n") }
        val length = text.length()
        val modified = text.lastModified()
        File(dir, "a.txt.gz").writeText("kept before")
        // turned off as it was read: its length and time kept by the rename
        assertTrue(text.renameTo(File(dir, "a.txt.disable")))
        val temp = File(dir, "a.txt.gz.tmp").apply { writeText("gzip") }
        assertNull(ImportedDictionaries.commitConversion(dir, "a", temp, length, modified)!!.left)
        assertEquals(listOf("a.txt.gz.disable"), names())
        assertEquals("gzip", File(dir, "a.txt.gz.disable").readText())
        // changed since (a backup restored over it): not committed, the text left to convert again
        File(dir, "b.txt").apply { writeText("new words"); setLastModified(modified) }
        val other = File(dir, "b.txt.gz.tmp").apply { writeText("gzip") }
        assertNull(ImportedDictionaries.commitConversion(dir, "b", other, length, modified))
        assertEquals(listOf("a.txt.gz.disable", "b.txt"), names())
        // deleted since: nothing
        assertNull(ImportedDictionaries.commitConversion(dir, "c", File(dir, "c.txt.gz.tmp").apply { writeText("gzip") }, 1, 1))
        assertEquals(listOf("a.txt.gz.disable", "b.txt"), names())
    }

    private fun gunzip(file: File) = GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }

}
