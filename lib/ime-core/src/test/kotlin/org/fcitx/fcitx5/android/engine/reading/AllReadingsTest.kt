/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.junit.Assert.assertEquals
import org.junit.Test

/** A handwritten character's readings in its candidate: [CandidateReadings.allOf] and the order [CandidateReadings.ordered] gives them. */
class AllReadingsTest {

    private val data = ReadingData.data

    // 還, 行 traditional as 还, 行: a character a time, as the app's simplifier
    private val readings = CandidateReadings(data, data.tones, { it.replace('還', '还') }) { Predictor.charReadingsOf(data.dictionary, data.vocabulary) }

    private fun ordered(c: Char, before: String = "") = readings.ordered(c.code, before).map { it.pinyin }

    @Test
    fun theFirstTwoAndMoreAfterThem() {
        assertEquals(listOf("xíng/háng…"), readings.allOf("行", ReadingStyle.PINYIN))
        assertEquals(listOf("hǎo/hào"), readings.allOf("好", ReadingStyle.PINYIN))
        assertEquals(listOf("ㄏㄠˇ/ㄏㄠˋ"), readings.allOf("好", ReadingStyle.ZHUYIN))
        assertEquals(listOf("ㄒㄧㄥˊ/ㄏㄤˊ…"), readings.allOf("行", ReadingStyle.ZHUYIN))
    }

    @Test
    fun theReadingOfTheWordTheTextBeforeMakesFirst() {
        assertEquals(listOf("háng/xíng…"), readings.allOf("行", ReadingStyle.PINYIN, "银"))
        assertEquals(listOf("háng", "xíng", "héng", "hàng"), ordered('行', "银"))
        assertEquals(listOf("héng", "xíng", "háng", "hàng"), ordered('行', "道"))
        // the word at the end of what was written
        assertEquals("háng", ordered('行', "你银").first())
        // no word: as usual
        assertEquals(listOf("xíng", "háng", "héng", "hàng"), ordered('行', "你"))
        assertEquals(listOf("xíng", "háng", "héng", "hàng"), ordered('行', "道路"))
    }

    @Test
    fun oneReadingOrNoneIsAsOfHasIt() {
        assertEquals(readings.of("银", ReadingStyle.PINYIN), readings.allOf("银", ReadingStyle.PINYIN))
        assertEquals(listOf("yín"), readings.allOf("银", ReadingStyle.PINYIN, "行"))
        assertEquals(readings.of("银行", ReadingStyle.PINYIN), readings.allOf("银行", ReadingStyle.PINYIN))
        assertEquals(emptyList<String>(), readings.allOf("a", ReadingStyle.PINYIN))
        assertEquals(emptyList<String>(), readings.allOf("", ReadingStyle.PINYIN))
        assertEquals(emptyList<String>(), readings.allOf("行", ReadingStyle.OFF))
    }

    @Test
    fun aTraditionalCharacterByItsSimplifiedOnesReadings() {
        assertEquals(listOf("hái", "huán"), ordered('還'))
        assertEquals(listOf("hái/huán"), readings.allOf("還", ReadingStyle.PINYIN))
    }
}
