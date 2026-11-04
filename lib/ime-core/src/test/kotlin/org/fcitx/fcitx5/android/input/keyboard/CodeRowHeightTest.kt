/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards.Layout
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeRowHeightTest {

    @Test
    fun theRowIsTallOnlyWhereTheLayoutShowsTheStrip() {
        val tall = CodeRowHeight.STRIP_DP
        val thin = CodeRowHeight.THIN_DP
        assertEquals(32, tall)
        assertEquals(18, thin)
        val rows = mapOf(
            Layout.PINYIN14 to tall,
            Layout.PINYIN18 to tall,
            Layout.SHUANGPIN18 to tall,
            Layout.SHUANGPIN14 to tall,
            Layout.SHUANGPIN_NINE to tall,
            // 注音九键 has no column; 大千 a symbol a key, as the full keyboard
            Layout.ZHUYIN_NINE to tall,
            Layout.ZHUYIN_FULL to thin,
            // pinyin's nine keys have their column
            Layout.PINYIN_NINE to thin,
            Layout.ENGLISH_NINE to thin,
            Layout.TEXT to thin,
            Layout.HANDWRITING to thin,
        )
        assertEquals(Layout.entries.toSet(), rows.keys)
        assertRows(rows.entries, { "${it.key}" }) { (layout, dp) ->
            assertEquals(dp, CodeRowHeight.dp(layout))
            assertEquals(dp == tall, CodeRowHeight.showsStrip(layout))
        }
    }

    // floating over the app the keyboard keeps no room for it; the strip is 24 dp, 32 with the syllables
    @Test
    fun floatingTheKeyboardHasNoRowAndTheStripItsOwnHeight() = assertRows(Layout.entries, { "$it" }) { layout ->
        assertEquals(0, CodeRowHeight.keyboardDp(layout, floats = true))
        assertEquals(CodeRowHeight.dp(layout), CodeRowHeight.keyboardDp(layout, floats = false))
        val strip = if (CodeRowHeight.showsStrip(layout)) CodeRowHeight.STRIP_DP else CodeRowHeight.FLOAT_DP
        assertEquals(strip, CodeRowHeight.stripDp(layout))
    }
}
