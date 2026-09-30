/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class WordIndexTest {

    // enough words that some hash to the same slot
    private val words = listOf("<unk>", "你", "你好", "好", "，") + (0 until 3000).map { "词$it" }

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder().apply {
                words.forEach { unigram(it, -3f, 0f) }
                entry("你", intArrayOf(Syllables.id("ni")))
                // a word of the dictionary only, not the model's
                entry("妳", intArrayOf(Syllables.id("ni")))
            }.build().toByteArray(),
        ),
    )

    private fun id(word: String) = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == word }

    @Test
    fun everyWordOfTheModelIsFound() {
        val index = data.wordIndex
        for (word in words) assertEquals(word, id(word), index.find(word))
    }

    @Test
    fun aWordIsFoundWhereItLiesInText() {
        assertEquals(id("你好"), data.wordIndex.find("他说你好吗", 2, 4))
        assertEquals(id("你"), data.wordIndex.find("他说你好吗", 2, 3))
        assertEquals(id("词42"), data.wordIndex.find("x词42y", 1, 4))
    }

    @Test
    fun textThatIsNoWordOfTheModelIsNotFound() {
        val index = data.wordIndex
        assertEquals(NO_WORD, index.find("妳"))
        assertEquals(NO_WORD, index.find("你好吗"))
        assertEquals(NO_WORD, index.find("词3000"))
        assertEquals(NO_WORD, index.find(""))
        assertEquals(NO_WORD, index.find("他说你好吗", 1, 3))
    }

    @Test
    fun anEmptyModelFindsNothing() {
        assertEquals(NO_WORD, WordIndex(data.vocabulary, 0).find("你"))
    }
}
