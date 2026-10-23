/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Test

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
    )
}
