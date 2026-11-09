/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.look.ThemeLookTest.Companion.PALETTE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyLookTest {

    @Test
    fun theDefaultSettingsAreTheDefaultLook() {
        assertTrue(LegacyLook().isDefault)
        assertEquals(LookDefaults.KEYS, LegacyLook().keys())
        // the radius levels: square, rounded (the default), round
        assertFalse(LegacyLook(keyRadius = 0).isDefault)
        assertEquals(0, LegacyLook(keyRadius = 0).keys().radiusDp)
        assertEquals(10, LegacyLook(keyRadius = 10).keys().radiusDp)
    }

    @Test
    fun eachSettingGoesToItsField() {
        assertFalse(LegacyLook(keyBorder = false).keys().blocks)
        // a stroke was 1 dp, with no shadow
        LegacyLook(keyBorderStroke = true).keys().let {
            assertEquals(1, it.borderDp)
            assertEquals(0, it.shadowDp)
        }
        assertEquals(PressEffect.Ripple, LegacyLook(keyRippleEffect = true).keys().press)
        // the spacing levels: compact, loose
        assertEquals(Gap(2, 5, 2, 3), LegacyLook(hMargin = 2, hMarginLandscape = 2, vMargin = 5, vMarginLandscape = 3).keys().gap)
        assertEquals(Gap(5, 9, 5, 6), LegacyLook(hMargin = 5, hMarginLandscape = 5, vMargin = 9, vMarginLandscape = 6).keys().gap)
        // a hidden radius set past the theme's range is kept in it
        assertEquals(24, LegacyLook(keyRadius = 48).keys().radiusDp)
        assertEquals(0, LegacyLook(hMargin = -3).keys().gap.h)
    }

    @Test
    fun appliedToALookItKeepsItsOwnFacesAndAFlatOneHasTheBarColour() {
        val look = LookDefaults.look(PALETTE).let {
            it.copy(keys = it.keys.copy(enter = KindStyle(KeyFill.Color, KeyPressed.Color(5))))
        }
        val flat = LegacyLook(keyBorder = false, keyRadius = 0).applyTo(look)
        assertEquals(BarBase.Color, flat.bar)
        assertFalse(flat.keys.blocks)
        assertEquals(0, flat.keys.radiusDp)
        assertEquals(KeyPressed.Color(5), flat.keys.enter.pressed)
        assertEquals(BarBase.Transparent, LegacyLook(keyRadius = 0).applyTo(look).bar)
        assertNull(flat.picture)
    }
}
