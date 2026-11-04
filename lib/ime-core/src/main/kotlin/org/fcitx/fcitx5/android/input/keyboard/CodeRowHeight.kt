/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * The code row's height (the row above the candidate bar: the code being typed and the syllable
 * strip), by the layout shown ([ImKeyboards.layoutOf]). Tall enough to tap ([STRIP_DP]) only while it
 * has the strip: the 14 and 18 keys, and 双拼's and 注音's nine keys. Thin ([THIN_DP], as in 0.3.3)
 * in every other, so the full keyboards, tables and English lose nothing to it. The keyboard
 * changing by the difference between the two kinds is accepted (the user, 2026-10-26), also within
 * one input method as its keyboard changes (pinyin's full keys to its 14, from ⋯ or its page).
 * That is the row embedded in the keyboard; floating over the app (编码行 浮在键盘上方, 0.5.1) the
 * keyboard gives it nothing ([keyboardDp]) and the strip has its own height ([stripDp]).
 */
object CodeRowHeight {
    const val THIN_DP = 18
    const val STRIP_DP = 32

    /** The floating strip's height without the syllables: the thin row floating looked like a slit. */
    const val FLOAT_DP = 24

    /**
     * Whether [layout] shows the syllable strip: the 14 and 18 keys, pinyin's and 双拼's, and
     * 双拼's and 注音's nine keys, whose letters are grouped. Pinyin's nine keys show theirs in the column.
     */
    fun showsStrip(layout: ImKeyboards.Layout): Boolean = layout in STRIP

    /** The row's height in dp for [layout], from [showsStrip]. */
    fun dp(layout: ImKeyboards.Layout): Int = if (showsStrip(layout)) STRIP_DP else THIN_DP

    /** The keyboard's room for the row on [layout]: [dp] embedded, none when it [floats] over the app. */
    fun keyboardDp(layout: ImKeyboards.Layout, floats: Boolean): Int = if (floats) 0 else dp(layout)

    /** The floating strip's own height on [layout]: tall enough to tap with the syllables, [FLOAT_DP] else. */
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
