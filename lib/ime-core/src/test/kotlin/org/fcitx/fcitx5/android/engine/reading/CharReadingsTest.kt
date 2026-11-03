/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.ReadingSource
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class CharReadingsTest {

    private val readings = ReadingData.data.charReadings

    private fun of(c: Char) = readings.of(c.code).map { it.pinyin }

    @Test
    fun theFrequencyDictionarysOrderFirstThenTheWordsThatReadEachWay() {
        // xíng háng as 《现代汉语频率词典》 heard them; héng (道行) commoner than hàng (树行子) in the model; not 大字典's xìng
        assertEquals(listOf("xíng", "háng", "héng", "hàng"), of('行'))
        // 频率词典 heard hāo, which no dictionary has
        assertEquals(listOf("hǎo", "hào"), of('好'))
        // a character only 频率词典 has: its readings
        assertEquals(listOf("gōng"), of('工'))
        assertEquals(listOf("hái", "huán"), of('还'))
    }

    @Test
    fun aNeutralToneGoesWhereTheSyllableHasAFullOneButNotACharactersOwn() {
        assertEquals(listOf("zǐ"), of('子'))
        assertEquals(listOf("tóu"), of('头'))
        // huo and he go (huó, hé); the rest after 频率词典's two in 通用规范's order
        assertEquals(listOf("hé", "hè", "hú", "huó", "huò"), of('和'))
        // kMandarin's neutral reading stays, first as heard; dī after as 通用规范 lists it
        assertEquals(listOf("de", "dì", "dí", "dī"), of('的'))
        assertEquals(listOf("men", "mén"), of('们'))
    }

    @Test
    fun theCardListsTheDictionariesNeutralTonesTooAfterTheRest() {
        assertEquals(listOf("tóu", "tou"), readings.of('头'.code, card = true).map { it.pinyin })
        assertEquals(listOf("hé", "hè", "hú", "huó", "huò", "huo"), readings.of('和'.code, card = true).map { it.pinyin })
        // 子's zi is 频率词典's alone, no dictionary's: not even the card's
        assertEquals(listOf("zǐ"), readings.of('子'.code, card = true).map { it.pinyin })
        assertEquals(of('的'), readings.of('的'.code, card = true).map { it.pinyin })
        // its words by the card's place
        assertEquals(emptyList<String>(), readings.examples('和'.code, 5))
        assertEquals(listOf("和平"), readings.examples('和'.code, 0))
    }

    @Test
    fun yiInItsOwnToneAlone() {
        assertEquals(listOf("yī"), of('一'))
    }

    @Test
    fun aCharacterNoModernDictionaryHasGoesByKMandarinAndTheGreatDictionary() {
        assertEquals(listOf("rén"), of('亻'))
        assertEquals(listOf("jiāng"), of('江'))
        assertEquals(emptyList<String>(), of('a'))
        assertEquals(emptyList<String>(), of('㐀'))
    }

    @Test
    fun eachReadingsCommonestWordsOfTwoToFourCharacters() {
        val xing = '行'.code
        assertEquals(listOf("进行", "行走"), readings.examples(xing, 0))
        // 银行's likelier reading only (yin xing is the dictionary's rarer one)
        assertEquals(listOf("银行"), readings.examples(xing, 1))
        assertEquals(listOf("道行"), readings.examples(xing, 2))
        assertEquals(listOf("树行子"), readings.examples(xing, 3))
        assertEquals(emptyList<String>(), readings.examples(xing, 4))
        assertEquals(listOf("爱好"), readings.examples('好'.code, 1))
        // one reading: none kept
        assertEquals(emptyList<String>(), readings.examples('银'.code, 0))
        assertEquals(emptyList<String>(), readings.examples('a'.code, 0))
    }

    @Test
    fun aReadingWrittenEachWay() {
        val r = Reading(Syllables.id("lv"), 3)
        assertEquals("lǚ", r.pinyin)
        assertEquals("ㄌㄩˇ", r.zhuyin)
        assertEquals("", Reading(Syllables.id("A"), 1).zhuyin)
    }

    @Test
    fun aSourceIsKnownByItsFilesName() {
        assertEquals(ReadingSource.XHC, ReadingSource.of("pinyin-data-fe29ad32-kXHC1983.txt"))
        assertEquals(ReadingSource.PINLU, ReadingSource.of("kHanyuPinlu.txt"))
        assertEquals(ReadingSource.HANYU_PINYIN, ReadingSource.of("kHanyuPinyin"))
        assertNull(ReadingSource.of("unihan.txt"))
    }

    @Test
    fun dataWithNoReadingsListedHasNone() {
        val data = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder().unigram(PinyinDataBuilder.UNKNOWN, -7f, 0f).entry("好", intArrayOf(Syllables.id("hao")))
                    .charTone('好'.code, Syllables.id("hao"), 3).build().toByteArray()
            )
        )
        assertEquals(0, data.charReadings.characters)
        assertEquals(emptyList<Reading>(), data.charReadings.of('好'.code))
    }
}
