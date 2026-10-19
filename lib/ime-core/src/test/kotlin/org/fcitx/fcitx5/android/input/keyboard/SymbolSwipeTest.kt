/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.SymbolSwipe.Direction.Down
import org.fcitx.fcitx5.android.input.keyboard.SymbolSwipe.Direction.Off
import org.fcitx.fcitx5.android.input.keyboard.SymbolSwipe.Direction.Up
import org.fcitx.fcitx5.android.input.keyboard.SymbolSwipe.Outcome
import org.junit.Test
import org.junit.Assert.assertEquals

class SymbolSwipeTest {

    @Test
    fun theWayPickedTypesTheSymbol() {
        assertEquals(Outcome.Symbol, SymbolSwipe.onUp(direction = Down, totalY = 2, consumed = false, cancelled = false))
        assertEquals(Outcome.Symbol, SymbolSwipe.onUp(direction = Up, totalY = -2, consumed = false, cancelled = false))
    }

    // ST-15: with 上划, a downward swipe off the key typed nothing
    @Test
    fun theOtherWayTypesTheLetter() {
        assertEquals(Outcome.Tap, SymbolSwipe.onUp(direction = Up, totalY = 2, consumed = false, cancelled = false))
        assertEquals(Outcome.Tap, SymbolSwipe.onUp(direction = Down, totalY = -1, consumed = false, cancelled = false))
        // swiping off: sliding off the key takes the press back, as before ST-15
        assertEquals("swiping off", Outcome.None, SymbolSwipe.onUp(direction = Off, totalY = 3, consumed = false, cancelled = false))
    }

    @Test
    fun noSwipeOrATakenTouchLeavesTheKeyAlone() {
        assertEquals(Outcome.None, SymbolSwipe.onUp(direction = Down, totalY = 0, consumed = false, cancelled = false))
        assertEquals("a long press's", Outcome.None, SymbolSwipe.onUp(direction = Down, totalY = 2, consumed = true, cancelled = false))
        assertEquals(Outcome.None, SymbolSwipe.onUp(direction = Up, totalY = 2, consumed = false, cancelled = true))
    }

    // the preview while moving and the lift agree: one rule
    @Test
    fun thePreviewFollowsTheLift() {
        assertEquals(true, SymbolSwipe.typesSymbol(Down, 1))
        assertEquals(false, SymbolSwipe.typesSymbol(Down, -1))
        assertEquals(false, SymbolSwipe.typesSymbol(Up, 0))
        assertEquals(false, SymbolSwipe.typesSymbol(Off, -1))
    }
}
