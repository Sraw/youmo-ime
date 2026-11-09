/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.scene.theme.Twin
import org.junit.Assert.assertEquals
import org.junit.Test
import org.fcitx.fcitx5.android.scene.theme.KeyboardSizes as ThemeSizes

class KeyboardHeightCapTest {

    @Test
    fun aHeightUnderTheCapIsKeptAndTheKeyboardGrows() = assertRows(
        "a height under the cap is kept" to {
            // portrait 851 dp, 40% = 340: 340 + 58 + 12 is well under 595
            assertEquals(340, KeyboardHeightCap.keysHeight(340, 851, 58, 12))
        },
        // 始终显示悬浮条: the docked handle (24) is added, the keys keep their height
        "a docked handle under the cap leaves the keys alone" to {
            assertEquals(340, KeyboardHeightCap.keysHeight(340, 851, 58, 12, handleHeight = 24))
        },
    )

    @Test
    fun whereTheCapBindsTheKeysGiveWay() = assertRows(
        "a tall landscape level is cut to 70% of the screen" to {
            // 393 dp landscape at 1x: 很高 60% = 236, bars 58, padding 12 -> 306 > 275
            assertEquals(275 - 58 - 12, KeyboardHeightCap.keysHeight(236, 393, 58, 12))
        },
        "a docked handle counts in the cap: the keys give up its room" to {
            assertEquals(275 - 58 - 24 - 12, KeyboardHeightCap.keysHeight(236, 393, 58, 12, handleHeight = 24))
        },
        "a screen too short for the bars gives no keys rather than a negative height" to {
            assertEquals(0, KeyboardHeightCap.keysHeight(100, 50, 58, 12))
        },
        // 060-render-p3 T5: the theme's height followed is asked for as a setting's is, and cut alike
        "the theme sets a height: the cap binds it as a level" to {
            val sizes = KeyboardSizes(
                KeyboardSizes.Setting(true, 30, 49), KeyboardSizes.Setting(true, 0, 0), KeyboardSizes.Setting(true, 0, 0), false,
                ThemeSizes(height = Twin(40.0, 60.0)),
            )
            assertEquals(393 * 60 / 100, sizes.heightPx(393, landscape = true))
            assertEquals(275 - 58 - 12, KeyboardHeightCap.keysHeight(sizes.heightPx(393, landscape = true), 393, 58, 12))
            assertEquals(340, KeyboardHeightCap.keysHeight(sizes.heightPx(851, landscape = false), 851, 58, 12))
        },
    )
}
