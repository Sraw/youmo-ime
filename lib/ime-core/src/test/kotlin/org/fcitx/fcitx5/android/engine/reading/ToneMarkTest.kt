/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToneMarkTest {

    @Test
    fun pinyinMarkGoesWhereTheRulesPutIt() {
        val expected = listOf(
            "hao" to 3 to "hǎo", "xiu" to 1 to "xiū", "gui" to 3 to "guǐ", "lv" to 4 to "lǜ", "er" to 2 to "ér",
            "lve" to 4 to "lüè", "lou" to 2 to "lóu", "zhuang" to 4 to "zhuàng", "n" to 2 to "ń",
            "ng" to 3 to "ňg", "hm" to 4 to "hm̀", "xie" to 4 to "xiè",
        )
        expected.forEach { (st, out) -> assertEquals(out, ToneMark.pinyin(st.first, st.second)) }
    }

    @Test
    fun neutralAndUnknownAreLeftBare() {
        assertEquals("zi", ToneMark.pinyin("zi", ToneMark.NEUTRAL))
        assertEquals("nü", ToneMark.pinyin("nv", ToneMark.NONE))
    }

    @Test
    fun zhuyinSigns() {
        assertEquals("ㄊㄧㄢ", ToneMark.zhuyin("ㄊㄧㄢ", 1))
        assertEquals("ㄉㄚˊ", ToneMark.zhuyin("ㄉㄚ", 2))
        assertEquals("ㄏㄠˇ", ToneMark.zhuyin("ㄏㄠ", 3))
        assertEquals("ㄉㄚˋ", ToneMark.zhuyin("ㄉㄚ", 4))
        assertEquals("˙ㄗ", ToneMark.zhuyin("ㄗ", ToneMark.NEUTRAL))
        assertEquals("ㄗ", ToneMark.zhuyin("ㄗ", ToneMark.NONE))
    }

    @Test
    fun parseReadsMarksDigitsAndUmlauts() {
        assertEquals("hao" to 3, ToneMark.parse("hǎo"))
        assertEquals("lve" to 4, ToneMark.parse("lüè"))
        assertEquals("nv" to 3, ToneMark.parse("nǚ"))
        assertEquals("m" to 2, ToneMark.parse("ḿ"))
        assertEquals("zi" to ToneMark.NEUTRAL, ToneMark.parse("zi"))
        assertEquals("hao" to 3, ToneMark.parse("hao3"))
        assertEquals("ni" to 3, ToneMark.parse("Nǐ"))
        assertNull(ToneMark.parse("a-b"))
        assertNull(ToneMark.parse(""))
    }

    @Test
    fun everyMarkedFormParsesBack() {
        for (s in listOf("hao", "lve", "lv", "gui", "liu", "zhuang", "er", "n", "ng", "hm"))
            for (t in 1..4) assertEquals(s to t, ToneMark.parse(ToneMark.pinyin(s, t)))
    }
}
