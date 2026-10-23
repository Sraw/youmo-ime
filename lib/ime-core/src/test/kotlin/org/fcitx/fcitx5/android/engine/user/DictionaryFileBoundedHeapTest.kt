/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Run alone by `boundedHeapTest`, in a JVM of 48 MB (build.gradle.kts): 0.3.1 listed a merged
 * dictionary's words as objects, about 450 bytes each, and a 1.9-million-word one ran a phone's
 * 256 MB heap out on every focus. 2 million words (rime-frost has 1.9) must compile in 48 MB,
 * open (mapped, checked) and be found there.
 */
class DictionaryFileBoundedHeapTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val syllables = (0 until Syllables.count).filter { Syllables.spelling(it)[0].isLowerCase() }

    private fun word(r: Random): String {
        val n = 2 + r.nextInt(3)
        val text = String(CharArray(n) { (0x4E00 + r.nextInt(20000)).toChar() })
        return text + " " + (0 until n).joinToString("'") { Syllables.spelling(syllables[r.nextInt(syllables.size)]) } + " " + r.nextFloat() / 1000
    }

    @Test
    fun manyWordsCompileAndOpenInASmallHeap() {
        assertTrue("run in a small heap", Runtime.getRuntime().maxMemory() <= 48L shl 20)
        val r = Random(7)
        val file = File(folder.root, "big.ymd")
        val result = DictionaryCompiler.compile(listOf(generateSequence { word(r) }.take(WORDS)), file)
        assertTrue(result.words > WORDS * 0.99)
        val d = DictionaryFile.open(file)
        var found = 0
        val again = Random(7)
        repeat(1000) {
            val (text, code) = word(again).split(' ')
            val entry = LibimeImport.entry(text, code)!!
            if (d.find(entry) >= 0) found++
        }
        assertEquals(1000, found)
        assertEquals(result.words, d.size)
    }

    // 2 million words of one reading: held to be ranked, all of them were too many (part B's find);
    // those kept the best ranked, not the first to come (review NIT 1)
    @Test
    fun manyWordsOfOneReadingCompileInASmallHeapTheRestPastTheCapUnread() {
        val file = File(folder.root, "one.ymd")
        fun text(i: Int) = String(charArrayOf((0x4E00 + i % 20000).toChar(), (0x4E00 + i / 20000).toChar()))
        // weighted by i: the last to come rank best
        val words = (0 until WORDS).asSequence().map { i -> text(i) + " shi'shi " + (i + 1) }
        val result = DictionaryCompiler.compile(listOf(words), file)
        assertEquals(DictionaryCompiler.MAX_PER_READING, result.words)
        assertEquals(WORDS - DictionaryCompiler.MAX_PER_READING, result.unread)
        val d = DictionaryFile.open(file)
        var node = DictionaryFile.ROOT
        repeat(2) { node = d.child(node, Syllables.id("shi")) }
        assertEquals(DictionaryCompiler.MAX_PER_READING, d.wordCount(node))
        assertEquals(text(WORDS - 1), d.text(d.word(node, 0)))
        assertEquals(text(WORDS - DictionaryCompiler.MAX_PER_READING), d.text(d.word(node, DictionaryCompiler.MAX_PER_READING - 1)))
    }

    // MUST-1: each time a word came was held till the next; 4 million copies of one line ran out of heap
    @Test
    fun aLineRepeatedMillionsOfTimesCompilesInASmallHeap() {
        val file = File(folder.root, "same.ymd")
        val copies = DictionaryCompiler.MAX_WORDS
        val result = DictionaryCompiler.compile(listOf(generateSequence { "啊 a 0.5" }.take(copies)), file)
        assertEquals(1, result.words)
        assertEquals(0, result.unread)
        assertEquals(1, DictionaryFile.open(file).size)
    }

    private companion object {
        const val WORDS = 2_000_000
    }
}
