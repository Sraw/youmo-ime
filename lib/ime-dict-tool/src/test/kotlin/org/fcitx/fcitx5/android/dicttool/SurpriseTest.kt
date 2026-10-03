/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class SurpriseTest {

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -5f, 0f)
                .unigram("你", -1f, -0.5f)
                .unigram("好", -1.5f, 0f)
                .unigram("你好", -2f, 0f)
                .bigram("你", "好", -0.3f, 0f)
                .entry("你", intArrayOf(Syllables.id("ni")))
                .entry("好", intArrayOf(Syllables.id("hao")))
                .entry("你好", intArrayOf(Syllables.id("ni"), Syllables.id("hao")))
                .build().toByteArray(),
        ),
    )

    @Test
    fun theLikeliestReadingAsTheModelsWordsIsWhatARunIsMeasuredAgainst() {
        val s = Surprise(data)
        // 你 好 at -1.3 beats the word 你好 at -2
        assertEquals(-1.3, s.predicted("你好"), 1e-6)
        // 好 你: no bigram, so 好 then 你 after it by the backoff (-1.5 + 0 - 1)
        assertEquals(-2.5, s.predicted("好你"), 1e-6)
        // a character of no word's is the unknown word; a longer run of none is no split
        assertEquals(-1.0 - 5.0 + -0.5, s.predicted("你嗯"), 1e-6)
        // 100 in 10000 characters: 100 times what the model says of 好你
        assertEquals(0.5, s.of("好你", 100, 10_000), 1e-6)
    }
}
