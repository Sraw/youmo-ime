/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.english.EnglishData
import org.fcitx.fcitx5.android.engine.english.EnglishDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishDecoder
import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The engine reads the words before the cursor (EnglishDecoder.context) as the pages' were counted
 * (EnglishVocabulary.Words.id): a spelling the data lacks is the word it was counted as, so what
 * follows it is scored as the model learned it. Slips the pages had are not in the data, nor here.
 */
class EnglishContextTest {

    @Test
    fun theWordsBeforeTheCursorAreReadAsThePagesWereCounted() {
        val seen = listOf(
            "well" to 300, "we'll" to 20, "We'll" to 1, "we'd" to 300, "wed" to 15,
            "don't" to 300, "dont" to 15, "the" to 300, "The" to 2, "can't" to 300, "cant" to 40,
        )
        val words = EnglishVocabulary().apply { for ((word, times) in seen) add(List(times) { word }, sentence = false) }.words(100)
        // the words in the order the dict tool numbered them, the commonest first, as english-lm writes them
        val b = EnglishDataBuilder()
        words.list.forEachIndexed { i, word -> b.unigram(word, -1f - i * 0.1f, 0f) }
        val data = EnglishData.load(ByteBuffer.wrap(b.build().toByteArray()))
        val decoder = EnglishDecoder(data)
        fun read(vararg before: String) = data.model.prevOf(decoder.context(before.toList()))
        val spellings = listOf(
            "well", "WELL", "we'll", "WE'LL", "We'll", "wed", "WED", "we'd", "WE'D",
            "dont", "DONT", "don't", "THE", "The", "cant", "CANT",
        )
        for (spelling in spellings) assertEquals(spelling, words.id(spelling, first = false), read(spelling))
        // a sentence's first
        for (spelling in listOf("The", "Well", "We'll", "Dont", "Wed")) {
            assertEquals(spelling, words.id(spelling, first = true), read(EnglishText.START, spelling))
        }
    }
}
