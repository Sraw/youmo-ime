/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The n-gram checks of [PinyinDataBuilder.build], which see a duplicate only beside its first in sorted order. */
class PinyinDataBuilderTest {

    private fun builder() = PinyinDataBuilder()
        .unigram(PinyinDataBuilder.UNKNOWN, -7f, 0f)
        .unigram("a", -2f, 0f)
        .unigram("b", -2.5f, 0f)
        .unigram("c", -3f, 0f)
        .entry("a", intArrayOf(Syllables.id("ni")))

    // sorted on the word alone, the duplicate would stay apart from its first
    private fun bigrams(twice: Boolean) = builder()
        .bigram("a", "b", -0.5f, 0f)
        .bigram("a", "c", -0.6f, 0f)
        .bigram("b", "a", -0.7f, 0f)
        .bigram("a", "a", -0.8f, 0f)
        .apply { if (twice) bigram("a", "b", -0.9f, 0f) }

    private fun trigrams(twice: Boolean) = builder()
        .bigram("a", "b", -0.5f, 0f)
        .bigram("b", "a", -0.7f, 0f)
        .trigram("a", "b", "c", -0.2f)
        .trigram("a", "b", "a", -0.3f)
        .trigram("b", "a", "c", -0.4f)
        .trigram("a", "b", "b", -0.5f)
        .apply { if (twice) trigram("a", "b", "c", -0.6f) }

    @Test
    fun aBigramAddedTwiceIsRefused() {
        bigrams(twice = false).build()
        val e = assertThrows(IllegalArgumentException::class.java) { bigrams(twice = true).build() }
        assertEquals("duplicate bigram at 4", e.message)
    }

    @Test
    fun aTrigramAddedTwiceIsRefused() {
        trigrams(twice = false).build()
        val e = assertThrows(IllegalArgumentException::class.java) { trigrams(twice = true).build() }
        assertEquals("duplicate trigram at 4", e.message)
    }
}
