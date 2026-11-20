/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.rules.ScreenShape
import org.fcitx.fcitx5.android.scene.skin.assertRows
import kotlin.test.Test
import kotlin.test.assertEquals

// 横屏: held sideways, or 600 dp wide either way up
class ScreenShapeTest {

    // a device each: turned, its width, dp; whether it is 横屏
    @Test
    fun aScreenIsLandscapeTurnedOrSixHundredDpWide() {
        val rows = listOf(
            Triple("upright, 1 dp short of WIDE_DP", false, 599) to false,
            Triple("upright, WIDE_DP itself", false, 600) to true,
            Triple("a foldable's inner screen", false, 673) to true,
            Triple("a tablet upright", false, 800) to true,
            Triple("a small phone on its side, narrower than WIDE_DP", true, 568) to true,
            Triple("a phone on its side, 1 dp short of WIDE_DP", true, 599) to true,
            Triple("a tablet on its side, wide", true, 1280) to true,
            Triple("a foldable's outer screen", false, 344) to false,
            Triple("a phone upright", false, 412) to false,
        )
        assertRows(rows, { it.first.first }) { (device, landscape) ->
            val (why, turned, widthDp) = device
            assertEquals(landscape, ScreenShape.landscape(turned = turned, widthDp = widthDp), why)
        }
    }
}
