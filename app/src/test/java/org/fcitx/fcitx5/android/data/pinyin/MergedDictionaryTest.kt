/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.pack.TestShelves
import org.fcitx.fcitx5.android.data.pinyin.dict.SourceNames
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.pack.ImportResult
import org.fcitx.fcitx5.android.pack.PackKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

class MergedDictionaryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dictionaries get() = File(folder.root, "dictionaries").apply { mkdirs() }

    private fun source(name: String, text: String, on: Boolean = true) =
        File(dictionaries, SourceNames.fileName(name, on)).apply { GZIPOutputStream(outputStream()).use { it.write(text.toByteArray()) } }

    // as 0.3.2 and older kept it
    private fun text(name: String, text: String, on: Boolean = true) = File(dictionaries, SourceNames.textName(name, on)).apply { writeText(text) }

    private fun gunzip(file: File) = GZIPInputStream(file.inputStream()).use { it.readBytes().decodeToString() }

    // compiled and told as an engine start's compile, not an import's
    private fun refreshed() = MergedDictionary.retry(dictionaries)!!.get()

    // refresh is null while a queued compile waits; a loop, as generateSequence ends at the first null
    private fun checked(): Any? {
        val until = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < until) {
            MergedDictionary.refresh(dictionaries)?.let { return it.get() }
            Thread.sleep(5)
        }
        error("no refresh queued in 10 s")
    }

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

    /** A table row from a clean start: a failed one leaves nothing to the next. */
    @Suppress("TooGenericExceptionCaught")
    private fun cleanRow(row: () -> Unit) {
        try {
            row()
            done()
        } catch (e: Throwable) {
            // done() throwing must not hide the row's own failure
            runCatching { done() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
    }

    @Test
    fun whatAKilledCompileLeftIsGoneAfterTheNext() {
        File(dictionaries, ".${MergedDictionary.FILE}.build/run0").apply { parentFile!!.mkdirs() }.writeText("half")
        File(dictionaries, "${MergedDictionary.FILE}.tmp").writeText("half")
        // an import's text half written: the sweep's own, the compiler never makes one
        File(dictionaries, "gone.txt.tmp").apply { writeText("half"); setLastModified(OLD) }
        File(dictionaries, "gone${SourceNames.TMP}").apply { writeText("half"); setLastModified(OLD) }
        File(dictionaries, "gone${SourceNames.CONVERT_TMP}").apply { writeText("half"); setLastModified(OLD) }
        // one this process writes is never swept, whatever the clock's grain
        File(dictionaries, "importing${SourceNames.TMP}").apply { writeText("half"); setLastModified(System.currentTimeMillis() + 60_000) }
        source("mine", "你好 ni'hao 0.0\n")
        MergedDictionary.refreshNow(dictionaries)
        assertEquals(1, MergedDictionary.current(dictionaries).file!!.size)
        assertEquals(listOf("importing${SourceNames.TMP}", MergedDictionary.FILE, "mine.txt.gz"), dictionaries.list()!!.sorted())
    }

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

    // cancelled before the import's compile started
    @Test
    fun aCancelStopsItsOwnCompileEvenBeforeItStartsAndNoOther() {
        source("mine", "你好 ni'hao 0.0\n")
        MergedDictionary.refreshNow(dictionaries)
        val before = File(dictionaries, MergedDictionary.FILE).readBytes()
        source("theirs", "搭子 da'zi 0.0\n")
        val cancelled = runCatching { MergedDictionary.refreshNow(dictionaries, AtomicBoolean(true)) }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        assertTrue(before.contentEquals(File(dictionaries, MergedDictionary.FILE).readBytes()))
        // another's cancel leaves this one be
        val other = AtomicBoolean()
        MergedDictionary.told = { other.set(true) }
        MergedDictionary.refreshNow(dictionaries)
        assertEquals(2, MergedDictionary.current(dictionaries).file!!.size)
    }

    @Test
    fun aFailedCompileIsToldAndCompiledAgainWhenRetried() {
        var fail = true
        MergedDictionary.base = { MergedDictionary.Base("test") { { _, _ -> if (fail) throw IOException("disk full") else false } } }
        val ended = Collections.synchronizedList(ArrayList<Pair<MergedDictionary.Kind, Boolean>>())
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val listening = scope.launch { MergedDictionary.done.collect { ended += it } }
        try {
            source("mine", "你好 ni'hao 0.0\n")
            // an import's compile: its failure thrown to the import; none in [done]
            assertTrue(runCatching { MergedDictionary.refreshNow(dictionaries) }.exceptionOrNull() is IOException)
            assertTrue(MergedDictionary.pending(dictionaries))
            fail = false
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

    // no compile queued to reload the engine mid-typing
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

    // an upgrade is told by what it finds (a text to convert, an old-format file, an upgrade owed), whatever the times
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
            // whatever its time (setLastModified fails on sdcardfs)
            Row("a text converted", MergedDictionary.Kind.UPGRADE, modified = 3_000_000, asText = true),
            Row("a file of the format before, the sources kept", MergedDictionary.Kind.UPGRADE, modified = 3_000_000, beside = Beside.FORMAT_BEFORE),
            // rebuilt by openOrDelete: no upgrade to tell of
            Row("a damaged file of this format", MergedDictionary.Kind.UPDATE, modified = 1_000_000, beside = Beside.DAMAGED),
            // killed before the compile: nothing left to tell it by but the mark
            Row("an upgrade a kill left owed", MergedDictionary.Kind.UPGRADE, modified = 3_000_000, beside = Beside.OWED),
            // a converted gzip keeps its text's old time: no upgrade told by it
            Row("kept ones, none made yet", MergedDictionary.Kind.UPDATE, modified = 1_000_000),
            Row("a file there already", MergedDictionary.Kind.UPDATE, modified = 1_000_000, fileThere = true),
            Row("an import's compile", MergedDictionary.Kind.UPDATE, modified = 1_000_000, forImport = true),
            // its conversion made the marker, its compile is done: none left for the next start
            Row("an import's compile converting a text", MergedDictionary.Kind.UPDATE, modified = 1_000_000, forImport = true, asText = true),
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
            if (row.forImport && row.asText) {
                assertFalse(row.why, File(dictionaries, ".merged.upgrading").exists())
                fresh()
                kinds.clear()
                MergedDictionary.told = { kinds += it.kind }
                checked()
                assertFalse(row.why, MergedDictionary.Kind.UPGRADE in kinds)
            }
        }
    }

    private enum class Beside { FORMAT_BEFORE, DAMAGED, OWED }

    // DataFile's header as 0.3.1/0.3.2 wrote it. Literals, not DataFile's constants: a later bump must still read a real file
    private fun header(formatVersion: Int): ByteArray =
        ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).put("FCITXIME".toByteArray()).putInt(2)
            .putInt(4).putInt(0).putInt(formatVersion).array()

    // killed after converting: no text nor old-format file is left to tell the upgrade by
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

    // mapped unchecked, failing its check on the compile thread: deleted, rebuilt, the engine told
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
        MergedDictionary.refreshNow(dictionaries)
        assertTrue(made > 0)
        assertNotNull(DictionaryFile.open(JvmFile(dest)))
        assertEquals(2, MergedDictionary.current(dictionaries).file!!.size)
    }

    // read again when another text pack is imported, even before PackStore counts it; not for a voice pack
    @Test
    fun theTextPacksBaseIsReadOnceATextPack() {
        val reading = MergedDictionary.readPackBase
        var reads = 0
        MergedDictionary.readPackBase = { reads++; MergedDictionary.Base("read $reads") { { _, _ -> false } } }
        try {
            val shelf = TestShelves.use(text = true, voice = false)
            val first = MergedDictionary.packBase()
            assertSame(first, MergedDictionary.packBase())
            assertEquals(1, reads)
            assertTrue(shelf.import(TestShelves.pack(PackKind.VOICE).inputStream()) is ImportResult.Imported)
            PackStore.useShelf(shelf)
            assertSame(first, MergedDictionary.packBase())
            assertEquals(1, reads)
            assertTrue(shelf.import(TestShelves.pack(PackKind.TEXT, version = 2).inputStream()) is ImportResult.Imported)
            assertEquals("read 2", MergedDictionary.packBase()?.fingerprint)
        } finally {
            MergedDictionary.readPackBase = reading
            TestShelves.use()
        }
    }

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

    // not kept as failed, not told, and the compile queued after makes the file of those left
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

    // 0.3.2's texts and refused file: the file deleted, texts gzip'd, told an upgrade though times cannot tell
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
        // the bar not emptied between conversion and compile. Best effort: a StateFlow collector may miss a null
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

    // no file and no page opened (0.3.1's, a kill after the delete, a 0.3.2 backup on a fresh install)
    @Test
    fun textsAloneArePendingAndConvertedFromTheEnginesStart() {
        text("mine", "你好 ni'hao 0.0\n")
        val told = Collections.synchronizedList(ArrayList<MergedDictionary.Work>())
        MergedDictionary.told = { told += it }
        assertTrue(MergedDictionary.pending(dictionaries))
        val seen = MergedDictionary.current(dictionaries)
        assertNull(seen.file)
        assertEquals("mine.txt", seen.token)
        // queued by current(): this import's UPDATE runs after it with nothing left to convert
        MergedDictionary.refreshNow(dictionaries)
        assertTrue(MergedDictionary.Work(MergedDictionary.Kind.UPGRADE, MergedDictionary.Step.CONVERT, 0) in told)
        assertFalse(MergedDictionary.pending(dictionaries))
        assertEquals(1, MergedDictionary.current(dictionaries).file!!.size)
    }

    // not left at 正在升级
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

    // no gzip at all, or cut short: the page told which, and retrying does not hide it
    @Test
    fun aDamagedGzipIsSkippedNotTheWholeCompile() {
        source("good", "你好 ni'hao 0.0\n")
        File(dictionaries, "bad.txt.gz").writeText("no gzip")
        // enough that half the gzip holds whole lines
        val ni = "你妮泥尼拟"
        val words = (0 until 3125).map { n -> (0 until 5).map { ni[n / pow5[it] % 5] }.joinToString("") }
        val whole = source("cut", words.joinToString("") { "$it ni'ni'ni'ni'ni 0.0\n" }).readBytes()
        File(dictionaries, "cut.txt.gz").writeBytes(whole.copyOf(whole.size / 2))
        refreshed()
        assertEquals(1, MergedDictionary.sourceWords(dictionaries)["good"])
        assertTrue(MergedDictionary.sourceWords(dictionaries).toString(), (MergedDictionary.sourceWords(dictionaries)["cut"] ?: 0) > 0)
        assertEquals(setOf("bad", "cut"), MergedDictionary.unreadable(dictionaries))
        assertFalse(File(dictionaries, ".merged.failed").exists())
        MergedDictionary.retry(dictionaries)!!.get()
        assertEquals(setOf("bad", "cut"), MergedDictionary.unreadable(dictionaries))
        // deleted and imported again, as the page says: its words in, no longer named
        File(dictionaries, "bad.txt.gz").delete()
        source("bad", "搭子 da'zi 0.0\n")
        refreshed()
        assertEquals(setOf("cut"), MergedDictionary.unreadable(dictionaries))
        assertEquals(1, MergedDictionary.sourceWords(dictionaries)["bad"])
    }

    // disk full: named as not converted for want of space, never unreadable (whose page says delete it); retried on the page
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
        // its words still owed, so pending
        assertTrue(MergedDictionary.pending(dictionaries))
        // the page opened: converted; only the note kept it from being retried
        refreshed()
        assertEquals(emptySet<String>(), MergedDictionary.unconverted(dictionaries))
        assertEquals(mapOf("big" to 1), MergedDictionary.sourceWords(dictionaries))
    }

    @Test
    fun aDictionaryIsWholeAfterAKillOrARestore() {
        class Row(val why: String, val files: Map<String, String>, val times: Map<String, Long>, val left: Map<String, String>)
        val rows = listOf(
            // killed between the rename and the text's delete; or a downgrade's import of its name
            Row("text and kept", mapOf("a.txt" to "new", "a.txt.gz" to "old"), emptyMap(), mapOf("a.txt.gz" to "new")),
            // an old backup restored over one kept on, the text off
            Row("text off over kept on", mapOf("a.txt.disable" to "new", "a.txt.gz" to "old"), emptyMap(), mapOf("a.txt.gz.disable" to "new")),
            // a backup restored over the other state: the newer
            Row("kept on and off", mapOf("a.txt.gz" to "old", "a.txt.gz.disable" to "new"), mapOf("a.txt.gz" to 1_000_000L, "a.txt.gz.disable" to 2_000_000L), mapOf("a.txt.gz.disable" to "new")),
            Row("kept on and off, one time", mapOf("a.txt.gz" to "on", "a.txt.gz.disable" to "off"), mapOf("a.txt.gz" to 1_000_000L, "a.txt.gz.disable" to 1_000_000L), mapOf("a.txt.gz" to "on")),
            // a conversion killed halfway, in a process before
            Row("its tmp left", mapOf("a.txt" to "new", "a${SourceNames.CONVERT_TMP}" to "half"), mapOf("a${SourceNames.CONVERT_TMP}" to OLD), mapOf("a.txt.gz" to "new")),
            // the newer, the on one on a tie, never always the off one
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

    // by name: as the page asked, not undone by the commit
    @Test
    fun aTextTurnedOrDeletedAsItIsConvertedStaysSo() {
        text("a", "你好 ni'hao 0.0\n")
        text("b", "搭子 da'zi 0.0\n")
        val once = AtomicBoolean()
        MergedDictionary.told = {
            // read, not yet committed: its rename keeps its length and time
            if (it.stage == MergedDictionary.Step.CONVERT && it.percent > 0 && once.compareAndSet(false, true)) {
                assertTrue(ImportedDictionaries.turn(dictionaries, "a", false))
                assertTrue(ImportedDictionaries.delete(dictionaries, "b"))
            }
        }
        refreshed()
        assertEquals(listOf("a.txt.gz.disable"), dictionaries.list()!!.sorted())
    }

    // the upgrade told failed and the page told which; not reconverted each start, then the page's retry converts it
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
            // the engine's next start: nothing reconverted, nothing told
            fresh()
            MergedDictionary.told = { told += it }
            assertNotNull(MergedDictionary.current(dictionaries).file)
            assertTrue(MergedDictionary.pending(dictionaries))
            // the engine's start tells its end in done (an import's never does)
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

    // not tried again at the next start. "locked" sorts first, so it is tried before "mine" is read
    @Test(timeout = 20_000)
    fun aConversionCancelledKeepsTheUnreadableNamed() {
        val locked = text("locked", "你好 ni'hao 0.0\n")
        assumeTrue(locked.setReadable(false) && !locked.canRead())
        try {
            text("mine", (1..50_000).joinToString("\n") { "词$it ci 0.0" })
            val cancel = AtomicBoolean()
            MergedDictionary.told = { if (it.stage == MergedDictionary.Step.CONVERT && it.percent > 0) cancel.set(true) }
            assertTrue(runCatching { MergedDictionary.refreshNow(dictionaries, cancel) }.exceptionOrNull() is CancellationException)
            assertEquals(setOf("locked"), MergedDictionary.unreadable(dictionaries))
        } finally {
            locked.setReadable(true)
        }
    }

    // noted in `.merged.left`: not pending, no upgrade told, the delete retried and the note dropped once it is gone
    @Test
    fun aTextLeftAfterItsConversionIsDeletedAgainNotConverted() {
        val ended = Collections.synchronizedList(ArrayList<Pair<MergedDictionary.Kind, Boolean>>())
        val told = Collections.synchronizedList(ArrayList<MergedDictionary.Work>())
        MergedDictionary.told = { told += it }
        val listening = CoroutineScope(Dispatchers.Unconfined).launch { MergedDictionary.done.collect { ended += it } }
        try {
            val rows = listOf(
                false to "on",
                // turned off before the compile that deletes it
                true to "turned off",
            )
            assertRows(rows, { it.second }) { (turned, _) ->
                try {
                    cleanRow {
                        source("frost", "你好 ni'hao 0.0\n")
                        MergedDictionary.refreshNow(dictionaries)
                        val left = text("frost", "你好 ni'hao 0.0\n")
                        File(dictionaries, ".merged.left").writeText(LegacyTextConversion.leftKey(left) + "\n")
                        if (turned) assertTrue(ImportedDictionaries.turn(dictionaries, "frost", false))
                        assertFalse(MergedDictionary.pending(dictionaries))
                        MergedDictionary.current(dictionaries)
                        checked()
                        assertTrue(told.none { it.stage == MergedDictionary.Step.CONVERT })
                        assertTrue(ended.none { it.first == MergedDictionary.Kind.UPGRADE })
                        assertEquals(listOf(SourceNames.fileName("frost", !turned)), dictionaries.list()!!.filter { it.startsWith("frost") })
                        assertFalse(File(dictionaries, ".merged.left").exists())
                    }
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

    // the note dropped, the text converted like any other, an upgrade told; the text's state kept
    @Test
    fun aTextLeftWithoutItsGzipIsConvertedAgain() {
        val ended = Collections.synchronizedList(ArrayList<Pair<MergedDictionary.Kind, Boolean>>())
        val listening = CoroutineScope(Dispatchers.Unconfined).launch { MergedDictionary.done.collect { ended += it } }
        try {
            val rows = listOf(
                // deleted outside the page (the page deletes both or neither)
                false to "its gzip deleted",
                // a newer backup restored over it
                true to "its gzip in the other state",
            )
            assertRows(rows, { it.second }) { (otherThere, _) ->
                cleanRow {
                    val left = text("frost", "搭子 da'zi 0.0\n")
                    if (otherThere) source("frost", "你好 ni'hao 0.0\n", on = false)
                    File(dictionaries, ".merged.left").writeText(LegacyTextConversion.leftKey(left) + "\n")
                    ended.clear()
                    assertTrue(MergedDictionary.pending(dictionaries))
                    refreshed()
                    assertEquals(MergedDictionary.Kind.UPGRADE to true, ended.last())
                    assertEquals("搭子 da'zi 0.0\n", gunzip(File(dictionaries, "frost.txt.gz")))
                    assertEquals(listOf("frost.txt.gz"), dictionaries.list()!!.filter { it.startsWith("frost") })
                    assertFalse(File(dictionaries, ".merged.left").exists())
                }
            }
        } finally {
            listening.cancel()
        }
    }

    // a conversion's tmp is left out too
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
            // these notes are this phone's, not the next's
            ".merged.failed" to true,
            ".merged.unreadable" to true,
            ".merged.unwritten" to true,
            ".merged.upgrading" to true,
            ".merged.left" to true,
        )
        assertRows(rows, { it.first }) { (name, compiled) ->
            assertEquals(name, compiled, MergedDictionary.isCompiled(File(dictionaries, name)))
        }
    }

    private companion object {
        val pow5 = intArrayOf(1, 5, 25, 125, 625)

        // before this process's start: what a killed process left
        const val OLD = 1_000_000L
    }
}
