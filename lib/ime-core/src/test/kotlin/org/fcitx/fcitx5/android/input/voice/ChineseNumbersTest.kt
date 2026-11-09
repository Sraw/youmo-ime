/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.ChineseNumbers.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

// the runs the tagger marks in voice-eval's num-data/cases-N.tsv, as each class writes them
class ChineseNumbersTest {

    private fun check(kind: Kind, vararg pairs: Pair<String, String>) {
        for ((said, written) in pairs) assertEquals("$said as $kind", written, ChineseNumbers.written(said, kind))
    }

    @Test
    fun valuesAreIntegersAndDecimalsWithTheirBigPlaces() {
        check(
            Kind.VALUE,
            "四十五" to "45", "一百二十三" to "123", "二十八" to "28", "十" to "10", "十二" to "12",
            "四千五" to "4500", "一千二百" to "1200", "三十三万六千零五" to "336005", "十二万三千" to "123000",
            // 万 and 亿 at the end stay characters
            "十四亿" to "14亿", "五万" to "5万", "三十三万" to "33万", "三万亿" to "3万亿",
            "十二点五" to "12.5", "零点五" to "0.5", "三点五亿" to "3.5亿",
        )
    }

    @Test
    fun digitsAreDigitByDigit() {
        check(Kind.DIGITS, "幺三八幺二三四五六七八" to "13812345678", "四七二九幺五" to "472915", "三零二" to "302")
        // a place among them: read as a value
        check(Kind.DIGITS, "一百二十三" to "123", "三万" to "3万", "十二" to "12")
    }

    @Test
    fun datesWithoutAPlaceAreDigitByDigit() {
        check(Kind.DATE, "二零二六" to "2026", "九八" to "98", "十一" to "11", "二十四" to "24", "八" to "8")
    }

    @Test
    fun timesAreHourAndMinutesOrKeepTheirCharacters() {
        check(
            Kind.TIME,
            "十点十五" to "10:15", "三点四十" to "3:40", "八点五分" to "8:05", "十点零五分" to "10:05",
            "八点半" to "8点半", "八点一刻" to "8点一刻", "八点" to "8点", "七点整" to "7点整", "十一" to "11",
            "二十四点" to "24点",
        )
        // past a day's hours or an hour's minutes, or 分 with no minutes: as said
        check(Kind.TIME, "二十五点十分" to "二十五点十分", "八点六十" to "八点六十", "八点分" to "八点分", "二零二六点" to "二零二六点")
    }

    @Test
    fun moneyPercentModelAndRatio() {
        check(Kind.MONEY, "三百五十" to "350", "五千九百九十九" to "5999")
        check(Kind.PERCENT, "百分之三点五" to "3.5%", "百分之二十" to "20%", "百分之九十九" to "99%")
        check(Kind.MODEL, "一点二三" to "1.23", "二点一点三" to "2.1.3", "四" to "4", "十五" to "15", "三点十二" to "3.12")
        check(Kind.RATIO, "四分之三" to "3/4", "三比二" to "3:2")
    }

    @Test
    fun whatDoesNotReadAsItsKindStaysAsSaid() {
        check(Kind.VALUE, "一点二点三" to "一点二点三", "万一" to "万一", "二十三四" to "二十三四", "一千零" to "一千零", "三点十" to "三点十")
        check(Kind.TIME, "八点七十" to "八点七十", "万点" to "万点")
        check(Kind.PERCENT, "百分之几" to "百分之几", "百分之百" to "百分之百")
        check(Kind.RATIO, "三四" to "三四", "几比二" to "几比二")
        check(Kind.MODEL, "V点" to "V点")
        check(Kind.NONE, "二十" to "二十")
        assertEquals(Kind.PERCENT, Kind.of("percent"))
    }
}
