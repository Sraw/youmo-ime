/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class CharRanksTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    // more characters of ji than the words were cut at, all commoner than 觉 of jiao, which
    // ji'ao (几奥) reads better as a whole too
    private val ji = (0 until PinyinDecoder.DEFAULT_WORDS + 6).map { (0x4E00 + it).toChar().toString() }
    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder().unigram("<unk>", -7f, 0f)
                .apply { ji.forEachIndexed { i, c -> unigram(c, -1f - i / 64f, 0f).entry(c, syl("ji")) } }
                .unigram("奥", -1f, 0f).entry("奥", syl("ao"))
                .unigram("觉", -5f, 0f).entry("觉", syl("jiao"))
                .build().toByteArray(),
        ),
    )

    @Test
    fun everyCharacterIsListedThoughMoreOfARarerReadingStartThanTheWordsWereCutAt() {
        val ranks = CharRanks(data).measure()
        assertEquals(ji.size + 2, ranks.total)
        assertEquals(0, ranks.missing)
        // the rarest of ji past the first words, and none past the list
        assertEquals(6, ranks.past)
        assertEquals(ji.size + 2, ranks.within.last() + ranks.past)
        val report = StringBuilder().also { CharRanks.report(ranks, it) }.toString()
        assertTrue(report, report.lines()[0].endsWith(": ${ji.size + 2}"))
    }

    // a character not listed has no score of the decoder's: they were shown in the order of a hash map
    @Test
    fun theMissingShownAreTheCommonestByTheModel() {
        // commonest itself, not through measure: decoding every word a reading starts with
        // (words = Int.MAX_VALUE), the decoder leaves no character of data like this out
        val charRanks = CharRanks(data)
        // 鑫 the model lacks: its unknown word's score, the last
        assertEquals(listOf("奥", ji[5], "觉", "鑫"), charRanks.commonest(listOf("觉", "鑫", ji[5], "奥")))
        assertEquals(ji.take(CharRanks.MISSED_SHOWN), charRanks.commonest(ji.reversed()))
    }
}
