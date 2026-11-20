/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

/** Character and word cards in a held candidate's menu: one column upright, floating or paged; two in landscape, about half the height. */
object CharCardLayout {

    /** Of the candidates' text. */
    const val CHAR_SCALE = 3f

    /** Of the candidates' text. */
    const val WORD_SCALE = 2f

    /** dp, for a row or button tapped. */
    const val MIN_ROW_DP = 48

    /** Not in the [paged] window: by the cursor with room upright, two columns would cover the text. */
    fun twoColumns(landscape: Boolean, floating: Boolean, paged: Boolean = false): Boolean = landscape && !floating && !paged

    /** Fewer in landscape, where the screen is low. */
    fun readingRows(twoColumns: Boolean): Int = if (twoColumns) LANDSCAPE_ROWS else PORTRAIT_ROWS

    /** Two in landscape, where a menu over the docked keyboard has some 190 dp. */
    fun wordRows(twoColumns: Boolean): Int = if (twoColumns) LANDSCAPE_WORD_ROWS else PORTRAIT_ROWS

    /** Half the screen or [least]; [twoColumns] two thirds, else parts and stroke order wrap. */
    fun width(screen: Int, least: Int, twoColumns: Boolean = false): Int =
        maxOf(if (twoColumns) screen * 2 / 3 else screen / 2, least)

    private const val PORTRAIT_ROWS = 4
    private const val LANDSCAPE_ROWS = 3
    private const val LANDSCAPE_WORD_ROWS = 2
}
