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
import org.junit.Assert.assertTrue
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
                // a phrase the model has as a word, and its words
                .unigram("今天天气", -4f, 0f)
                .unigram("今天", -3f, 0f)
                .unigram("天气", -3f, 0f)
                .unigram("今", -3f, 0f)
                .unigram("天", -2.5f, 0f)
                .unigram("气", -2.5f, 0f)
                // a word of three, and a phrase with it in it
                .unigram("去开饭店", -4f, 0f)
                .unigram("开饭店", -4f, 0f)
                .unigram("开饭", -3.5f, 0f)
                .unigram("饭店", -3f, 0f)
                .unigram("开", -3f, 0f)
                .unigram("店", -3f, 0f)
                .unigram("去", -2f, 0f)
                // a pair, for a text scored after the words before it
                .bigram("，", "你", -0.5f, 0f)
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
    fun theLastTwoWordsAreTheDecodersContextTheOneBeforeFirst() {
        val (ni, hao, ma) = listOf("你", "好", "吗").map(::id)
        assertEquals(hao to ma, TextWords.previous(intArrayOf(ni, hao, ma)))
        assertEquals(NO_WORD to ma, TextWords.previous(intArrayOf(ma)))
        assertEquals(NO_WORD to NO_WORD, TextWords.previous(IntArray(0)))
        // after a last that is no word, the one before it is no context either
        assertEquals(NO_WORD to NO_WORD, TextWords.previous(intArrayOf(ni, NO_WORD)))
        assertEquals(NO_WORD to ma, TextWords.previous(intArrayOf(NO_WORD, ma)))
        assertEquals(id("你好") to id("吗"), TextWords.previous(words.lastTwo("你好吗")))
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

    @Test
    fun aWordOfThreeOrMoreIsSplitAgainIntoItsWords() {
        // 今天天气 whole (-4) is likeliest, and within it 今天 天气 (-6) over 今天 天 气 (-8):
        // a blocked 天气 stands as a word in it (VoiceBlocking, VO-10)
        assertEquals(listOf(0, 2, 4, 5), words.boundaries("今天天气吗", splitLong = true).toList())
        // no word of three: as before, 今天 气 (-5.5) over 今 天气 (-6)
        assertEquals(listOf(0, 2, 3), words.boundaries("今天气", splitLong = true).toList())
        // a word of three: 开 饭店 (-6) over 开饭 店 (-6.5)
        assertEquals(listOf(0, 1, 3), words.boundaries("开饭店", splitLong = true).toList())
        // nested: 去开饭店 is 去 开饭店 (-6) without itself, and 开饭店 split again
        assertEquals(listOf(0, 1, 2, 4), words.boundaries("去开饭店", splitLong = true).toList())
    }

    @Test
    fun withoutSplitLongTheModelsWordsStandWhole() {
        // as ime-eval measures 联想 after each word
        assertEquals(listOf(0, 4, 5), words.boundaries("今天天气吗").toList())
        assertEquals(listOf(0, 3), words.boundaries("开饭店").toList())
        assertEquals(listOf(0, 4), words.boundaries("去开饭店").toList())
    }

    @Test
    fun aTextScoredAsItsLikeliestSplit() {
        // unigrams only here: 你好 吗 (-6.5) over 你 好 吗 (-8)
        assertEquals(-6.5f, words.logProb("你好吗"), 1e-4f)
        // 中国 人 and 中 国人 alike, the better
        assertEquals(-5f, words.logProb("中国人"), 1e-4f)
        // x no word: <unk>
        assertEquals(-7f - 3f, words.logProb("x你好"), 1e-4f)
        assertEquals(0f, words.logProb(""), 0f)
        assertTrue(words.logProb("你好吗") > words.logProb("吗你好吗"))
    }

    @Test
    fun aTextIsScoredAfterTheWordsBeforeItEachWordAfterTheTwoBeforeIt() {
        val (comma, ni, hao) = listOf("，", "你", "好").map(::id)
        val ma = id("吗")
        val model = data.model
        assertEquals(model.score(NO_WORD, comma, ni), words.logProb("你", "，"), 1e-6f)
        assertTrue(words.logProb("你", "，") > words.logProb("你"))
        // 吗 after ， 你, a pair the model has
        assertEquals(model.score(NO_WORD, comma, ni) + model.score(comma, ni, ma), words.logProb("你吗", "，"), 1e-6f)
        // the likelier of 你好 and 你 好, each after what came before it
        val whole = model.score(NO_WORD, comma, id("你好"))
        val split = model.score(NO_WORD, comma, ni) + model.score(comma, ni, hao)
        assertEquals(maxOf(whole, split), words.logProb("你好", "，"), 1e-6f)
    }
}
