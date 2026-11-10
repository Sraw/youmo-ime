/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.voice.ChineseNumbers.Kind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

// the runs the tagger marks in voice-eval's num-data/cases-N.tsv, as each class writes them
class ChineseNumbersTest {

    private fun rows(kind: Kind, vararg pairs: Pair<String, String>) = pairs.map { (said, written) -> Triple(kind, said, written) }

    // every row of the test checked, the failing ones listed together (review F5854, F6561); each row's
    // why is the comment above its group
    private fun check(vararg groups: List<Triple<Kind, String, String>>) =
        assertRows(groups.flatMap { it }, { (kind, said, _) -> "$said as $kind" }) { (kind, said, written) ->
            assertEquals(written, ChineseNumbers.written(said, kind))
        }

    @Test
    fun valuesAreIntegersAndDecimalsWithTheirBigPlaces() = check(
        rows(
            Kind.VALUE,
            "四十五" to "45", "一亿七万" to "100070000", "三十亿五万" to "3000050000", "一亿零七万" to "100070000", "一百二十三" to "123", "二十八" to "28", "十" to "10", "十二" to "12",
            "四千五" to "4500", "一千二百" to "1200", "三十三万六千零五" to "336005",
            // 万 and 亿 at the end stay characters
            "十四亿" to "14亿", "五万" to "5万", "三十三万" to "33万", "三万亿" to "3万亿",
            "十二点五" to "12.5", "零点五" to "0.5", "三点五亿" to "3.5亿",
        ),
    )

    @Test
    fun moreAfterABigPlaceIsItsDecimalOfAtMostTwoPlaces() = check(
        rows(
            Kind.VALUE,
            "十二万三千" to "12.3万", "三万五" to "3.5万", "一亿两千万" to "1.2亿", "一亿两千三百万" to "1.23亿",
            "一万零五百" to "1.05万",
            // three places or more to be exact: in full; 万亿 one place, not 30000.5亿
            "一百二十三万四千五百六十七" to "1234567", "一万零五" to "10005", "一亿零五万" to "100050000",
            "三万亿五千万" to "3000050000000", "九百万亿九千万" to "900000090000000",
        ),
        rows(Kind.MONEY, "十二万三千" to "12.3万"),
        rows(Kind.DIGITS, "十二万三千" to "12.3万"),
    )

    @Test
    @Uat("VO-06")
    fun digitsAreDigitByDigit() = check(
        // with 点: read as a model, an address's parts (review F6386)
        rows(Kind.DIGITS, "幺三八幺二三四五六七八" to "13812345678", "四七二九幺五" to "472915", "三零二" to "302", "一九二点一六八" to "192.168"),
        // a place among them: read as a value
        rows(Kind.DIGITS, "一百二十三" to "123", "三万" to "3万", "十二" to "12"),
    )

    @Test
    fun datesWithoutAPlaceAreDigitByDigit() = check(
        rows(Kind.DATE, "二零二六" to "2026", "九八" to "98", "十一" to "11", "二十四" to "24", "八" to "8"),
    )

    @Test
    fun timesAreHourAndMinutesOrKeepTheirCharacters() = check(
        rows(
            Kind.TIME,
            "十点十五" to "10:15", "八点十" to "8:10", "三点四十" to "3:40", "八点五分" to "8:05", "十点零五分" to "10:05",
            "八点半" to "8点半", "八点一刻" to "8点一刻", "八点" to "8点", "七点整" to "7点整", "十一" to "11",
            // an hour alone, 24 too (review F5962)
            "二十四点" to "24点",
        ),
        // past a day's hours or an hour's minutes, or 分 with no minutes: as said
        rows(Kind.TIME, "二十五点十分" to "二十五点十分", "八点六十" to "八点六十", "八点分" to "八点分", "二零二六点" to "二零二六点"),
    )

    @Test
    fun moneyPercentModelAndRatio() = check(
        rows(Kind.MONEY, "三百五十" to "350", "五千九百九十九" to "5999"),
        rows(Kind.PERCENT, "百分之三点五" to "3.5%", "百分之二十" to "20%", "百分之九十九" to "99%"),
        rows(Kind.MODEL, "一点二三" to "1.23", "二点一点三" to "2.1.3", "四" to "4", "十五" to "15", "三点十二" to "3.12"),
        rows(Kind.RATIO, "四分之三" to "3/4", "三比二" to "3:2"),
    )

    @Test
    @Uat("VO-06")
    fun whatDoesNotReadAsItsKindStaysAsSaid() {
        check(
            rows(Kind.VALUE, "一点二点三" to "一点二点三", "万一" to "万一", "二十三四" to "二十三四", "一千零" to "一千零", "三点十" to "三点十"),
            rows(Kind.TIME, "八点七十" to "八点七十", "万点" to "万点"),
            // 24 is an hour alone (24:30 is no clock's, review F5962, the user's decision 2026-11-09), a quarter
            // one or three, a lone minute character maybe 八点五十 misheard (the user's decision: 八点五 as said)
            rows(Kind.TIME, "二十四点三十" to "二十四点三十", "二十四点半" to "二十四点半", "八点九刻" to "八点九刻", "八点五" to "八点五"),
            // 两 is no digit said one by one (review F6171)
            rows(Kind.VALUE, "三点两" to "三点两"),
            rows(Kind.PERCENT, "百分之几" to "百分之几", "百分之百" to "百分之百"),
            rows(Kind.RATIO, "三四" to "三四", "几比二" to "几比二"),
            rows(Kind.MODEL, "V点" to "V点"),
            rows(Kind.NONE, "二十" to "二十"),
        )
        assertEquals(Kind.PERCENT, Kind.of("percent"))
    }

    // review F5809: %02d wrote 10:٠٥ with the phone in Arabic or Persian
    @Test
    fun digitsWhateverThePhonesLanguage() {
        val format = Locale.getDefault(Locale.Category.FORMAT)
        try {
            assertRows(
                listOf("ar-EG", "fa-IR").flatMap { tag -> listOf("十点零五分" to "10:05", "八点五分" to "8:05").map { tag to it } },
                { (tag, pair) -> "${pair.first} in $tag" },
            ) { (tag, pair) ->
                Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(tag))
                assertEquals(pair.second, ChineseNumbers.written(pair.first, Kind.TIME))
            }
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, format)
        }
    }

    // review F6387: a newer tagger's class: its runs as said, not the polish failing to load
    @Test
    fun aClassThisBuildDoesNotKnowIsNone() {
        assertEquals(Kind.NONE, Kind.of("ordinal"))
        assertEquals(Kind.TIME, Kind.of("time"))
    }
}
