/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.scene.theme.Twin
import org.junit.Assert.assertEquals
import org.junit.Test
import org.fcitx.fcitx5.android.scene.theme.KeyboardSizes as ThemeSizes

/** 061-landscape-height §5.2, at 1x unless said: the app keeps 120 dp, the rows 36 dp each, the rows winning. */
class KeyboardHeightCapTest {

    @Test
    fun aHeightUnderTheFloorIsKept() = assertRows(
        "upright: 40% of 851 and the bars leave the app far more than 120" to {
            assertEquals(340, KeyboardHeightCap.keysHeight(340, 851 - 24 - 48, 58, 12))
        },
        "a docked handle (始终显示悬浮条) is added, the keys keep their height" to {
            assertEquals(340, KeyboardHeightCap.keysHeight(340, 851 - 24 - 48, 58, 12, handleHeight = 24))
        },
        "a common phone turned, three buttons: 标准's 4 × 48 leaves the app 137" to {
            assertEquals(192, KeyboardHeightCap.keysHeight(192, 393 - 24, 40, 0))
        },
    )

    @Test
    @Uat("LS-47")
    fun whereTheFloorBindsTheKeysGiveWay() = assertRows(
        "a common phone turned under gestures (LC-24): 321 of room, the keys 161, the app 120" to {
            assertEquals(321 - 40 - 120, KeyboardHeightCap.keysHeight(192, 321, 40, 0))
        },
        "a docked handle, an embedded code row and the bottom padding all count" to {
            assertEquals(360 - 40 - 18 - 24 - 4 - 120, KeyboardHeightCap.keysHeight(192, 360, 40 + 18, 4, handleHeight = 24))
        },
        "upright at a theme's 90%: the app keeps 120 where 0.6.0's 70% kept more" to {
            val available = 851 - 24 - 48
            val keys = KeyboardHeightCap.keysHeight((851 * 0.9).toInt(), available, 58, 12)
            assertEquals(120, available - 58 - 12 - keys)
        },
    )

    @Test
    @Uat("LS-50")
    fun theRowsWinOverTheFloor() = assertRows(
        "a small phone under gestures: 272 of room leaves the keys 112, the rows keep 4 × 36" to {
            assertEquals(144, KeyboardHeightCap.keysHeight(192, 272, 40, 0))
        },
        // R26 F8123: was a request of 0 giving 0, which tested nothing once the rows won
        "a screen too short even for the bars: the rows still keep 4 × 36, no less" to {
            assertEquals(144, KeyboardHeightCap.keysHeight(192, 50, 58, 12))
        },
        "the rows as the layout has them: 注音's five, six" to {
            assertEquals(180, KeyboardHeightCap.keysHeight(236, 272, 40, 0, rows = 5.0))
            assertEquals(216, KeyboardHeightCap.keysHeight(236, 272, 40, 0, rows = 6.0))
        },
        "紧凑's own 4 × 34 is not raised to 36" to {
            assertEquals(136, KeyboardHeightCap.keysHeight(136, 272, 40, 0))
        },
        "nor a level's keys under five rows of 36 (矮, 164)" to {
            assertEquals(164, KeyboardHeightCap.keysHeight(164, 272, 40, 0, rows = 5.0))
        },
        "in px: at 2.75 the app's 120 and a row's 36 are 330 and 99" to {
            val d = 2.75f
            assertEquals(883 - 110 - 330, KeyboardHeightCap.keysHeight(528, 883, 110, 0, density = d))
            assertEquals(396, KeyboardHeightCap.keysHeight(528, 700, 110, 0, density = d))
        },
    )

    @Test
    fun aLayoutsRowsCountedByTheirHeights() = assertRows(
        "four rows alike: four" to { assertEquals(4.0, KeyboardHeightCap.rowUnits(listOf(1.0, 1.0, 1.0, 1.0)), 0.0) },
        "注音's five" to { assertEquals(5.0, KeyboardHeightCap.rowUnits(List(5) { 1.0 }), 0.0) },
        "a row half as tall is half a row" to { assertEquals(2.5, KeyboardHeightCap.rowUnits(listOf(1.0, 1.0, 0.5)), 0.0) },
        // a custom layout's thin row (LayoutReader: 0–4): not nine rows, a floor of 324 dp over any phone's room
        "four rows and a thin one: four and a half" to { assertEquals(4.5, KeyboardHeightCap.rowUnits(listOf(1.0, 1.0, 1.0, 1.0, 0.5)), 0.0) },
        "a row of nothing counts nothing" to { assertEquals(4.0, KeyboardHeightCap.rowUnits(listOf(1.0, 1.0, 1.0, 1.0, 0.0)), 0.0) },
        "the symbols' 1 and 3: the tall one's height" to { assertEquals(4.0 / 3, KeyboardHeightCap.rowUnits(listOf(1.0, 3.0)), 1e-9) },
        "none read: four" to { assertEquals(4.0, KeyboardHeightCap.rowUnits(emptyList()), 0.0) },
    )

    @Test
    fun aThemesLandscapeHeightIsARowOfTheTable() {
        // 060-render-p3 T5 and the user's Q8 (2026-11-14): the theme's height followed is asked for as a level is
        val sizes = KeyboardSizes(
            KeyboardSizes.Setting(true, 30, 49), KeyboardSizes.Setting(true, 0, 0), KeyboardSizes.Setting(true, 0, 0), false,
            ThemeSizes(height = Twin(40.0, 60.0)),
        )
        assertEquals(4 * 59, sizes.heightPx(393, landscape = true, density = 1f, large = false))
        assertEquals(321 - 40 - 120, KeyboardHeightCap.keysHeight(sizes.heightPx(393, true, 1f, false), 321, 40, 0))
        assertEquals(340, KeyboardHeightCap.keysHeight(sizes.heightPx(851, false, 1f, false), 851 - 72, 58, 12))
    }
}
