/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.rules.ScreenShape
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 横屏: held sideways, or 600 dp wide either way up (061-wide-as-landscape §2.1)
class ScreenShapeTest {

    @Test
    fun aWideScreenUprightIsLandscape() {
        assertFalse(ScreenShape.landscape(turned = false, widthDp = 599))
        assertTrue(ScreenShape.landscape(turned = false, widthDp = 600))
        // a foldable's inner screen, a tablet upright
        assertTrue(ScreenShape.landscape(turned = false, widthDp = 673))
        assertTrue(ScreenShape.landscape(turned = false, widthDp = 800))
    }

    @Test
    fun aSmallScreenTurnedIsLandscapeAndAnOuterScreenIsNot() {
        // a small phone on its side, narrower than WIDE_DP
        assertTrue(ScreenShape.landscape(turned = true, widthDp = 568))
        // foldables' outer screens and phones upright
        assertFalse(ScreenShape.landscape(turned = false, widthDp = 344))
        assertFalse(ScreenShape.landscape(turned = false, widthDp = 412))
    }

    @Test
    fun autoSplitsFromTheSameWidth() {
        // this copy of the split as ime-core's: one width for 横屏 and AUTO's split
        assertEquals(600, ScreenShape.WIDE_DP)
        assertTrue(SplitKeyboard.splits(Mode.AUTO, widthDp = 673))
        assertTrue(SplitKeyboard.splits(Mode.AUTO, widthDp = 852))
        assertFalse(SplitKeyboard.splits(Mode.AUTO, widthDp = 599))
        assertFalse(SplitKeyboard.splits(Mode.AUTO, widthDp = 568))
        assertTrue(SplitKeyboard.splits(Mode.ALWAYS, widthDp = 360))
        assertFalse(SplitKeyboard.splits(Mode.OFF, widthDp = 1280))
    }
}
