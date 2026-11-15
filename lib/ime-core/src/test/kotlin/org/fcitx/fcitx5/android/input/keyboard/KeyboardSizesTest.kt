/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.keyboard.KeyboardSizes.Setting
import org.fcitx.fcitx5.android.scene.theme.Twin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.fcitx.fcitx5.android.scene.theme.KeyboardSizes as ThemeSizes

class KeyboardSizesTest {

    // today's defaults, each following the theme (an upgrade's untouched settings, 0.6.0's own)
    private val following = KeyboardSizes(Setting(true, 30, 49), Setting(true, 0, 0), Setting(true, 0, 0), bottomFlush = false)
    private val themed = ThemeSizes(height = Twin(38.0, 52.0), sidePadding = Twin(8.0, 48.0), bottomPadding = Twin(4.0, 2.5))

    @Test
    fun aThemeWithoutASizeIsTheDefaultLevels() {
        // 851 px high upright, 393 on its side
        assertEquals(851 * 30 / 100, following.heightPx(851, landscape = false, density = 1f, large = false))
        // turned, 标准's four rows of 48 dp, whatever the screen's height (061-landscape-height §5.1)
        assertEquals(4 * 48, following.heightPx(393, landscape = true, density = 1f, large = false))
        assertEquals(0.0, following.sidePaddingDp(false), 0.0)
        assertEquals(0.0, following.bottomPaddingDp(true), 0.0)
        assertFalse(following.compact)
    }

    @Test
    fun theThemeSetsAHeight() {
        val sizes = following.copy(theme = themed)
        assertEquals((851 * 0.38).toInt(), sizes.heightPx(851, landscape = false, density = 1f, large = false))
        // the theme's landscape 52 is a level, between 标准 (49, 48 dp) and 高 (55, 54 dp): 51 dp a row (the user's Q8)
        assertEquals(4 * 51, sizes.heightPx(393, landscape = true, density = 1f, large = false))
        assertEquals(8.0, sizes.sidePaddingDp(false), 0.0)
        assertEquals(48.0, sizes.sidePaddingDp(true), 0.0)
        assertEquals(2.5, sizes.bottomPaddingDp(true), 0.0)
        // the floor still comes last: 204 and bars of 100 leave a 321 dp room's app 17, and the keys give way to 4 × 36
        val requested = sizes.heightPx(393, landscape = true, density = 1f, large = false)
        val keys = KeyboardHeightCap.keysHeight(requested, 321, 100, sizes.bottomPaddingDp(true).toInt())
        assertEquals(4 * KeyboardHeightCap.MIN_ROW_DP, keys)
        assertTrue(keys < requested)
    }

    @Test
    @Uat("ST-10")
    fun turnedTheRowIsTheSameOnEveryScreenOfAKind() {
        // a phone of any height: 360, 393, 448 dp, and in px at its density
        listOf(360, 393, 448).forEach { assertEquals(192, following.heightPx(it, true, 1f, large = false)) }
        assertEquals((192 * 2.75f).toInt(), following.heightPx(1080, true, 2.75f, large = false))
        assertEquals((192 * 2.625f).toInt(), following.heightPx(1080, true, 2.625f, large = false))
        // a foldable's inner screen and a tablet, upright or turned (横屏 both ways): 4 × 56
        listOf(673, 841, 800, 1280).forEach { assertEquals(224, following.heightPx(it, true, 1f, large = true)) }
        // a level picked: 紧凑's 34 and 很高's 59 a row on a phone, 48 and 64 on a large screen
        val compact = KeyboardSizes(Setting(false, 20, 34), Setting(false, 0, 0), Setting(false, 0, 0), false)
        val taller = KeyboardSizes(Setting(false, 38, 60), Setting(false, 0, 0), Setting(false, 0, 0), false)
        assertEquals(136, compact.heightPx(393, true, 1f, false))
        assertEquals(192, compact.heightPx(841, true, 1f, true))
        assertEquals(236, taller.heightPx(393, true, 1f, false))
        assertEquals(256, taller.heightPx(841, true, 1f, true))
        // upright, 竖屏: the height's percent as before
        assertEquals(851 * 30 / 100, following.heightPx(851, landscape = false, density = 1f, large = true))
    }

    @Test
    @Uat("PR-61")
    fun floatingKeepsThePercentOf060() {
        // 061-landscape-height §5.4: floating's base is 0.6.0's, the level's percent of the short side
        assertEquals(393 * 49 / 100, following.percentHeightPx(393, 851, landscape = true))
        assertEquals(673 * 49 / 100, following.percentHeightPx(841, 673, landscape = true))
        assertEquals(851 * 30 / 100, following.percentHeightPx(851, 393, landscape = false))
        assertEquals((393 * 0.52).toInt(), following.copy(theme = themed).percentHeightPx(393, 851, landscape = true))
    }

    @Test
    fun aLevelPickedIsKeptOverTheTheme() {
        val sizes = KeyboardSizes(Setting(false, 20, 34), Setting(false, 24, 120), Setting(false, 0, 0), bottomFlush = true, theme = themed)
        assertEquals(851 * 20 / 100, sizes.heightPx(851, false, 1f, false))
        assertEquals(120.0, sizes.sidePaddingDp(true), 0.0)
        assertEquals(0.0, sizes.bottomPaddingDp(false), 0.0)
        assertTrue(sizes.compact)
        assertTrue(sizes.flush)
        // each setting on its own: the height followed, the paddings picked
        val mixed = sizes.copy(height = Setting(true, 30, 49))
        assertEquals((851 * 0.38).toInt(), mixed.heightPx(851, false, 1f, false))
        assertEquals(24.0, mixed.sidePaddingDp(false), 0.0)
    }

    @Test
    fun compactAndFlushAsTheThemeIs() {
        // a theme's height near 紧凑's (20/34) is 紧凑, rounded as a setting's would be
        assertTrue(following.copy(theme = ThemeSizes(height = Twin(20.4, 33.6))).compact)
        assertFalse(following.copy(theme = ThemeSizes(height = Twin(30.0, 49.0))).compact)
        // 贴底 is no padding of the theme's: a bottom padding followed leaves the navigation bar its room
        val flush = following.copy(bottomFlush = true)
        assertTrue(flush.flush)
        assertFalse(flush.copy(theme = ThemeSizes(bottomPadding = Twin(4.0, 4.0))).flush)
        // a theme of no bottom padding: 贴底 as set
        assertTrue(flush.copy(theme = ThemeSizes(height = Twin(30.0, 49.0))).flush)
    }
}
