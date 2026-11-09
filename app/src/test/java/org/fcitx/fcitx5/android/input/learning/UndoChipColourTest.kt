/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.learning

import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.input.Contrast
import org.junit.Assert.assertTrue
import org.junit.Test

/** 撤销 reads on the chip in every built-in theme: 4.5:1, normal-size text's (§4). */
class UndoChipColourTest {

    // every built-in theme's version, as its file has it (a file added is checked too), and Transparent's two
    private val presets = TestThemes.builtins.map { it.second } + TestThemes.TransparentLight + TestThemes.TransparentDark

    @Test
    fun undoIsLegibleOnThePopupColourInEveryTheme() {
        assertTrue(presets.size >= 8)
        val short = presets.map { it.name to Contrast.ratio(UndoChip.undoColour(it), it.popupBackgroundColor) }
            .filter { it.second < Contrast.TEXT }
        assertTrue("below 4.5:1: $short", short.isEmpty())
    }

    @Test
    fun anAccentThatReadsAlreadyIsKept() {
        for (theme in presets) {
            if (Contrast.ratio(theme.accentKeyBackgroundColor, theme.popupBackgroundColor) >= Contrast.TEXT) {
                assertTrue(theme.name, UndoChip.undoColour(theme) == theme.accentKeyBackgroundColor)
            }
        }
    }
}
