/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.host.Engines

/**
 * The code row's height (the row above the candidate bar: the code being typed and the syllable
 * strip), by input method. Tall enough to tap ([STRIP_DP]) only while the input method shown has
 * the strip: pinyin on the 14 keys, 双拼 on the 18 or the nine keys. Thin ([THIN_DP], as in 0.3.3)
 * in every other, so the full keyboards, tables and English lose nothing to it. The keyboard
 * changing by the difference between the two kinds is accepted (the user, 2026-10-26); within one
 * input method it never changes.
 */
object CodeRowHeight {
    const val THIN_DP = 18
    const val STRIP_DP = 32

    /**
     * Whether input method [im] shows the syllable strip: pinyin with the 14 keys ([pinyin14]),
     * 双拼 with the 18 or nine keys ([shuangpinGrouped]): the input methods whose letters are grouped
     * (the app's `lettersOf`, kept in step by a test).
     */
    fun showsStrip(im: String?, pinyin14: Boolean, shuangpinGrouped: Boolean): Boolean = when (im) {
        Engines.PINYIN -> pinyin14
        Engines.SHUANGPIN -> shuangpinGrouped
        else -> false
    }

    /** The row's height in dp for [im], from [showsStrip]. */
    fun dp(im: String?, pinyin14: Boolean, shuangpinGrouped: Boolean): Int =
        if (showsStrip(im, pinyin14, shuangpinGrouped)) STRIP_DP else THIN_DP
}
