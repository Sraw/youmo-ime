/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

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
        assertEquals(851 * 30 / 100, following.heightPx(851, landscape = false))
        assertEquals(393 * 49 / 100, following.heightPx(393, landscape = true))
        assertEquals(0.0, following.sidePaddingDp(false), 0.0)
        assertEquals(0.0, following.bottomPaddingDp(true), 0.0)
        assertFalse(following.compact)
    }

    @Test
    fun theThemeSetsAHeight() {
        val sizes = following.copy(theme = themed)
        assertEquals((851 * 0.38).toInt(), sizes.heightPx(851, landscape = false))
        assertEquals((393 * 0.52).toInt(), sizes.heightPx(393, landscape = true))
        assertEquals(8.0, sizes.sidePaddingDp(false), 0.0)
        assertEquals(48.0, sizes.sidePaddingDp(true), 0.0)
        assertEquals(2.5, sizes.bottomPaddingDp(true), 0.0)
        // the cap still comes last: 52% of a short landscape screen (204) and bars of 100 pass 70% (275), and the keys give way
        val requested = sizes.heightPx(393, landscape = true)
        // a screen of density 1, where the app's ctx.dp(bottom padding) is its dp
        val bottomPx = sizes.bottomPaddingDp(true).toInt()
        val keys = KeyboardHeightCap.keysHeight(requested, 393, 100, bottomPx)
        assertEquals((393 * KeyboardHeightCap.MAX_FRACTION).toInt() - 100 - 2, keys)
        assertTrue(keys < requested)
    }

    @Test
    fun aLevelPickedIsKeptOverTheTheme() {
        val sizes = KeyboardSizes(Setting(false, 20, 34), Setting(false, 24, 120), Setting(false, 0, 0), bottomFlush = true, theme = themed)
        assertEquals(851 * 20 / 100, sizes.heightPx(851, false))
        assertEquals(120.0, sizes.sidePaddingDp(true), 0.0)
        assertEquals(0.0, sizes.bottomPaddingDp(false), 0.0)
        assertTrue(sizes.compact)
        assertTrue(sizes.flush)
        // each setting on its own: the height followed, the paddings picked
        val mixed = sizes.copy(height = Setting(true, 30, 49))
        assertEquals((851 * 0.38).toInt(), mixed.heightPx(851, false))
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
