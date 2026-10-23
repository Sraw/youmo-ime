/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Run alone by `boundedHeapTest`, in a JVM of 48 MB (build.gradle.kts): 0.3.1 coded a merged
 * dictionary's words for a table into a TreeMap, about 250 MB for rime-frost's 1.9 million, and
 * the OutOfMemoryError killed the input method. 2 million words must be coded for a table in 48
 * MB, the index opened, and each word found under its code.
 */
class CodedIndexBoundedHeapTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun manyWordsAreCodedForATableInASmallHeap() {
        assertTrue("run in a small heap", Runtime.getRuntime().maxMemory() <= 48L shl 20)
        val (table, lines) = manyWords(chars = CHARS)
        val merged = File(folder.root, "merged.ymd")
        DictionaryCompiler.compile(listOf(lines), merged)
        val d = DictionaryFile.open(merged)
        assertTrue(d.size >= 2_000_000)
        val dest = File(folder.root, "wubi.ymc")
        CodedIndex.build(table, d, dest, "k")
        val index = CodedIndex.open(dest, "k")!!
        assertEquals(d.size, index.size)
        val r = Random(7)
        repeat(1000) {
            val text = d.text(r.nextInt(d.size))
            val code = table.encode(text)!!
            assertTrue(text, index.has(code, text, d))
            assertTrue(index.words(code, d).contains(code to text))
        }
        typedOverTheIndex(table, index, d)
    }

    // one key of a table over the index: its first page of candidates, timed and its allocation counted
    private fun typedOverTheIndex(table: TableDictionary, index: CodedIndex, d: DictionaryFile) {
        val shared = object : SharedWords by SharedWords.NONE {
            override fun words(prefix: String) = index.exact(prefix, d)
            override fun longer(prefix: String, byLength: Boolean) = index.longer(prefix, byLength, d)
            override fun leadsAnywhere(prefix: String) = index.leadsAnywhere(prefix)
        }
        val session = TableSession(table, TableOptions.WUBI.copy(autoSelect = false), shared = shared)
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        fun key(c: Char): Long {
            session.apply(Action.Reset)
            val before = threads.getThreadAllocatedBytes(Thread.currentThread().id)
            val shown = session.apply(Action.Key(c)).candidates
            assertTrue(shown.isNotEmpty())
            return threads.getThreadAllocatedBytes(Thread.currentThread().id) - before
        }
        repeat(WARM) { key('a' + it % 25) }
        val start = System.nanoTime()
        val bytes = (0 until KEYS).map { key('a' + it % 25) }
        val ms = (System.nanoTime() - start) / 1e6 / KEYS
        println("one key over ${index.size} words: %.2f ms, %d KB allocated at most".format(ms, bytes.max() / 1024))
        // one letter leads to ~80,000 of the index's words: only those a page shows are read
        assertTrue("${bytes.max()} bytes for a key", bytes.max() < KEY_BYTES)
    }

    private companion object {
        // 1415² words: just over 2 million
        const val CHARS = 1415
        const val WARM = 25
        const val KEYS = 100
        const val KEY_BYTES = 256L shl 10
    }
}
