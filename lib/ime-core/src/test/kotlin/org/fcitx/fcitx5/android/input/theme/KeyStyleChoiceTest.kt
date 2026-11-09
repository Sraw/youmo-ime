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
        assertEquals(look.copy(keys = look.keys.copy(radiusDp = 0)), KeyStyleChoice(null, null, 0).applyTo(look))
        assertEquals(look.copy(keys = look.keys.copy(blocks = true)), KeyStyleChoice(true, null, null).applyTo(look))
        val gap = KeyStyleChoice.GAP_LOOSE
        assertEquals(look.copy(keys = look.keys.copy(gap = gap)), KeyStyleChoice(null, gap, null).applyTo(look))
    }

    @Test
    fun blockKeysMadeFlatGiveATransparentBarTheBarColour() {
        val blocks = look.copy(keys = look.keys.copy(blocks = true), bar = BarBase.Transparent)
        assertEquals(BarBase.Color, KeyStyleChoice(false, null, null).applyTo(blocks).bar)
        // blocks kept, or following the theme, leave the theme's bar
        assertEquals(BarBase.Transparent, KeyStyleChoice(true, null, null).applyTo(blocks).bar)
        assertEquals(BarBase.Transparent, KeyStyleChoice(null, null, 0).applyTo(blocks).bar)
    }

    @Test
    fun aFlatThemeMadeFlatKeepsItsBar() {
        // as following it: the setting the same as the theme draws the same
        val flat = look.copy(bar = BarBase.Transparent)
        assertEquals(BarBase.Transparent, KeyStyleChoice(false, null, null).applyTo(flat).bar)
    }

    @Test
    fun theLevelsAreInTheThemesRanges() {
        // made into a theme's keys: out of range would throw
        listOf(KeyStyleChoice.GAP_COMPACT, KeyStyleChoice.GAP_STANDARD, KeyStyleChoice.GAP_LOOSE).forEach { gap ->
            listOf(KeyStyleChoice.RADIUS_SQUARE, KeyStyleChoice.RADIUS_ROUNDED, KeyStyleChoice.RADIUS_ROUND).forEach { r ->
                KeyStyleChoice(true, gap, r).applyTo(look).let {
                    assertEquals(gap, it.keys.gap)
                    assertEquals(r, it.keys.radiusDp)
                }
            }
        }
    }
}
