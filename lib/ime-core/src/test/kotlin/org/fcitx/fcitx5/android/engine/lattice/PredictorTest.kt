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

class PredictorTest {

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("好", -2.5f, 0f)
                .unigram("，", -1.5f, 0f)
                .unigram("吗", -3.5f, 0f)
                .unigram("我", -2f, 0f)
                .unigram("再", -2.5f, 0f)
                .unigram("见", -3f, 0f)
                .unigram("A股", -4f, 0f)
                .unigram("马克·吐温", -5f, 0f)
                .unigram("·", -3f, 0f)
                .unigram("二〇二六", -5f, 0f)
                .bigram("你", "好", -0.75f, 0f)
                .bigram("你", "，", -0.25f, 0f)
                .bigram("你", "吗", -1.5f, 0f)
                .bigram("你", "<unk>", -1f, 0f)
                .bigram("你", "A股", -2f, 0f)
                .bigram("你", "马克·吐温", -3f, 0f)
                .bigram("你", "·", -0.5f, 0f)
                .bigram("好", "二〇二六", -1f, 0f)
                .bigram("吗", "好", -1f, 0f)
                .bigram("吗", "你", -1f, 0f)
                .bigram("我", "再", -0.5f, -0.5f)
                .bigram("再", "见", -0.5f, 0f)
                .bigram("再", "吗", -1f, 0f)
                .trigram("我", "再", "见", -0.25f)
                .entry("你", intArrayOf(Syllables.id("ni")))
                .build().toByteArray(),
        ),
    )

    private val predictor = Predictor(data.model, data.vocabulary)

    private fun id(word: String) = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == word }

    private fun texts(c: List<Candidate>) = c.map { it.text }

    @Test
    fun theWordsSeenAfterTheLastOneComeMostProbableFirst() {
        val after = predictor.predict(NO_WORD, id("你"))
        // ， · and <unk> are more probable, and no words
        assertEquals(listOf("好", "吗", "A股", "马克·吐温"), texts(after))
        assertEquals(listOf("二〇二六"), texts(predictor.predict(NO_WORD, id("好"))))
        val context = data.model.context(NO_WORD, id("你"))
        for (c in after) {
            assertEquals(data.model.scoreAfter(context, c.words.single()), c.score, 0f)
            assertEquals(0, c.end)
        }
    }

    @Test
    fun theWordBeforeThatCounts() {
        val after = predictor.predict(id("我"), id("再"))
        assertEquals(listOf("见", "吗"), texts(after))
        assertEquals(-0.25f, after[0].score, 0f)
        // 吗 was never seen after 我 再: the backoff of 我 再 on top
        assertEquals(-0.5f - 1f, after[1].score, 1e-6f)
    }

    @Test
    fun theLimitIsKept() {
        assertEquals(listOf("好"), texts(predictor.predict(NO_WORD, id("你"), limit = 1)))
        assertEquals(listOf("好", "吗"), texts(predictor.predict(NO_WORD, id("你"), limit = 2)))
        assertTrue(predictor.predict(NO_WORD, id("你"), limit = 0).isEmpty())
    }

    @Test
    fun aTieGoesToTheWordSeenFirst() {
        // 你 comes before 好 in the vocabulary, and both follow 吗 at -1
        assertEquals(listOf("你"), texts(predictor.predict(NO_WORD, id("吗"), limit = 1)))
        assertEquals(listOf("你", "好"), texts(predictor.predict(NO_WORD, id("吗"))))
    }

    @Test
    fun nothingIsPredictedWithoutAWordOrAfterOneNeverFollowed() {
        assertTrue(predictor.predict(NO_WORD, NO_WORD).isEmpty())
        assertTrue(predictor.predict(id("你"), NO_WORD).isEmpty())
        assertTrue(predictor.predict(NO_WORD, id("见")).isEmpty())
    }
}
