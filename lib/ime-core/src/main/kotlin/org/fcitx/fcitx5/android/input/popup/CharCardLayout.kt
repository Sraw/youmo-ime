/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

/**
 * How a character's card (CharCardView, in a held candidate's menu) is laid out: one column on a
 * phone held upright and on the floating keyboard, two side by side in landscape (the readings
 * beside the radical, strokes, parts and order), so it takes about half the height; the character
 * [CHAR_SCALE] times the candidates' size; at most [readingRows] readings shown, the rest scrolled to.
 */
object CharCardLayout {

    /** The character's size, of the candidates' text. */
    const val CHAR_SCALE = 3f

    /** Whether the card is two columns: in landscape, docked. */
    fun twoColumns(landscape: Boolean, floating: Boolean): Boolean = landscape && !floating

    /** The readings shown before they scroll: fewer in landscape, where the screen is low. */
    fun readingRows(twoColumns: Boolean): Int = if (twoColumns) LANDSCAPE_ROWS else PORTRAIT_ROWS

    /**
     * The card's width (BaseInputView): half the screen or [least], whichever is more; [twoColumns], two
     * thirds of it, else the parts and the stroke order beside the readings wrap a line in two.
     */
    fun width(screen: Int, least: Int, twoColumns: Boolean = false): Int =
        maxOf(if (twoColumns) screen * 2 / 3 else screen / 2, least)

    private const val PORTRAIT_ROWS = 4
    private const val LANDSCAPE_ROWS = 3
}
