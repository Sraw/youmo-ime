/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.rules

/**
 * Whether the keyboard is laid out as 横屏 (landscape): every setting with a portrait and a landscape value,
 * the theme's `@landscape`, the split's AUTO, the low keys' marks all read this one answer. Here, not in
 * ime-core, so the web tools ask it too: the app, the theme maker's page and the
 * layout check's scenes (LayoutCheck) alike, so none of them lays out a keyboard as a device never shows it
 * (673 dp wide drawn upright).
 */
object ScreenShape {

    /**
     * A window this wide in dp or wider is wide, held either way: a foldable's inner screen (some 673 dp
     * upright), a tablet upright (some 800). Android's medium width class; the split's AUTO splits from it.
     */
    const val WIDE_DP = 600

    /** A window [widthDp] wide, [turned] (held sideways, the configuration's orientation). */
    fun landscape(turned: Boolean, widthDp: Int): Boolean = turned || widthDp >= WIDE_DP
}
