/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * The code row's height (the row above the candidate bar: the code being typed and the syllable
 * strip), by the layout shown ([ImKeyboards.layoutOf]). Tall enough to tap ([STRIP_DP]) only while it
 * has the strip: the 14 and 18 keys, and 双拼's nine keys. Thin ([THIN_DP], as in 0.3.3)
 * in every other, so the full keyboards, tables and English lose nothing to it. The keyboard
 * changing by the difference between the two kinds is accepted (the user, 2026-10-26), also within
 * one input method as its keyboard changes (pinyin's full keys to its 14, from ⋯ or its page).
 */
object CodeRowHeight {
    const val THIN_DP = 18
    const val STRIP_DP = 32

    /**
     * Whether [layout] shows the syllable strip: the 14 and 18 keys, pinyin's and 双拼's, and
     * 双拼's nine keys, whose letters are grouped. Pinyin's nine keys show theirs in the column.
     */
    fun showsStrip(layout: ImKeyboards.Layout): Boolean = layout in STRIP

    /** The row's height in dp for [layout], from [showsStrip]. */
    fun dp(layout: ImKeyboards.Layout): Int = if (showsStrip(layout)) STRIP_DP else THIN_DP

    private val STRIP = setOf(
        ImKeyboards.Layout.PINYIN14,
        ImKeyboards.Layout.ZHUYIN_NINE,
        ImKeyboards.Layout.PINYIN18,
        ImKeyboards.Layout.SHUANGPIN18,
        ImKeyboards.Layout.SHUANGPIN14,
        ImKeyboards.Layout.SHUANGPIN_NINE,
    )
}
