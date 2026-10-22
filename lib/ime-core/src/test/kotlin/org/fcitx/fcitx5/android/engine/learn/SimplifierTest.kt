/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.junit.Assert.assertEquals
import org.junit.Test

/** [Simplifier] on OpenCC's tables as the app ships them: the usual simplified one, and none for one simplified already. */
class SimplifierTest {
    private val s = openccSimplifier

    @Test
    fun traditionalCharactersAreTheUsualSimplifiedOnes() {
        assertEquals("手机", s.simplify("手機"))
        assertEquals("什么", s.simplify("什麼"))
        assertEquals("点了", s.simplify("點了"))
        assertEquals("发发", s.simplify("發髮"))
        assertEquals("里面", s.simplify("裏面"))
        assertEquals("干活", s.simplify("幹活"))
    }

    @Test
    fun simplifiedTextIsNeverChanged() {
        for (text in listOf("覆盖", "已覆盖", "乾坤", "慰藉", "瞭望", "麽", "于是", "什么都行", "机几干复借了", "哪吒", "由於"))
            assertEquals(text, text, s.simplify(text))
    }

    // review-1a-r2 S3: in traditional text, a character simplified text has too is the simplified one
    @Test
    fun inTraditionalTextCharactersOfBothAreSimplified() {
        assertEquals("干净", s.simplify("乾淨"))
        assertEquals("这是由于", s.simplify("這是由於"))
        assertEquals("特征说", s.simplify("特徵說"))
        assertEquals("说以后", s.simplify("說以後"))
        // verify-1a-r2 S3: one OpenCC lists first as itself stays
        assertEquals("覆盖", s.simplify("覆蓋"))
        assertEquals("这是慰藉", s.simplify("這是慰藉"))
        assertEquals("这是大阪", s.simplify("這是大阪"))
    }

    @Test
    fun tablesOfNoneAndOfOddLinesChangeNothing() {
        val odd = Simplifier.read("# a comment\n軟體\t软件\n點\n\n".toByteArray(), ByteArray(0))
        assertEquals("軟體點", odd.simplify("軟體點"))
        assertEquals("點", Simplifier.NONE.simplify("點"))
        // listed simplified the other way: kept
        assertEquals("點", Simplifier.read("點\t点\n".toByteArray(), "點\t點\n".toByteArray()).simplify("點"))
        // its own reading too: changed only in traditional text
        val both = Simplifier.read("乾\t干 乾\n淨\t净\n覆\t覆 复\n".toByteArray(), ByteArray(0))
        assertEquals("乾", both.simplify("乾"))
        assertEquals("干净", both.simplify("乾淨"))
        // listed first as itself: itself, in traditional text too
        assertEquals("覆净", both.simplify("覆淨"))
    }
}
