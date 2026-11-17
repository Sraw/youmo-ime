/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.KeyboardSizes.Setting
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.rules.ScreenShape
import org.junit.Assert.assertEquals
import org.junit.Test

// keyboard-scene's layout check cannot read this module, so it keeps the app's heights as numbers: held to them here
class LayoutCheckHeightsTest {

    @Test
    fun theLayoutChecksScenesAreAsHighAsTheAppsKeys() {
        for (level in listOf(KeyboardHeightLevels.ALL[2], KeyboardHeightLevels.ALL[KeyboardHeightLevels.COMPACT])) {
            val sizes = KeyboardSizes(Setting(false, level.portrait, level.landscapeLevel), Setting(false, 0, 0), Setting(false, 0, 0), false)
            for (s in LayoutCheck.SCREENS) {
                val landscape = ScreenShape.landscape(s.turned, s.widthDp)
                val large = minOf(s.widthDp, s.tallDp) >= KeyboardHeightLevels.LARGE_SHORT_SIDE_DP
                assertEquals("$s $level", sizes.heightPx(s.tallDp, landscape, 1f, large), LayoutCheck.keysDp(s, level == KeyboardHeightLevels.ALL[KeyboardHeightLevels.COMPACT]))
                assertEquals("$s", large, s.large)
            }
        }
    }

    @Test
    fun theLowestSceneIsTheFloorsFourRows() {
        assertEquals(KeyboardSizes.LANDSCAPE_ROWS * KeyboardHeightCap.MIN_ROW_DP, LayoutCheck.LOWEST_DP)
    }
}
