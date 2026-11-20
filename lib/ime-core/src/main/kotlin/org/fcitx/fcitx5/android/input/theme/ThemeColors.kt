/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.scene.look.BarBase
import org.fcitx.fcitx5.android.scene.look.KeyKind
import org.fcitx.fcitx5.android.scene.look.Keys
import org.fcitx.fcitx5.android.scene.look.Palette

/** Which palette colour each part uses, the app's and the theme maker's alike: blocks show the background between keys; flat the keyboard colour, its space bar the space bar colour. */
object ThemeColors {

    fun board(p: Palette, keys: Keys): Int = if (keys.blocks) p.backgroundColor else p.keyboardColor

    /** Null: the keyboard's base seen through. */
    fun bar(p: Palette, bar: BarBase): Int? = if (bar == BarBase.Color) p.barColor else null

    /** Clipboard, status area, all candidates: the bar's colour on a flat keyboard, seen through on blocks. */
    fun panel(p: Palette, keys: Keys): Int? = if (keys.blocks) null else p.barColor

    /** Over the picture, the colour laid on it. */
    fun face(p: Palette, kind: KeyKind, blocks: Boolean): Int = when (kind) {
        KeyKind.Letter -> p.keyBackgroundColor
        KeyKind.Function -> p.altKeyBackgroundColor
        KeyKind.Space -> if (blocks) p.keyBackgroundColor else p.spaceBarColor
        KeyKind.Enter -> p.accentKeyBackgroundColor
    }

    /** Alpha 0..255. */
    fun dim(brightness: Int): Int = (100 - brightness) * 255 / 100
}
