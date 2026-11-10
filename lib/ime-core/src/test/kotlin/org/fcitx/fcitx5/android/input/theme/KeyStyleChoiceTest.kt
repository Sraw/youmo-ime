/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.input.theme.LookFixtures.PALETTE
import org.fcitx.fcitx5.android.scene.look.BarBase
import org.fcitx.fcitx5.android.scene.look.Gap
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class KeyStyleChoiceTest {

    private val look = LookDefaults.look(PALETTE, null).let {
        it.copy(keys = it.keys.copy(blocks = false, radiusDp = 16, gap = Gap(1, 1, 1, 1)), bar = BarBase.Color)
    }

    @Test
    fun allFollowingIsTheSameLook() {
        assertSame(look, KeyStyleChoice.FOLLOW.applyTo(look))
    }

    @Test
    fun eachChoiceChangesItsFieldOnly() {
        assertEquals(look.copy(keys = look.keys.copy(radiusDp = 0)), KeyStyleChoice(null, null, null, 0).applyTo(look))
        assertEquals(look.copy(keys = look.keys.copy(blocks = true)), KeyStyleChoice(true, null, null, null).applyTo(look))
    }

    @Test
    fun eachDirectionOfTheGapIsOverItsHalfOnly() {
        // across chosen, up and down the theme's; the other way round; both
        val theme = Gap(1, 2, 3, 4)
        val keys = look.keys.copy(gap = theme)
        val l = look.copy(keys = keys)
        assertEquals(Gap(5, 2, 5, 4), KeyStyleChoice(null, KeyStyleChoice.H_LOOSE, null, null).applyTo(l).keys.gap)
        assertEquals(Gap(1, 9, 3, 6), KeyStyleChoice(null, null, KeyStyleChoice.V_LOOSE, null).applyTo(l).keys.gap)
        assertEquals(Gap(2, 7, 2, 4), KeyStyleChoice(null, KeyStyleChoice.H_COMPACT, KeyStyleChoice.V_STANDARD, null).applyTo(l).keys.gap)
        // the rest of the keys as they were
        assertEquals(keys.copy(gap = Gap(5, 2, 5, 4)), KeyStyleChoice(null, KeyStyleChoice.H_LOOSE, null, null).applyTo(l).keys)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSpacingOutOfTheThemesRangeIsRefused() {
        KeyStyleChoice.Spacing(Gap.MAX_GAP_DP + 1, 0)
    }

    @Test
    fun blockKeysMadeFlatGiveATransparentBarTheBarColour() {
        val blocks = look.copy(keys = look.keys.copy(blocks = true), bar = BarBase.Transparent)
        assertEquals(BarBase.Color, KeyStyleChoice(false, null, null, null).applyTo(blocks).bar)
        // blocks kept, or following the theme, leave the theme's bar
        assertEquals(BarBase.Transparent, KeyStyleChoice(true, null, null, null).applyTo(blocks).bar)
        assertEquals(BarBase.Transparent, KeyStyleChoice(null, null, null, 0).applyTo(blocks).bar)
    }

    @Test
    fun aFlatThemeMadeFlatKeepsItsBar() {
        // as following it: the setting the same as the theme draws the same
        val flat = look.copy(bar = BarBase.Transparent)
        assertEquals(BarBase.Transparent, KeyStyleChoice(false, null, null, null).applyTo(flat).bar)
    }

    @Test
    fun theLevelsAreInTheThemesRanges() {
        // made into a theme's keys: out of range would throw
        val hs = listOf(KeyStyleChoice.H_COMPACT, KeyStyleChoice.H_STANDARD, KeyStyleChoice.H_LOOSE)
        val vs = listOf(KeyStyleChoice.V_COMPACT, KeyStyleChoice.V_STANDARD, KeyStyleChoice.V_LOOSE)
        for (h in hs) for (v in vs) {
            listOf(KeyStyleChoice.RADIUS_SQUARE, KeyStyleChoice.RADIUS_ROUNDED, KeyStyleChoice.RADIUS_ROUND).forEach { r ->
                KeyStyleChoice(true, h, v, r).applyTo(look).let {
                    assertEquals(Gap(h.portrait, v.portrait, h.landscape, v.landscape), it.keys.gap)
                    assertEquals(r, it.keys.radiusDp)
                }
            }
        }
    }
}
