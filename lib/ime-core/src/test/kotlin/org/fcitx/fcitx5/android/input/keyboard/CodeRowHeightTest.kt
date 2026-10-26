/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.host.Engines
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeRowHeightTest {

    private data class Row(val im: String?, val pinyin14: Boolean, val shuangpinGrouped: Boolean, val dp: Int, val why: String)

    @Test
    fun theRowIsTallOnlyWhereTheInputMethodShowsTheStrip() {
        val tall = CodeRowHeight.STRIP_DP
        val thin = CodeRowHeight.THIN_DP
        assertEquals(32, tall)
        assertEquals(18, thin)
        listOf(
            Row(Engines.PINYIN, true, false, tall, "拼音 on the 14 keys"),
            Row(Engines.PINYIN, false, true, thin, "拼音 全键盘, whatever 双拼's keyboard"),
            Row(Engines.SHUANGPIN, false, true, tall, "双拼 on the 18 or nine keys"),
            Row(Engines.SHUANGPIN, true, false, thin, "双拼 全键盘, whatever pinyin's keyboard"),
            Row(Engines.T9, true, true, thin, "九键拼音 has its column"),
            Row(Engines.ENGLISH, true, true, thin, "English"),
            Row(Engines.HANDWRITING, true, true, thin, "handwriting"),
            Row("wbx", true, true, thin, "a table"),
            Row(null, true, true, thin, "no input method yet"),
        ).forEach {
            assertEquals(it.why, it.dp, CodeRowHeight.dp(it.im, it.pinyin14, it.shuangpinGrouped))
            assertEquals(it.why, it.dp == tall, CodeRowHeight.showsStrip(it.im, it.pinyin14, it.shuangpinGrouped))
        }
    }
}
