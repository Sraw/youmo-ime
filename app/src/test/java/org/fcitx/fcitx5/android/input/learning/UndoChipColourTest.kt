/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.learning

import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.Contrast
import org.junit.Assert.assertTrue
import org.junit.Test

/** 撤销 reads on the chip in every built-in theme: 4.5:1, normal-size text's (§4). */
class UndoChipColourTest {

    // found by reflection, so a preset added is checked too
    private val presets = ThemePreset::class.java.declaredFields
        .filter { Theme.Builtin::class.java.isAssignableFrom(it.type) }
        .map { it.isAccessible = true; it.get(ThemePreset) as Theme.Builtin }

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
