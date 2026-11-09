/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.scene.look.Insets
import org.fcitx.fcitx5.android.scene.look.NineSlice
import org.fcitx.fcitx5.android.scene.look.Palette

/** A look's parts for the tests of what ime-core makes of it (keyboard-scene's ThemeLookTest has the same). */
object LookFixtures {
    val PALETTE = Palette(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21,
    )

    fun slice(file: String) = NineSlice(file, Insets(24, 24, 24, 24), 3)
}
