/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.pinyin.dict.SourceNames
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The imported dictionaries compiled into one file: how far a compile is, a cancel, a failure told
 * and retried, what a killed one left swept, a fresh start's file mapped at once and one damaged
 * made again, an upgrade told from an update, and a source gone mid-compile no failure.
 */
class MergedDictionaryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dictionaries get() = File(folder.root, "dictionaries").apply { mkdirs() }

    // a dictionary as this build keeps it, gzip'd
    private fun source(name: String, text: String, on: Boolean = true) =
        File(dictionaries, SourceNames.fileName(name, on)).apply { GZIPOutputStream(outputStream()).use { it.write(text.toByteArray()) } }

    // one as 0.3.2 and older kept it
    private fun text(name: String, text: String, on: Boolean = true) = File(dictionaries, SourceNames.textName(name, on)).apply { writeText(text) }

    private fun gunzip(file: File) = GZIPInputStream(file.inputStream()).use { it.readBytes().decodeToString() }

    // compiled and told as a compile of its own (an engine's start), not an import's
    private fun refreshed() = MergedDictionary.retry(dictionaries)!!.get()

    // compiled as at an engine's start, after one a check ([MergedDictionary.current]) may have queued:
    // refresh is null while that one waits, so asked again once it starts, this one after it (F3696);
    // a loop, not generateSequence, which ends at the first null rather than skipping it
    private fun checked(): Any? {
        val until = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < until) {
            MergedDictionary.refresh(dictionaries)?.let { return it.get() }
            Thread.sleep(5)
        }
        error("no refresh queued in 10 s")
    }

    // the one object, as at the process's start for each test: nothing inherited (opened, failed, swept) from another (review)
    @Before
    fun fresh() {
        MergedDictionary.reset()
        MergedDictionary.made = {}
        MergedDictionary.base = { null }
    }

    @After
    fun done() {
        dictionaries.listFiles()!!.forEach { it.deleteRecursively() }
        MergedDictionary.refreshNow(dictionaries)
        MergedDictionary.reset()
    }

    // a compile killed halfway (the app killed, the phone off) leaves its work and its file half written
    @Test
    fun whatAKilledCompileLeftIsGoneAfterTheNext() {
        File(dictionaries, ".${MergedDictionary.FILE}.build/run0").apply { parentFile!!.mkdirs() }.writeText("half")
        File(dictionaries, "${MergedDictionary.FILE}.tmp").writeText("half")
        // an import's text half written: the sweep's own, the compiler never makes one
        File(dictionaries, "gone.txt.tmp").apply { writeText("half"); setLastModified(OLD) }
        File(dictionaries, "gone${SourceNames.TMP}").apply { writeText("half"); setLastModified(OLD) }
        File(dictionaries, "gone${SourceNames.CONVERT_TMP}").apply { writeText("half"); setLastModified(OLD) }
        // one this process writes (an import's, on the page's thread) is never swept (RN4): its time past the
        // process's start whatever the clock's grain, not left to the order the tests run in (F3609)
        File(dictionaries, "importing${SourceNames.TMP}").apply { writeText("half"); setLastModified(System.currentTimeMillis() + 60_000) }
        source("mine", "你好 ni'hao 0.0\n")
        MergedDictionary.refreshNow(dictionaries)
        assertEquals(1, MergedDictionary.current(dictionaries).file!!.size)
        assertEquals(listOf("importing${SourceNames.TMP}", MergedDictionary.FILE, "mine.txt.gz"), dictionaries.list()!!.sorted())
    }

    // a compile tells how far it is, stage by stage, to its end; cancelled, the file there is
    // what it was, nothing of it left about; a word the base has is kept out of the trie
    @Test
    fun aCompileTellsHowFarAndACancelLeavesTheFileAsItWas() {
        MergedDictionary.base = { MergedDictionary.Base("test base") { { text, _ -> text == "你好" } } }
        val told = ArrayList<MergedDictionary.Work>()
        MergedDictionary.told = { told += it }
        source("mine", "你好 ni'hao 0.0\n搭子 da'zi 0.0\n")
        MergedDictionary.refreshNow(dictionaries)
        val merged = MergedDictionary.current(dictionaries).file!!
        assertEquals(1, merged.size - merged.trieWords)
        assertEquals(listOf(MergedDictionary.Step.READ, MergedDictionary.Step.MERGE, MergedDictionary.Step.WRITE), told.map { it.stage }.distinct())
        assertTrue(told.zipWithNext().all { (a, b) -> a.percent <= b.percent })
        assertNull(MergedDictionary.work.value)
        val before = File(dictionaries, MergedDictionary.FILE).readBytes()
        source("theirs", (1..50_000).joinToString("\n") { "词$it ci 0.0" })
        val cancel = AtomicBoolean()
        MergedDictionary.told = { if (it.percent > 0) cancel.set(true) }
        val cancelled = runCatching { MergedDictionary.refreshNow(dictionaries, cancel) }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        assertTrue(before.contentEquals(File(dictionaries, MergedDictionary.FILE).readBytes()))
        assertEquals(listOf(MergedDictionary.FILE, "mine.txt.gz", "theirs.txt.gz"), dictionaries.list()!!.sorted())
        assertNull(MergedDictionary.work.value)
    }

    // a cancel pressed before the import's compile started (its file still being written, or
    // another compile running ahead of it) stops that one, and no other (review M1)
    @Test
    fun aCancelStopsItsOwnCompileEvenBeforeItStartsAndNoOther() {
        source("mine", "你好 ni'hao 0.0\n")
        MergedDictionary.refreshNow(dictionaries)
        val before = File(dictionaries, MergedDictionary.FILE).readBytes()
        source("theirs", "搭子 da'zi 0.0\n")
        val cancelled = runCatching { MergedDictionary.refreshNow(dictionaries, AtomicBoolean(true)) }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        assertTrue(before.contentEquals(File(dictionaries, MergedDictionary.FILE).readBytes()))
        // another's cancel, set while this one runs, leaves this one be
        val other = AtomicBoolean()
        MergedDictionary.told = { other.set(true) }
        MergedDictionary.refreshNow(dictionaries)
        assertEquals(2, MergedDictionary.current(dictionaries).file!!.size)
    }

    // a compile that failed is told failed again, not compiled again, until retried (the 词库
    // page opened, as the notice says); the engine is told the dictionaries are pending meanwhile (review M3)
    @Test
    fun aFailedCompileIsToldAndCompiledAgainWhenRetried() {
        var fail = true
        MergedDictionary.base = { MergedDictionary.Base("test") { { _, _ -> if (fail) throw IOException("disk full") else false } } }
        val ended = Collections.synchronizedList(ArrayList<Pair<MergedDictionary.Kind, Boolean>>())
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val listening = scope.launch { MergedDictionary.done.collect { ended += it } }
        try {
            source("mine", "你好 ni'hao 0.0\n")
            // an import's compile: its failure thrown to the import, which tells it; none in [done] (one dialog, review)
            assertTrue(runCatching { MergedDictionary.refreshNow(dictionaries) }.exceptionOrNull() is IOException)
            assertTrue(MergedDictionary.pending(dictionaries))
            fail = false
            // not compiled again till retried, and an import asking for it fails rather than "succeed" uncompiled
            assertTrue(runCatching { MergedDictionary.refreshNow(dictionaries) }.exceptionOrNull() is IOException)
            assertNull(MergedDictionary.current(dictionaries).file)
            // nor at the next start, quietly: the same input fails the same way
            MergedDictionary.reset()
            MergedDictionary.made = {}
            MergedDictionary.refresh(dictionaries)!!.get()
            assertNull(MergedDictionary.current(dictionaries).file)
            assertEquals(emptyList<Any>(), ended.toList())
            // a compile of its own (the page's retry) that fails is told
            fail = true
            MergedDictionary.retry(dictionaries)!!.get()
            assertEquals(listOf(MergedDictionary.Kind.UPDATE to false), ended.toList())
            fail = false
            MergedDictionary.retry(dictionaries)!!.get()
            assertEquals(1, MergedDictionary.current(dictionaries).file!!.size)
            assertFalse(MergedDictionary.pending(dictionaries))
        } finally {
            listening.cancel()
        }
    }

    // at a fresh start the file there of the dictionaries as they are is mapped at once, no compile
    // queued to reload the engine mid-typing (review S1)
    @Test
    fun aFreshStartMapsTheFileThereAtOnce() {
        var made = 0
        MergedDictionary.made = { made++ }
        source("mine", "你好 ni'hao 0.0\n")
        MergedDictionary.refreshNow(dictionaries)
        MergedDictionary.reset()
        made = 0
        val seen = MergedDictionary.current(dictionaries)
        assertEquals(1, seen.file!!.size)
        assertTrue(seen.token.startsWith(MergedDictionary.FILE))
        MergedDictionary.refreshNow(dictionaries)
        assertEquals(0, made)
    }

    // the first compile of what an older build left is an upgrade, told so by what it finds: a text
    // to convert, a file of the format before, an upgrade owed; kept ones with none made yet (all
    // turned off, then one on), a damaged file of this format, a file there already, or an import's
    // compile is an update, whatever the files' times (review, F3409, F3644). Each row: why, the
    // files' time, how it is compiled, and what is beside
    @Test
    fun anUpgradeIsToldFromAnUpdate() {
        class Row(
            val why: String,
            val kind: MergedDictionary.Kind,
            val modified: Long,
            val fileThere: Boolean = false,
            val forImport: Boolean = false,
            val asText: Boolean = false,
            val beside: Beside? = null,
        )
        val rows = listOf(
            // 0.3.1's or 0.3.2's, whatever its time (setLastModified fails on sdcardfs) (F3606)
            Row("a text converted", MergedDictionary.Kind.UPGRADE, modified = 3_000_000, asText = true),
            Row("a file of the format before, the sources kept", MergedDictionary.Kind.UPGRADE, modified = 3_000_000, beside = Beside.FORMAT_BEFORE),
            // made again as it was before this change, by openOrDelete: no upgrade to tell of (F3599)
            Row("a damaged file of this format", MergedDictionary.Kind.UPDATE, modified = 1_000_000, beside = Beside.DAMAGED),
            // converted, then killed before the compile was done: nothing left to tell it by but the mark (F3601)
            Row("an upgrade a kill left owed", MergedDictionary.Kind.UPGRADE, modified = 3_000_000, beside = Beside.OWED),
            // a converted gzip keeps its text's old time: no upgrade told by it once all were turned off (F3644)
            Row("kept ones, none made yet", MergedDictionary.Kind.UPDATE, modified = 1_000_000),
            Row("a file there already", MergedDictionary.Kind.UPDATE, modified = 1_000_000, fileThere = true),
            Row("an import's compile", MergedDictionary.Kind.UPDATE, modified = 1_000_000, forImport = true),
        )
        assertRows(rows, { it.why }) { row ->
            done()
            fresh()
            if (row.fileThere) {
                source("kept", "妮 ni 0.0\n")
                MergedDictionary.refreshNow(dictionaries)
                MergedDictionary.reset()
            }
            when (row.beside) {
                Beside.FORMAT_BEFORE -> File(dictionaries, MergedDictionary.FILE).writeBytes(header(formatVersion = 1))
                Beside.DAMAGED -> File(dictionaries, MergedDictionary.FILE).writeBytes(header(formatVersion = 2))
                Beside.OWED -> File(dictionaries, ".merged.upgrading").createNewFile()
                null -> {}
            }
            if (row.asText) text("old", "你好 ni'hao 0.0\n").setLastModified(row.modified) else source("old", "你好 ni'hao 0.0\n").setLastModified(row.modified)
            val kinds = Collections.synchronizedSet(HashSet<MergedDictionary.Kind>())
            MergedDictionary.told = { kinds += it.kind }
            if (row.forImport) MergedDictionary.refreshNow(dictionaries) else MergedDictionary.refresh(dictionaries)!!.get()
            assertEquals(row.why, setOf(row.kind), kinds.toSet())
        }
    }

    private enum class Beside { FORMAT_BEFORE, DAMAGED, OWED }

    // an engine data file's header (DataFile's: magic, container version, kind, sections, the kind's version), nothing after
    private fun header(formatVersion: Int): ByteArray =
        ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).put("FCITXIME".toByteArray()).putInt(1).putInt(0).putInt(0).putInt(formatVersion).array()

    // converted, then killed (here cancelled) before its compile was done: an upgrade still at the next
    // start, shown and told, though no text nor file of the format before is left to tell it by (F3601, F3637)
    @Test
    fun anUpgradeKilledAfterItsConversionIsToldAtTheNextStart() {
        text("old", "你好 ni'hao 0.0\n")
        val cancel = AtomicBoolean()
        MergedDictionary.told = { if (it.stage == MergedDictionary.Step.READ) cancel.set(true) }
        assertTrue(runCatching { MergedDictionary.refreshNow(dictionaries, cancel) }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("old.txt.gz"), dictionaries.list()!!.filter { !MergedDictionary.isCompiled(File(dictionaries, it)) })
        fresh()
        val kinds = Collections.synchronizedSet(HashSet<MergedDictionary.Kind>())
        MergedDictionary.told = { kinds += it.kind }
        val ended = Collections.synchronizedList(ArrayList<Pair<MergedDictionary.Kind, Boolean>>())
        val listening = CoroutineScope(Dispatchers.Unconfined).launch { MergedDictionary.done.collect { ended += it } }
        try {
            MergedDictionary.refresh(dictionaries)!!.get()
            // and told once: what follows is an update
            MergedDictionary.retry(dictionaries)!!.get()
        } finally {
            listening.cancel()
        }
        assertEquals(setOf(MergedDictionary.Kind.UPGRADE), kinds.toSet())
        assertEquals(listOf(MergedDictionary.Kind.UPGRADE to true), ended.toList())
    }

    // a file mapped unchecked at a fresh start that fails its check on the compile thread is
    // dropped, deleted and made again, the engine told (review)
    @Test
    fun aDamagedFileIsMadeAgain() {
        source("mine", "你好 ni'hao 0.0\n搭子 da'zi 0.0\n")
        MergedDictionary.refreshNow(dictionaries)
        val dest = File(dictionaries, MergedDictionary.FILE)
        RandomAccessFile(dest, "rw").use { f ->
            val at = f.length() * 3 / 4
            f.seek(at)
            val b = f.read()
            f.seek(at)
            f.write(b xor 0xff)
        }
        MergedDictionary.reset()
        var made = 0
        MergedDictionary.made = { made++ }
        MergedDictionary.base = { null }
        MergedDictionary.current(dictionaries)
        // the check, then the compile it queued, are done before this one
        MergedDictionary.refreshNow(dictionaries)
        assertTrue(made > 0)
        assertNotNull(DictionaryFile.open(dest))
        assertEquals(2, MergedDictionary.current(dictionaries).file!!.size)
    }

    // a file of another base dictionary (an update's) is used meanwhile, said stale, till its compile is in
    @Test
    fun aFileOfAnotherBaseIsStaleTillCompiledAgain() {
        MergedDictionary.base = { MergedDictionary.Base("one") { { _, _ -> false } } }
        source("mine", "你好 ni'hao 0.0\n")
        MergedDictionary.refreshNow(dictionaries)
        assertFalse(MergedDictionary.current(dictionaries).stale)
        MergedDictionary.base = { MergedDictionary.Base("two") { { _, _ -> false } } }
        val seen = MergedDictionary.current(dictionaries)
        assertNotNull(seen.file)
        assertTrue(seen.stale)
        MergedDictionary.refreshNow(dictionaries)
        assertFalse(MergedDictionary.current(dictionaries).stale)
    }

    // a source turned off or deleted while it was compiled is no failure: not kept as failed, not
    // told, and the compile queued after it makes the file of those left (review)
    @Test
    fun aSourceGoneMidCompileIsNoFailure() {
        source("a", "你好 ni'hao 0.0\n")
        val b = source("b", "搭子 da'zi 0.0\n")
        val ended = Collections.synchronizedList(ArrayList<Pair<MergedDictionary.Kind, Boolean>>())
        val listening = CoroutineScope(Dispatchers.Unconfined).launch { MergedDictionary.done.collect { ended += it } }
        try {
            // deleted as the compile starts, before the second file is opened
            MergedDictionary.told = { b.delete() }
            MergedDictionary.refresh(dictionaries)!!.get()
            MergedDictionary.told = {}
            MergedDictionary.refreshNow(dictionaries)
            assertFalse(ended.contains(MergedDictionary.Kind.UPDATE to false))
            assertFalse(File(dictionaries, ".merged.failed").exists())
            assertEquals(mapOf("a" to 1), MergedDictionary.sourceWords(dictionaries))
        } finally {
            listening.cancel()
        }
    }

    // 0.3.2's texts and its file (another format, refused): the file deleted, the texts gzip'd on or
    // off as they were, compiled, told an upgrade though their times cannot tell (setLastModified
    // fails on sdcardfs: none known here), the conversion shown first
    @Test
    fun anOlderBuildsTextsAreConvertedAndItsFileMadeAgainAsAnUpgrade() {
        text("on", "你好 ni'hao 0.0\n𠀀 ni 0.0\n")
        text("off", "搭子 da'zi 0.0\n", on = false)
        File(dictionaries, MergedDictionary.FILE).writeText("a file of the format before")
        val told = Collections.synchronizedList(ArrayList<MergedDictionary.Work>())
        MergedDictionary.told = { told += it }
        val ended = Collections.synchronizedList(ArrayList<Pair<MergedDictionary.Kind, Boolean>>())
        val works = Collections.synchronizedList(ArrayList<MergedDictionary.Work?>())
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val listening = scope.launch { MergedDictionary.done.collect { ended += it } }
        val watching = scope.launch { MergedDictionary.work.collect { works += it } }
        try {
            refreshed()
        } finally {
            listening.cancel()
            watching.cancel()
        }
        // the bar not emptied between the conversion and the compile, the page not rebuilt there (F3597).
        // Best effort: a StateFlow's collector may miss a null at once overwritten, never see one not set (F3655)
        assertFalse(null in works.dropWhile { it == null }.dropLastWhile { it == null })
        assertEquals(listOf(MergedDictionary.FILE, "off.txt.gz.disable", "on.txt.gz"), dictionaries.list()!!.sorted())
        assertEquals("你好 ni'hao 0.0\n𠀀 ni 0.0\n", gunzip(File(dictionaries, "on.txt.gz")))
        assertEquals("搭子 da'zi 0.0\n", gunzip(File(dictionaries, "off.txt.gz.disable")))
        assertEquals(2, MergedDictionary.current(dictionaries).file!!.size)
        assertEquals(mapOf("on" to 2), MergedDictionary.sourceWords(dictionaries))
        assertEquals(MergedDictionary.Step.entries.toList(), told.map { it.stage }.distinct())
        assertEquals(setOf(MergedDictionary.Kind.UPGRADE), told.map { it.kind }.toSet())
        assertTrue(told.zipWithNext().all { (a, b) -> a.percent <= b.percent })
        assertEquals(listOf(MergedDictionary.Kind.UPGRADE to true), ended.toList())
        assertNull(MergedDictionary.work.value)
    }

    // texts alone, no file and no page opened (0.3.1's, a kill after the file's delete, a 0.3.2
    // backup restored on a fresh install): the engine's start alone has them pending and converted (RM1)
    @Test
    fun textsAloneArePendingAndConvertedFromTheEnginesStart() {
        text("mine", "你好 ni'hao 0.0\n")
        val told = Collections.synchronizedList(ArrayList<MergedDictionary.Work>())
        MergedDictionary.told = { told += it }
        assertTrue(MergedDictionary.pending(dictionaries))
        val seen = MergedDictionary.current(dictionaries)
        assertNull(seen.file)
        assertEquals("mine.txt", seen.token)
        // queued by current(): this one runs after it, an import's (an UPDATE), with nothing left to convert
        MergedDictionary.refreshNow(dictionaries)
        assertTrue(MergedDictionary.Work(MergedDictionary.Kind.UPGRADE, MergedDictionary.Step.CONVERT, 0) in told)
        assertFalse(MergedDictionary.pending(dictionaries))
        assertEquals(1, MergedDictionary.current(dictionaries).file!!.size)
    }

    // only texts off converted, the file of those on as it was: the upgrade told done all the same, not
    // left at 正在升级 (F3597)
    @Test
    fun anUpgradeOfTextsOffAloneIsToldDone() {
        source("on", "你好 ni'hao 0.0\n")
        MergedDictionary.refreshNow(dictionaries)
        text("off", "搭子 da'zi 0.0\n", on = false)
        val ended = Collections.synchronizedList(ArrayList<Pair<MergedDictionary.Kind, Boolean>>())
        val listening = CoroutineScope(Dispatchers.Unconfined).launch { MergedDictionary.done.collect { ended += it } }
        try {
            refreshed()
        } finally {
            listening.cancel()
        }
        assertEquals(listOf(MergedDictionary.Kind.UPGRADE to true), ended.toList())
        assertEquals(listOf(MergedDictionary.FILE, "off.txt.gz.disable", "on.txt.gz"), dictionaries.list()!!.sorted())
    }

    // a backup's texts restored beside the file there: it is used meanwhile, not none till they are converted (F3604)
    @Test
    fun theFileThereIsUsedWhileRestoredTextsConvert() {
        source("kept", "你好 ni'hao 0.0\n")
        MergedDictionary.refreshNow(dictionaries)
        fresh()
        text("restored", "搭子 da'zi 0.0\n")
        assertEquals(1, MergedDictionary.current(dictionaries).file?.size)
        MergedDictionary.refreshNow(dictionaries)
        assertEquals(2, MergedDictionary.current(dictionaries).file!!.size)
    }

    // a kept dictionary whose gzip is damaged (no gzip at all, or cut short): skipped, the others
    // compiled, the page told which; no longer a failure of the whole compile, every imported word
    // gone with it, and retrying does not hide it (F3593)
    @Test
    fun aDamagedGzipIsSkippedNotTheWholeCompile() {
        source("good", "你好 ni'hao 0.0\n")
        File(dictionaries, "bad.txt.gz").writeText("no gzip")
        // 3125 words of five 妮-like characters: enough that half the gzip holds whole lines
        val ni = "你妮泥尼拟"
        val words = (0 until 3125).map { n -> (0 until 5).map { ni[n / pow5[it] % 5] }.joinToString("") }
        val whole = source("cut", words.joinToString("") { "$it ni'ni'ni'ni'ni 0.0\n" }).readBytes()
        File(dictionaries, "cut.txt.gz").writeBytes(whole.copyOf(whole.size / 2))
        refreshed()
        assertEquals(1, MergedDictionary.sourceWords(dictionaries)["good"])
        // what was read of it before the damage kept
        assertTrue(MergedDictionary.sourceWords(dictionaries).toString(), (MergedDictionary.sourceWords(dictionaries)["cut"] ?: 0) > 0)
        assertEquals(setOf("bad", "cut"), MergedDictionary.unreadable(dictionaries))
        assertFalse(File(dictionaries, ".merged.failed").exists())
        MergedDictionary.retry(dictionaries)!!.get()
        assertEquals(setOf("bad", "cut"), MergedDictionary.unreadable(dictionaries))
        // deleted and imported again, as the page says: its words in, no longer named (F3652)
        File(dictionaries, "bad.txt.gz").delete()
        source("bad", "搭子 da'zi 0.0\n")
        refreshed()
        assertEquals(setOf("cut"), MergedDictionary.unreadable(dictionaries))
        assertEquals(1, MergedDictionary.sourceWords(dictionaries)["bad"])
    }

    // a text whose gzip could not be written (the disk full; here a directory where its tmp goes):
    // kept as it was, named as not converted for want of space, never as unreadable (whose page says
    // to delete it); not tried again till the page opens, which converts it once there is room (F3645)
    @Test
    fun aTextNotConvertedForSpaceIsKeptAndNotCalledUnreadable() {
        text("big", "你好 ni'hao 0.0\n")
        File(dictionaries, "big${SourceNames.CONVERT_TMP}").mkdir()
        val ended = Collections.synchronizedList(ArrayList<Pair<MergedDictionary.Kind, Boolean>>())
        val listening = CoroutineScope(Dispatchers.Unconfined).launch { MergedDictionary.done.collect { ended += it } }
        try {
            MergedDictionary.refresh(dictionaries)!!.get()
        } finally {
            listening.cancel()
        }
        assertEquals(listOf(MergedDictionary.Kind.UPGRADE to false), ended.toList())
        assertEquals(setOf("big"), MergedDictionary.unconverted(dictionaries))
        assertEquals(emptySet<String>(), MergedDictionary.unreadable(dictionaries))
        assertEquals("你好 ni'hao 0.0\n", File(dictionaries, "big.txt").readText())
        // the engine's next start: not tried again
        fresh()
        MergedDictionary.current(dictionaries)
        MergedDictionary.refresh(dictionaries)?.get()
        assertTrue(File(dictionaries, "big.txt").exists())
        // noted, not converted till the page opens; its words still owed, so pending meanwhile: a word
        // only it has is not forgotten as none of theirs (F3677, which F3668's skip had undone)
        assertTrue(MergedDictionary.pending(dictionaries))
        // the page opened: converted. Not for room made here: the conversion's finally already took the
        // directory off the tmp's place; the note alone kept it from being tried again till the retry (F3670)
        refreshed()
        assertEquals(emptySet<String>(), MergedDictionary.unconverted(dictionaries))
        assertEquals(mapOf("big" to 1), MergedDictionary.sourceWords(dictionaries))
    }

    // where a kill or a restore leaves a dictionary: each row the files there, and what is left (M2, RN3, RN4)
    @Test
    fun aDictionaryIsWholeAfterAKillOrARestore() {
        class Row(val why: String, val files: Map<String, String>, val times: Map<String, Long>, val left: Map<String, String>)
        val rows = listOf(
            // killed between the rename and the text's delete; or a downgrade's import of its name
            Row("text and kept", mapOf("a.txt" to "new", "a.txt.gz" to "old"), emptyMap(), mapOf("a.txt.gz" to "new")),
            // a 0.3.2 backup restored over one kept on, the text off
            Row("text off over kept on", mapOf("a.txt.disable" to "new", "a.txt.gz" to "old"), emptyMap(), mapOf("a.txt.gz.disable" to "new")),
            // a backup restored over the other state: the newer
            Row("kept on and off", mapOf("a.txt.gz" to "old", "a.txt.gz.disable" to "new"), mapOf("a.txt.gz" to 1_000_000L, "a.txt.gz.disable" to 2_000_000L), mapOf("a.txt.gz.disable" to "new")),
            Row("kept on and off, one time", mapOf("a.txt.gz" to "on", "a.txt.gz.disable" to "off"), mapOf("a.txt.gz" to 1_000_000L, "a.txt.gz.disable" to 1_000_000L), mapOf("a.txt.gz" to "on")),
            // a conversion killed halfway, in a process before
            Row("its tmp left", mapOf("a.txt" to "new", "a${SourceNames.CONVERT_TMP}" to "half"), mapOf("a${SourceNames.CONVERT_TMP}" to OLD), mapOf("a.txt.gz" to "new")),
            // 0.3.2's backup of the other state restored over its texts: the newer, the on one at one time, never the off one always (F3596)
            Row("texts on and off", mapOf("a.txt" to "new", "a.txt.disable" to "old"), mapOf("a.txt" to 2_000_000L, "a.txt.disable" to 1_000_000L), mapOf("a.txt.gz" to "new")),
            Row("texts on and off, one time", mapOf("a.txt" to "on", "a.txt.disable" to "off"), mapOf("a.txt" to 1_000_000L, "a.txt.disable" to 1_000_000L), mapOf("a.txt.gz" to "on")),
        )
        assertRows(rows, { it.why }) { row ->
            done()
            fresh()
            for ((name, content) in row.files) {
                val file = File(dictionaries, name)
                if (name.endsWith(".gz") || name.endsWith(".gz.disable")) source(SourceNames.nameOf(name), "$content ni 0.0\n", SourceNames.isOn(name)) else file.writeText("$content ni 0.0\n")
                row.times[name]?.let { file.setLastModified(it) }
            }
            refreshed()
            val left = dictionaries.list()!!.filter { it != MergedDictionary.FILE }.sorted()
            assertEquals(row.why, row.left.keys.sorted(), left)
            for ((name, content) in row.left) assertEquals(row.why, "$content ni 0.0\n", gunzip(File(dictionaries, name)))
        }
    }

    // turned off and deleted, by name, while their texts were converted: as the page asked, not undone by the commit (RS1)
    @Test
    fun aTextTurnedOrDeletedAsItIsConvertedStaysSo() {
        text("a", "你好 ni'hao 0.0\n")
        text("b", "搭子 da'zi 0.0\n")
        val once = AtomicBoolean()
        MergedDictionary.told = {
            // a read, not yet committed: its rename keeps its length and time
            if (it.stage == MergedDictionary.Step.CONVERT && it.percent > 0 && once.compareAndSet(false, true)) {
                assertTrue(ImportedDictionaries.turn(dictionaries, "a", false))
                assertTrue(ImportedDictionaries.delete(dictionaries, "b"))
            }
        }
        refreshed()
        assertEquals(listOf("a.txt.gz.disable"), dictionaries.list()!!.sorted())
    }

    // a text that cannot be read: skipped, the others converted and compiled, the upgrade told failed
    // and the page told which; not converted again at each start or reload, each time told again (F3592);
    // the pass's guard (design §4 Commit: "not here for ever") ends it, then the 词库 page's retry converts it
    @Test(timeout = 20_000)
    fun aTextThatCannotBeReadIsSkippedNamedAndNotTriedAgain() {
        val locked = text("locked", "你好 ni'hao 0.0\n")
        assumeTrue(locked.setReadable(false) && !locked.canRead())
        text("fine", "搭子 da'zi 0.0\n")
        val ended = Collections.synchronizedList(ArrayList<Pair<MergedDictionary.Kind, Boolean>>())
        val told = Collections.synchronizedList(ArrayList<MergedDictionary.Work>())
        val listening = CoroutineScope(Dispatchers.Unconfined).launch { MergedDictionary.done.collect { ended += it } }
        try {
            refreshed()
            assertEquals(listOf(MergedDictionary.Kind.UPGRADE to false), ended.toList())
            assertEquals(setOf("locked"), MergedDictionary.unreadable(dictionaries))
            assertEquals(mapOf("fine" to 1), MergedDictionary.sourceWords(dictionaries))
            assertEquals(listOf("fine.txt.gz", "locked.txt"), dictionaries.list()!!.filter { !MergedDictionary.isCompiled(File(dictionaries, it)) }.sorted())
            assertNull(MergedDictionary.work.value)
            // the engine's next start: the file of those converted at once, nothing converted again, nothing told
            fresh()
            MergedDictionary.told = { told += it }
            assertNotNull(MergedDictionary.current(dictionaries).file)
            // its words owed till it is read: pending (F3677, F3684)
            assertTrue(MergedDictionary.pending(dictionaries))
            // the engine's start, which tells its end in done (an import's never does) (F3651)
            checked()
            assertTrue(told.none { it.stage == MergedDictionary.Step.CONVERT })
            assertEquals(1, ended.size)
            // the page opened, the text readable now: converted
            locked.setReadable(true)
            MergedDictionary.retry(dictionaries)!!.get()
            assertEquals(emptySet<String>(), MergedDictionary.unreadable(dictionaries))
            assertEquals(mapOf("fine" to 1, "locked" to 1), MergedDictionary.sourceWords(dictionaries))
        } finally {
            listening.cancel()
            locked.setReadable(true)
        }
    }

    // a text converted whose delete was refused (F3660), as a later start finds it: noted in `.merged.left`
    // beside its gzip. Not pending, not converted again, no upgrade told; its delete tried again,
    // the note dropped once it is gone. Turned with its gzip (they are turned together), the same (F3686)
    @Test
    fun aTextLeftAfterItsConversionIsDeletedAgainNotConverted() {
        val ended = Collections.synchronizedList(ArrayList<Pair<MergedDictionary.Kind, Boolean>>())
        val told = Collections.synchronizedList(ArrayList<MergedDictionary.Work>())
        MergedDictionary.told = { told += it }
        val listening = CoroutineScope(Dispatchers.Unconfined).launch { MergedDictionary.done.collect { ended += it } }
        try {
            val rows = listOf(
                // as the conversion left it
                false to "on",
                // turned off on the page before the compile that deletes it: its gzip turned with it (F3686)
                true to "turned off",
            )
            assertRows(rows, { it.second }) { (turned, _) ->
                try {
                    source("frost", "你好 ni'hao 0.0\n")
                    MergedDictionary.refreshNow(dictionaries)
                    val left = text("frost", "你好 ni'hao 0.0\n")
                    File(dictionaries, ".merged.left").writeText(MergedDictionary.leftKey(left) + "\n")
                    if (turned) assertTrue(ImportedDictionaries.turn(dictionaries, "frost", false))
                    // its words are in the gzip, compiled: nothing owed (F3684)
                    assertFalse(MergedDictionary.pending(dictionaries))
                    MergedDictionary.current(dictionaries)
                    checked()
                    assertTrue(told.none { it.stage == MergedDictionary.Step.CONVERT })
                    assertTrue(ended.none { it.first == MergedDictionary.Kind.UPGRADE })
                    assertEquals(listOf(SourceNames.fileName("frost", !turned)), dictionaries.list()!!.filter { it.startsWith("frost") })
                    assertFalse(File(dictionaries, ".merged.left").exists())
                    // each row from a clean start, one failed leaving nothing to the next (F3697)
                    done()
                } catch (e: Throwable) {
                    // done() throwing on what a failed row left must not hide the row's own failure (F3708)
                    runCatching { done() }.exceptionOrNull()?.let(e::addSuppressed)
                    throw e
                } finally {
                    MergedDictionary.told = { told += it }
                    told.clear()
                    ended.clear()
                }
            }
        } finally {
            listening.cancel()
        }
    }

    // a text noted left whose gzip of its own state is not there: the note dropped, the text converted
    // like any other, an upgrade told; its gzip of the other state replaced, the text's state kept (F3674, F3675, F3691)
    @Test
    fun aTextLeftWithoutItsGzipIsConvertedAgain() {
        val ended = Collections.synchronizedList(ArrayList<Pair<MergedDictionary.Kind, Boolean>>())
        val listening = CoroutineScope(Dispatchers.Unconfined).launch { MergedDictionary.done.collect { ended += it } }
        try {
            val rows = listOf(
                // deleted outside the page (the page deletes both or neither, F3686)
                false to "its gzip deleted",
                // only the other state's there: a newer backup restored over it
                true to "its gzip in the other state",
            )
            assertRows(rows, { it.second }) { (otherThere, _) ->
                try {
                    val left = text("frost", "搭子 da'zi 0.0\n")
                    if (otherThere) source("frost", "你好 ni'hao 0.0\n", on = false)
                    File(dictionaries, ".merged.left").writeText(MergedDictionary.leftKey(left) + "\n")
                    ended.clear()
                    assertTrue(MergedDictionary.pending(dictionaries))
                    refreshed()
                    assertEquals(MergedDictionary.Kind.UPGRADE to true, ended.last())
                    assertEquals("搭子 da'zi 0.0\n", gunzip(File(dictionaries, "frost.txt.gz")))
                    assertEquals(listOf("frost.txt.gz"), dictionaries.list()!!.filter { it.startsWith("frost") })
                    assertFalse(File(dictionaries, ".merged.left").exists())
                    done()
                } catch (e: Throwable) {
                    // as above (F3708)
                    runCatching { done() }.exceptionOrNull()?.let(e::addSuppressed)
                    throw e
                }
            }
        } finally {
            listening.cancel()
        }
    }

    // backups leave out what is made from the dictionaries, a conversion's tmp too, not the dictionaries (S5). Each row: a file, whether it is left out
    @Test
    fun aBackupHasTheDictionariesNotWhatIsMadeOfThem() {
        val rows = listOf(
            "a.txt.gz" to false,
            "a.txt.gz.disable" to false,
            "a.txt" to false,
            "a${SourceNames.TMP}" to true,
            "a${SourceNames.CONVERT_TMP}" to true,
            "a.txt.tmp" to true,
            MergedDictionary.FILE to true,
            // a failure's, the unreadable and the unconverted noted, an upgrade owed: this phone's, not the next's (F3592, F3601, F3645)
            ".merged.failed" to true,
            ".merged.unreadable" to true,
            ".merged.unwritten" to true,
            ".merged.upgrading" to true,
            // a text left after its conversion, its delete refused: this phone's refusal (F3660, F3678)
            ".merged.left" to true,
        )
        assertRows(rows, { it.first }) { (name, compiled) ->
            assertEquals(name, compiled, MergedDictionary.isCompiled(File(dictionaries, name)))
        }
    }

    private companion object {
        val pow5 = intArrayOf(1, 5, 25, 125, 625)

        // a time before this process's start: what a process killed before left
        const val OLD = 1_000_000L
    }
}
