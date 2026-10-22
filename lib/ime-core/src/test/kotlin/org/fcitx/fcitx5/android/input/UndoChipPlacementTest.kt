/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Test
import org.junit.Assert.assertEquals

class UndoChipPlacementTest {

    @Test
    fun withNoCandidatesItSitsOverTheBar() {
        assertEquals(1310, UndoChipPlacement.top(candidates = false, barTop = 1300, aboveTop = 1200, chipHeight = 110, gap = 10))
    }

    @Test
    fun withCandidatesItGoesAboveTheKeyboard() {
        // the chip ends a gap above the keyboard's top: over none of its rows
        assertEquals(1080, UndoChipPlacement.top(candidates = true, barTop = 1300, aboveTop = 1200, chipHeight = 110, gap = 10))
    }

    @Test
    fun withNoRoomAboveItGoesBelowWhenThereIsABelow() {
        assertEquals(410, UndoChipPlacement.top(candidates = true, barTop = 50, aboveTop = 50, chipHeight = 110, gap = 10, belowBottom = 400))
    }

    @Test
    fun withNoRoomAboveAndNoBelowItSitsOverTheBar() {
        assertEquals(60, UndoChipPlacement.top(candidates = true, barTop = 50, aboveTop = 50, chipHeight = 110, gap = 10))
    }

    @Test
    fun exactlyEnoughRoomIsRoom() {
        assertEquals(0, UndoChipPlacement.top(candidates = true, barTop = 120, aboveTop = 120, chipHeight = 110, gap = 10))
    }

    @Test
    fun belowThatFitsTheWindowIsUsed() {
        // 400 + 10 + 110 = 520: exactly the window's height still fits
        assertEquals(410, UndoChipPlacement.top(candidates = true, barTop = 50, aboveTop = 50, chipHeight = 110, gap = 10, belowBottom = 400, bottomLimit = 520))
    }

    @Test
    fun withNoRoomAboveOrBelowItSitsOverTheBar() {
        // a floating keyboard as tall as the screen: below would leave the window
        assertEquals(60, UndoChipPlacement.top(candidates = true, barTop = 50, aboveTop = 50, chipHeight = 110, gap = 10, belowBottom = 400, bottomLimit = 519))
    }
}
