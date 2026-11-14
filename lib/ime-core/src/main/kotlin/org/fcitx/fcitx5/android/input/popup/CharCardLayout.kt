/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

/**
 * How a character's card (CharCardView, in a held candidate's menu) and a word's (WordCardView) are laid
 * out: one column on a phone held upright, on the floating keyboard and in the physical keyboard's paged
 * window, two side by side in landscape (the readings beside the radical, strokes, parts and order; a
 * word's rows beside its buttons), so it takes about half the height; the character [CHAR_SCALE] times the
 * candidates' size, a word [WORD_SCALE]; at most [readingRows] readings and [wordRows] characters shown, the
 * rest scrolled to; each row tapped at least [MIN_ROW_DP] high.
 */
object CharCardLayout {

    /** The character's size, of the candidates' text. */
    const val CHAR_SCALE = 3f

    /** A word's size, of the candidates' text: two characters or more beside each other. */
    const val WORD_SCALE = 2f

    /** The least height of a row or button tapped, in dp (a word's characters, the way back, 上屏, 复制). */
    const val MIN_ROW_DP = 48

    /**
     * Whether the card is two columns: in landscape, docked; not in the physical keyboard's [paged] window,
     * which is by the cursor with no keyboard under it, room enough upright, and two columns would cover the
     * text being read.
     */
    fun twoColumns(landscape: Boolean, floating: Boolean, paged: Boolean = false): Boolean = landscape && !floating && !paged

    /** The readings shown before they scroll: fewer in landscape, where the screen is low. */
    fun readingRows(twoColumns: Boolean): Int = if (twoColumns) LANDSCAPE_ROWS else PORTRAIT_ROWS

    /**
     * A word card's characters shown before they scroll: as many as a character's readings upright; two in
     * landscape, where a menu over the docked keyboard has some 190 dp, measured (061-word-card.md §3.4).
     */
    fun wordRows(twoColumns: Boolean): Int = if (twoColumns) LANDSCAPE_WORD_ROWS else PORTRAIT_ROWS

    /**
     * The card's width, a character's or a word's (BaseInputView): half the screen or [least], whichever is more; [twoColumns], two
     * thirds of it, else the parts and the stroke order beside the readings wrap a line in two.
     */
    fun width(screen: Int, least: Int, twoColumns: Boolean = false): Int =
        maxOf(if (twoColumns) screen * 2 / 3 else screen / 2, least)

    private const val PORTRAIT_ROWS = 4
    private const val LANDSCAPE_ROWS = 3
    private const val LANDSCAPE_WORD_ROWS = 2
}
