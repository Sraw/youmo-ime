/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardHeightCapTest {

    @Test
    fun aTallLandscapeLevelIsCutToSeventyPercentOfTheScreen() {
        // 393 dp landscape at 1x: 很高 60% = 236, bars 58, padding 12 -> 306 > 275
        assertEquals(275 - 58 - 12, KeyboardHeightCap.keysHeight(236, 393, 58, 12))
    }

    @Test
    fun aHeightUnderTheCapIsKept() {
        // portrait 851 dp, 40% = 340: 340 + 58 + 12 is well under 595
        assertEquals(340, KeyboardHeightCap.keysHeight(340, 851, 58, 12))
    }

    @Test
    fun aScreenTooShortForTheBarsGivesNoKeysRatherThanANegativeHeight() {
        assertEquals(0, KeyboardHeightCap.keysHeight(100, 50, 58, 12))
    }
}
