/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

/**
 * Which of the palette's colours each part is drawn in, the app's and the theme maker's alike. As
 * before theme file 3.0, where the key border setting chose them: a keyboard of blocks shows the
 * background colour between its keys and the bar over it; a flat one the keyboard colour, and its
 * space bar is the space bar colour.
 */
object ThemeColors {

    /** Under the keyboard, its base a colour. */
    fun board(p: Palette, keys: Keys): Int = if (keys.blocks) p.backgroundColor else p.keyboardColor

    /** Under the bar: null for none, the keyboard's base seen through ([BarBase.Transparent], or the picture's part). */
    fun bar(p: Palette, bar: BarBase): Int? = if (bar == BarBase.Color) p.barColor else null

    /**
     * Under a panel over the keyboard (clipboard, status area, all candidates): the bar's colour on a
     * flat keyboard, its keys' base seen through on one of blocks, as before 3.0.
     */
    fun panel(p: Palette, keys: Keys): Int? = if (keys.blocks) null else p.barColor

    /** A key's face of [kind] in its kind's colour ([KeyFill.Color]); over the picture, the colour laid on it. */
    fun face(p: Palette, kind: KeyKind, blocks: Boolean): Int = when (kind) {
        KeyKind.Letter -> p.keyBackgroundColor
        KeyKind.Function -> p.altKeyBackgroundColor
        KeyKind.Space -> if (blocks) p.keyBackgroundColor else p.spaceBarColor
        KeyKind.Enter -> p.accentKeyBackgroundColor
    }

    /** The black laid over the picture at [brightness] percent, its alpha 0..255. */
    fun dim(brightness: Int): Int = (100 - brightness) * 255 / 100

    /** The clipboard entries' corners and the editing buttons', dp: as their own settings had them, half and twice the keys'. */
    fun entryRadius(keys: Keys): Float = keys.radiusDp / 2f

    fun buttonRadius(keys: Keys): Float = keys.radiusDp * 2f
}
