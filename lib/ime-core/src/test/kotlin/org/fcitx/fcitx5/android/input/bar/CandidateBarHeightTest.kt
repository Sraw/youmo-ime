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
    fun theBarFollowsTheTextAndTheDefaultKeepsTodaysBar() {
        assertEquals(listOf(28, 40, 56), listOf(14, 20, 28).map { CandidateBarHeight.dp(it) })
    }

    @Test
    fun pinyinAddsALineAboveAndZhuyinNoHeight() {
        // text sp, style, bar dp
        val table = listOf(
            Triple(14, ReadingStyle.OFF, 28),
            Triple(20, ReadingStyle.OFF, 40),
            Triple(14, ReadingStyle.PINYIN, 36),
            Triple(20, ReadingStyle.PINYIN, 52),
            Triple(28, ReadingStyle.PINYIN, 73),
            Triple(20, ReadingStyle.ZHUYIN, 40),
            Triple(28, ReadingStyle.ZHUYIN, 56),
        )
        for ((sp, style, dp) in table) assertEquals("$sp $style", dp, CandidateBarHeight.dp(sp, style))
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
}
