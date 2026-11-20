/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * The code row above the candidate bar: tall ([STRIP_DP]) only with the syllable strip, thin ([THIN_DP]) otherwise so
 * full keyboards, tables and English lose nothing; the jump between the two is accepted. Floating over the app, the
 * keyboard gives it nothing ([keyboardDp]) and the strip sizes itself ([stripDp]).
 */
object CodeRowHeight {
    const val THIN_DP = 18
    const val STRIP_DP = 32

    /** The thin row floating looked like a slit. */
    const val FLOAT_DP = 24

    /** The 14 and 18 keys, and 双拼's and 注音's nine keys; pinyin's nine keys show syllables in the column. */
    fun showsStrip(layout: ImKeyboards.Layout): Boolean = layout in STRIP

    fun dp(layout: ImKeyboards.Layout): Int = if (showsStrip(layout)) STRIP_DP else THIN_DP

    /** None when it [floats] over the app. */
    fun keyboardDp(layout: ImKeyboards.Layout, floats: Boolean): Int = if (floats) 0 else dp(layout)

    fun stripDp(layout: ImKeyboards.Layout): Int = if (showsStrip(layout)) STRIP_DP else FLOAT_DP

    private val STRIP = setOf(
        ImKeyboards.Layout.PINYIN14,
        ImKeyboards.Layout.ZHUYIN_NINE,
        ImKeyboards.Layout.PINYIN18,
        ImKeyboards.Layout.SHUANGPIN18,
        ImKeyboards.Layout.SHUANGPIN14,
        ImKeyboards.Layout.SHUANGPIN_NINE,
    )
}
