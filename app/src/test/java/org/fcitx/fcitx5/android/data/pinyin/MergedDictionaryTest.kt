/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The imported dictionaries compiled into one file: how far a compile is, a cancel, a failure told
 * and retried, what a killed one left swept, a fresh start's file mapped at once and one damaged
 * made again, an upgrade told from an update, and a source gone mid-compile no failure.
 */
class MergedDictionaryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dictionaries get() = File(folder.root, "dictionaries").apply { mkdirs() }

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
        File(dictionaries, "gone.txt.tmp").writeText("half")
        File(dictionaries, "mine.txt").writeText("你好 ni'hao 0.0\n")
        MergedDictionary.refreshNow(dictionaries)
        assertEquals(1, MergedDictionary.current(dictionaries).file!!.size)
        assertEquals(listOf(MergedDictionary.FILE, "mine.txt"), dictionaries.list()!!.sorted())
    }

    // a compile tells how far it is, stage by stage, to its end; cancelled, the file there is
    // what it was, nothing of it left about; a word the base has is kept out of the trie
    @Test
    fun aCompileTellsHowFarAndACancelLeavesTheFileAsItWas() {
        MergedDictionary.base = { MergedDictionary.Base("test base") { { text, _ -> text == "你好" } } }
        val told = ArrayList<MergedDictionary.Work>()
        MergedDictionary.told = { told += it }
        File(dictionaries, "mine.txt").writeText("你好 ni'hao 0.0\n搭子 da'zi 0.0\n")
        MergedDictionary.refreshNow(dictionaries)
        val merged = MergedDictionary.current(dictionaries).file!!
        assertEquals(1, merged.size - merged.trieWords)
        assertEquals(DictionaryCompiler.Stage.entries.toList(), told.map { it.stage }.distinct())
        assertTrue(told.zipWithNext().all { (a, b) -> a.percent <= b.percent })
        assertNull(MergedDictionary.work.value)
        val before = File(dictionaries, MergedDictionary.FILE).readBytes()
        File(dictionaries, "theirs.txt").writeText((1..50_000).joinToString("\n") { "词$it ci 0.0" })
        val cancel = AtomicBoolean()
        MergedDictionary.told = { if (it.percent > 0) cancel.set(true) }
        val cancelled = runCatching { MergedDictionary.refreshNow(dictionaries, cancel) }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        assertTrue(before.contentEquals(File(dictionaries, MergedDictionary.FILE).readBytes()))
        assertEquals(listOf(MergedDictionary.FILE, "mine.txt", "theirs.txt"), dictionaries.list()!!.sorted())
        assertNull(MergedDictionary.work.value)
    }

    // a cancel pressed before the import's compile started (its file still being written, or
    // another compile running ahead of it) stops that one, and no other (review M1)
    @Test
    fun aCancelStopsItsOwnCompileEvenBeforeItStartsAndNoOther() {
        File(dictionaries, "mine.txt").writeText("你好 ni'hao 0.0\n")
        MergedDictionary.refreshNow(dictionaries)
        val before = File(dictionaries, MergedDictionary.FILE).readBytes()
        File(dictionaries, "theirs.txt").writeText("搭子 da'zi 0.0\n")
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
            File(dictionaries, "mine.txt").writeText("你好 ni'hao 0.0\n")
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
        File(dictionaries, "mine.txt").writeText("你好 ni'hao 0.0\n")
        MergedDictionary.refreshNow(dictionaries)
        MergedDictionary.reset()
        made = 0
        val seen = MergedDictionary.current(dictionaries)
        assertEquals(1, seen.file!!.size)
        assertTrue(seen.token.startsWith(MergedDictionary.FILE))
        MergedDictionary.refreshNow(dictionaries)
        assertEquals(0, made)
    }

    // the first compile of what an older build left is an upgrade, told so; one imported or
    // restored since this build was put on, none known, a file there already, or an import's
    // compile is an update (review, F3409). Each row: why, the files' time, and how it is compiled
    @Test
    fun anUpgradeIsToldFromAnUpdate() {
        class Row(val why: String, val kind: MergedDictionary.Kind, val modified: Long, val installed: Long?, val fileThere: Boolean = false, val forImport: Boolean = false)
        val rows = listOf(
            Row("an older build's dictionaries", MergedDictionary.Kind.UPGRADE, modified = 1_000_000, installed = 2_000_000),
            Row("imported since", MergedDictionary.Kind.UPDATE, modified = 3_000_000, installed = 2_000_000),
            Row("the install time not known", MergedDictionary.Kind.UPDATE, modified = 1_000_000, installed = null),
            Row("a file there already", MergedDictionary.Kind.UPDATE, modified = 1_000_000, installed = 2_000_000, fileThere = true),
            Row("an import's compile", MergedDictionary.Kind.UPDATE, modified = 1_000_000, installed = 2_000_000, forImport = true),
        )
        assertRows(rows, { it.why }) { row ->
            done()
            fresh()
            if (row.fileThere) {
                File(dictionaries, "kept.txt").writeText("妮 ni 0.0\n")
                MergedDictionary.refreshNow(dictionaries)
                MergedDictionary.reset()
            }
            File(dictionaries, "old.txt").apply { writeText("你好 ni'hao 0.0\n"); setLastModified(row.modified) }
            MergedDictionary.installedAt = { row.installed }
            val kinds = Collections.synchronizedSet(HashSet<MergedDictionary.Kind>())
            MergedDictionary.told = { kinds += it.kind }
            if (row.forImport) MergedDictionary.refreshNow(dictionaries) else MergedDictionary.refresh(dictionaries)!!.get()
            assertEquals(row.why, setOf(row.kind), kinds.toSet())
        }
    }

    // a file mapped unchecked at a fresh start that fails its check on the compile thread is
    // dropped, deleted and made again, the engine told (review)
    @Test
    fun aDamagedFileIsMadeAgain() {
        File(dictionaries, "mine.txt").writeText("你好 ni'hao 0.0\n搭子 da'zi 0.0\n")
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
        File(dictionaries, "mine.txt").writeText("你好 ni'hao 0.0\n")
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
        File(dictionaries, "a.txt").writeText("你好 ni'hao 0.0\n")
        val b = File(dictionaries, "b.txt").apply { writeText("搭子 da'zi 0.0\n") }
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
}
