/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.english.EnglishDataBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The checks of a model's words, one copy for the pinyin and the English builders: they refuse alike. */
class NgramWordsTest {

    @Test
    fun aWordIsAUnigramOnceItsNgramsOfUnigramsTheirProbabilitiesFinite() {
        val model = NgramWords().apply {
            unigram("a", -1f, 0f)
            unigram("b", -2f, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) { model.bigram("a", "c", -1f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { model.trigram("a", "b", "b", Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { model.unigram("c", -1f, Float.POSITIVE_INFINITY) }
        // a dictionary's word the model lacks, numbered after the model's
        assertEquals(2, model.add("c"))
        assertEquals(2, model.add("c"))
        assertEquals(listOf("a", "b", "c"), model.words)
        // both builders through it
        assertThrows(IllegalArgumentException::class.java) { PinyinDataBuilder().unigram("a", -1f, 0f).unigram("a", -2f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { EnglishDataBuilder().unigram("a", -1f, 0f).bigram("a", "b", -1f, 0f) }
    }
}
