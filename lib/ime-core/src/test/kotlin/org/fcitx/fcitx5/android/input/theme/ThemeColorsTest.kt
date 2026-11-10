/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.input.theme.LookFixtures.PALETTE
import org.fcitx.fcitx5.android.scene.look.BarBase
import org.fcitx.fcitx5.android.scene.look.KeyKind
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.fcitx.fcitx5.android.scene.look.Picture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeColorsTest {

    private val blocks = LookDefaults.KEYS
    private val flat = blocks.copy(blocks = false)

    @Test
    fun theBoardIsTheBackgroundUnderBlocksAndTheKeyboardsColourFlat() {
        assertEquals(PALETTE.backgroundColor, ThemeColors.board(PALETTE, blocks))
        assertEquals(PALETTE.keyboardColor, ThemeColors.board(PALETTE, flat))
    }

    @Test
    fun theBarIsItsColourOnlyWhenItIsOne() {
        assertEquals(PALETTE.barColor, ThemeColors.bar(PALETTE, BarBase.Color))
        assertNull(ThemeColors.bar(PALETTE, BarBase.Transparent))
        assertNull(ThemeColors.bar(PALETTE, BarBase.Picture(0, 100)))
    }

    @Test
    fun eachKindHasItsColourTheSpaceTheBarsFlat() {
        assertEquals(PALETTE.keyBackgroundColor, ThemeColors.face(PALETTE, KeyKind.Letter, true))
        assertEquals(PALETTE.altKeyBackgroundColor, ThemeColors.face(PALETTE, KeyKind.Function, true))
        assertEquals(PALETTE.accentKeyBackgroundColor, ThemeColors.face(PALETTE, KeyKind.Enter, false))
        assertEquals(PALETTE.keyBackgroundColor, ThemeColors.face(PALETTE, KeyKind.Space, true))
        assertEquals(PALETTE.spaceBarColor, ThemeColors.face(PALETTE, KeyKind.Space, false))
    }

    @Test
    fun brightnessIsTheBlackLeftOff() {
        assertEquals(0, ThemeColors.dim(100))
        assertEquals(255, ThemeColors.dim(0))
        assertEquals(76, ThemeColors.dim(70))
    }

    @Test
    fun panelsShowTheBarColourOnlyOverFlatKeys() {
        assertEquals(null, ThemeColors.panel(PALETTE, blocks))
        assertEquals(PALETTE.barColor, ThemeColors.panel(PALETTE, blocks.copy(blocks = false)))
    }
}
