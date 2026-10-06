/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class TextWordsTest {

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("好", -2.5f, 0f)
                .unigram("你好", -3f, 0f)
                .unigram("吗", -3.5f, 0f)
                .unigram("，", -1.5f, 0f)
                .unigram("中国", -3f, 0f)
                .unigram("中", -2.5f, 0f)
                .unigram("国人", -3f, 0f)
                .unigram("人", -2f, 0f)
                .entry("你", intArrayOf(Syllables.id("ni")))
                .build().toByteArray(),
        ),
    )

    private val words = TextWords(data.model, data.wordIndex)

    private fun id(word: String) = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == word }

    private fun lastTwo(text: String) = words.lastTwo(text).map { if (it == NO_WORD) "-" else data.vocabulary.word(it) }

    @Test
    fun theTextIsSplitAsTheModelFindsItLikeliest() {
        assertEquals(listOf("你好", "吗"), lastTwo("你好吗"))
        // 中国 人 at -5 over 中 国人 at -5.5
        assertEquals(listOf("中国", "人"), lastTwo("中国人"))
        assertEquals(listOf("吗", "，"), lastTwo("你好吗，"))
        assertEquals(id("你好"), words.lastTwo("你好").single())
    }

    @Test
    fun aCharNoWordCoversIsNoWordBeforeTheLast() {
        assertEquals(listOf("-", "你好"), lastTwo("x你好"))
        assertEquals(listOf("-", "人"), lastTwo("你好吗x人"))
    }

    @Test
    fun textEndingWithNoWordIsNoContext() {
        assertEquals(emptyList<String>(), lastTwo("你好 "))
        assertEquals(emptyList<String>(), lastTwo("你好x"))
        assertEquals(emptyList<String>(), lastTwo(""))
    }

    @Test
    fun aCharPastTheBmpIsNoWord() {
        assertEquals(emptyList<String>(), lastTwo("你好\uD83D\uDE00"))
        assertEquals(listOf("-", "你好"), lastTwo("\uD83D\uDE00你好"))
        // the window starting on the second half of one
        assertEquals(listOf("你好", "吗"), lastTwo("\uD83D\uDE00" + "你好".repeat(11) + "吗"))
    }

    @Test
    fun onlyTheEndOfLongTextIsRead() {
        assertEquals(listOf("你好", "吗"), lastTwo("x".repeat(10_000) + "你好".repeat(20) + "吗"))
    }

    @Test
    fun theWholeTextSplitIntoWords() {
        assertEquals(listOf(0, 2, 3), words.boundaries("你好吗").toList())
        assertEquals(listOf(0, 2, 3, 4, 6), words.boundaries("中国人x你好").toList())
        assertEquals(listOf(0), words.boundaries("").toList())
        // longer than the window lastTwo looks at
        val long = "你好吗".repeat(10)
        assertEquals((0..30).filter { it % 3 != 1 }, words.boundaries(long).toList())
    }
}
