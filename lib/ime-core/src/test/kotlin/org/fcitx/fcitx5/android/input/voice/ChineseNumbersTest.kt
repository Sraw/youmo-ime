/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The cases are the recognizer's own output, on the voice evaluation sets. */
class ChineseNumbersTest {

    private fun check(expected: String, said: String) = assertEquals(said, expected, ChineseNumbers.convert(said))

    @Test
    fun yearsAndDates() {
        check("2002年的美国盐湖城冬奥会上", "二零零二年的美国盐湖城冬奥会上")
        check("以1967年中东战争前的边界", "以一九六七年中东战争前的边界")
        check("98年", "九八年")
        check("6月23日。", "六月二十三日。")
        check("于7月8日", "于七月八日")
        check("90后占", "九零后占")
        check("80年代", "八零年代")
    }

    @Test
    fun percentagesAndDecimals() {
        check("收窄了57%。", "收窄了百分之五十七。")
        check("1%。", "百分之一。")
        check("2.5%", "百分之二点五")
        check("收盘价13.6元", "收盘价十三点六元")
        check("同比增长268.5", "同比增长二百六十八点五")
        check("以低价5.5亿元", "以低价五点五亿元")
        check("0.5", "零点五")
        check("兼容802.11", "兼容八零二点一一")
        check("百分之百确定", "百分之百确定")
    }

    @Test
    fun quantities() {
        check("继承266万余元", "继承二六六万余元")
        check("33万人中只有6000人", "三十三万人中只有六千人")
        check("面积约5万平方米", "面积约五万平方米")
        check("仅为400亿美元", "仅为四百亿美元")
        check("交价款3000亿元", "交价款三千亿元")
        check("在1500米自由泳", "在一千五百米自由泳")
        // a digit after a place is the next place down; after 零 a one
        check("12000元", "一万二元")
        check("3500块", "三千五块")
        check("25000人", "两万五人")
        check("120000", "十万二")
        check("250000", "二十万五")
        check("253000", "二十五万三")
        check("120个", "一百二个")
        check("108个", "一百零八个")
        check("1005", "一千零五")
        check("2020年", "两千零二十年")
        check("10500", "一万零五百")
        check("336005", "三十三万六千零五")
        check("3万亿", "三万亿")
        check("3.5万亿", "三点五万亿")
        check("岛以北120公里", "岛以北一百二十公里")
        check("117名选手", "一一七名选手")
        check("第32届", "第三十二届")
        check("10到60分钟", "十到六十分钟")
        check("10分钟", "十分钟")
        check("123000", "十二万三千")
    }

    @Test
    fun times() {
        check("6点半到7点半", "六点半到七点半")
        check("早上9点", "早上九点")
        check("9点15分", "九点十五分")
        check("12点", "十二点")
        check("下午3点10分出发", "下午三点十分出发")
        check("约在3点10分。", "约在三点十分。")
        check("3点10分", "三点十分")
        check("10点整", "十点整")
        check("10点05分", "十点零五分")
        check("有2点半的会", "有两点半的会")
        check("1点半开会", "一点半开会")
        // "a bit" (一点半点) only with 一 for the hour, and no time word before it
        check("7点半点外卖", "七点半点外卖")
        check("下午1点半点名", "下午一点半点名")
        // a long run before 点 is no hour, and does not throw
        check("12345678901234567890.5分", "一二三四五六七八九零一二三四五六七八九零点五分")
        // no 分: a time or a decimal, not to be guessed
        check("九点五十", "九点五十")
    }

    @Test
    fun wordsThatOnlyLookLikeNumbers() {
        for (word in listOf(
            "一个", "一种", "一些", "进一步", "如出一辙", "一夜之间", "三年", "五分钟", "两人", "第四期",
            "三四线城市", "七八个", "六七桌", "十分有趣", "十足", "十全十美", "万一", "千万", "成千上万",
            "雷霆万钧", "上千民众", "几十个人", "几十万", "数百万加仑", "十几个", "二十几岁", "一一接纳",
            "一五一十", "十六十七倍", "有一点", "一点一点", "金九", "六比六", "零",
            // misrecognitions, not to be made into something else
            "二十亿万元", "八点五百分之一",
            // a point made, not an hour
            "这一点十分重要", "这三点十分关键", "第三点", "这一点整体来看",
            // sayings
            "十字路口", "十有八九", "三十而立", "十万火急", "十万八千里", "三三两两", "七七八八",
            "百分之三十几", "差一点整天", "一千零零五", "一千零", "他有一点十分奇怪", "还有两点十分重要",
            // 一点 as "a bit"
            "没有一点半点", "有一点半信半疑",
        )) check(word, word)
    }

    @Test
    fun digitsWhateverThePhonesLanguage() {
        // %02d wrote 10点٠٥分 with the phone in Arabic or Persian
        val format = Locale.getDefault(Locale.Category.FORMAT)
        try {
            for (tag in listOf("ar-EG", "fa-IR")) {
                Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(tag))
                check("10点05分", "十点零五分")
            }
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, format)
        }
    }
}
