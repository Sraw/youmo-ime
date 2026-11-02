/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer

class CandidateReadingsTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder().unigram("<unk>", -7f, 0f)
                .unigram("银行", -2f, 0f).entry("银行", syl("yin", "hang"))
                .unigram("行", -2f, 0f).entry("行", syl("xing")).entry("行", syl("hang"), -1f)
                .unigram("走", -2f, 0f).entry("走", syl("zou"))
                .unigram("银", -3f, 0f).entry("银", syl("yin"))
                .unigram("你", -2f, 0f).entry("你", syl("ni"))
                .entry("𠀀", syl("he"))
                .charTone('银'.code, Syllables.id("yin"), 2)
                .charTone('行'.code, Syllables.id("xing"), 2)
                .charTone('行'.code, Syllables.id("hang"), 2)
                .charTone('走'.code, Syllables.id("zou"), 3)
                .charTone('你'.code, Syllables.id("ni"), 3)
                .build().toByteArray(),
        ),
    )

    private val readings = CandidateReadings(data, data.tones) { Predictor.charReadingsOf(data.dictionary, data.vocabulary) }

    @Test
    fun offShowsNothingAndReadsNoTable() {
        var asked = 0
        val counting = object : ReadingTable by data.tones {
            override fun tones(text: String, syllables: IntArray, word: Int): ByteArray? = data.tones.tones(text, syllables, word).also { asked++ }
        }
        var charsRead = false
        val r = CandidateReadings(data, counting) { charsRead = true; Predictor.charReadingsOf(data.dictionary, data.vocabulary) }
        assertEquals(emptyList<String>(), r.of("银行", ReadingStyle.OFF))
        assertEquals(0, asked)
        assertFalse(charsRead)
        r.of("银行", ReadingStyle.PINYIN)
        assertEquals(1, asked)
    }

    @Test
    fun theSessionsReadingWins() {
        val typed = listOf(WordReading("行", -1, syl("hang")), WordReading("走", -1, syl("zou")))
        assertEquals(listOf("háng", "zǒu"), readings.of("行走", ReadingStyle.PINYIN, typed))
        // not of the text: read by it instead
        assertEquals(listOf("xíng", "zǒu"), readings.of("行走", ReadingStyle.PINYIN, typed.take(1)))
    }

    @Test
    fun byTextTheLongestWordFirstThenEachCharacterItsLikeliestWay() {
        assertEquals(listOf("yín", "háng", "xíng", "zǒu"), readings.of("银行行走", ReadingStyle.PINYIN))
    }

    @Test
    fun zhuyin() {
        assertEquals(listOf("ㄧㄣˊ", "ㄏㄤˊ"), readings.of("银行", ReadingStyle.ZHUYIN))
    }

    @Test
    fun whatIsNoHanCharacterOrHasNoKnownToneIsLeftEmpty() {
        assertEquals(listOf("nǐ", "", ""), readings.of("你A😀", ReadingStyle.PINYIN))
        assertEquals(emptyList<String>(), readings.of("hello", ReadingStyle.PINYIN))
        // a character of the dictionary only, with no tone known
        assertEquals(emptyList<String>(), readings.of("𠀀", ReadingStyle.PINYIN))
    }

    @Test
    fun stylesByKey() {
        assertEquals(ReadingStyle.ZHUYIN, ReadingStyle.of("Zhuyin"))
        assertEquals(ReadingStyle.OFF, ReadingStyle.of(null))
        assertEquals(ReadingStyle.OFF, ReadingStyle.of("x"))
    }
}
