/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateBarHeightTest {

    @Test
    fun pinyinAddsALineAboveAndZhuyinNoHeight() {
        // text sp, style, bar dp; off, the bar follows the text and the default keeps the old bar
        val table = listOf(
            Triple(14, ReadingStyle.OFF, 28),
            Triple(20, ReadingStyle.OFF, 40),
            Triple(28, ReadingStyle.OFF, 56),
            Triple(14, ReadingStyle.PINYIN, 36),
            Triple(20, ReadingStyle.PINYIN, 52),
            Triple(28, ReadingStyle.PINYIN, 73),
            Triple(20, ReadingStyle.ZHUYIN, 40),
            Triple(28, ReadingStyle.ZHUYIN, 56),
        )
        for ((sp, style, dp) in table) assertEquals("$sp $style", dp, CandidateBarHeight.dp(sp, style))
    }

    @Test
    fun theThemesHeightScalesTheBarNotItsReadings() {
        // 060-render-p3 T6b: `bar height=` of the bar 候选字号 gives, rounded; the readings' line added as it is
        assertEquals(40, CandidateBarHeight.dp(20, ReadingStyle.OFF, 100.0))
        assertEquals(48, CandidateBarHeight.dp(20, ReadingStyle.OFF, 120.0))
        assertEquals(32, CandidateBarHeight.dp(20, ReadingStyle.OFF, 80.0))
        assertEquals(39, CandidateBarHeight.dp(14, ReadingStyle.OFF, 140.0))
        assertEquals(48 + 12, CandidateBarHeight.dp(20, ReadingStyle.PINYIN, 120.0))
    }

    @Test
    @Uat("PY-111")
    fun aHintsReadingFillsTheRoomBetweenItsBounds() {
        // text 40 px, a line 1.2 of its size: 12 px of room fits 10 px, 30 px no more than half the text, 2 px the floor
        assertEquals(10f, CandidateBarHeight.hintSize(40f, 12f, 1.2f, 7f), 1e-4f)
        assertEquals(20f, CandidateBarHeight.hintSize(40f, 30f, 1.2f, 7f), 1e-4f)
        assertEquals(7f, CandidateBarHeight.hintSize(40f, 2f, 1.2f, 7f), 1e-4f)
        // a floor above half the text gives way to it
        assertEquals(5f, CandidateBarHeight.hintSize(10f, 1f, 1.2f, 7f), 1e-4f)
    }

    // xíng/háng… over 行: smaller till it fits, not below the floor, then the candidate widens
    @Test
    fun readingsShrinkToTheFloorBeforeTheCandidateWidens() {
        assertEquals(10f, CandidateBarHeight.readingsSize(20f, 20f, 15f, 7f), 0f)
        assertEquals(8f, CandidateBarHeight.readingsSize(20f, 20f, 25f, 7f), 1e-4f)
        assertEquals(7f, CandidateBarHeight.readingsSize(20f, 20f, 60f, 7f), 0f)
        assertEquals(10f, CandidateBarHeight.readingsSize(20f, 20f, 60f, 0f), 0f)
        // a floor above half the text: half the text
        assertEquals(5f, CandidateBarHeight.readingsSize(10f, 10f, 60f, 7f), 0f)
    }
}
