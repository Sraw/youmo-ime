/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.input.theme.ThemeLookTest.Companion.PALETTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeEditLevelsTest {

    private val look = LookDefaults.look(PALETTE)
    private val withPicture = ThemeLookTest.FULL

    @Test
    fun theDefaultsAreTheMiddleSteps() {
        assertEquals(1, ThemeEditLevels.of(ThemeEditLevels.RADIUS, look.keys.radiusDp))
        assertEquals(1, ThemeEditLevels.of(ThemeEditLevels.GAP, look.keys.gap))
        assertEquals(0, ThemeEditLevels.of(ThemeEditLevels.BORDER, look.keys.borderDp))
    }

    @Test
    fun aValueOfNoStepIsCustom() {
        assertNull(ThemeEditLevels.of(ThemeEditLevels.RADIUS, 7))
        assertNull(ThemeEditLevels.of(ThemeEditLevels.GAP, Gap(3, 7, 3, 5)))
    }

    @Test
    fun theStepsAreTheOldSettings() {
        // the old 按键间距 and 按键圆角 levels, as LegacyLook reads what they wrote
        for ((i, gap) in ThemeEditLevels.GAP.withIndex()) {
            val l = LegacyLook(hMargin = gap.h, hMarginLandscape = gap.hLand, vMargin = gap.v, vMarginLandscape = gap.vLand)
            assertEquals(ThemeEditLevels.gap(look, i).keys, l.applyTo(look).keys)
        }
        assertEquals(LegacyLook(keyRadius = 10).applyTo(look).keys, ThemeEditLevels.radius(look, 2).keys)
    }

    @Test
    fun flatKeysPutTheBarInItsColourAsTheOldSwitchDid() {
        val flat = ThemeEditLevels.blocks(look, false)
        assertEquals(LegacyLook(keyBorder = false).applyTo(look), flat)
        assertEquals(look, ThemeEditLevels.blocks(flat, true))
        // a bar of the picture stays
        val pictured = withPicture.copy(bar = BarBase.Picture(4, 80))
        assertEquals(pictured.bar, ThemeEditLevels.blocks(pictured, false).bar)
    }

    @Test
    fun eachChoiceSetsItsOwnPart() {
        assertEquals(2, ThemeEditLevels.border(look, 2).keys.borderDp)
        assertEquals(PressEffect.Ripple, ThemeEditLevels.press(look, PressEffect.Ripple).keys.press)
        assertEquals(look.keys.copy(radiusDp = 0), ThemeEditLevels.radius(look, 0).keys)
    }
}
