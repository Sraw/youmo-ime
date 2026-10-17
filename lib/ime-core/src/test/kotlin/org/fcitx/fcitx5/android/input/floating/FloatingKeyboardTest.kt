/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard.Spot
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingKeyboardTest {

    @Test
    fun itIsAPhonesWidthAndItsKeysAsNarrowedButNotTooShort() {
        assertEquals(350, FloatingKeyboard.widthDp(500))
        assertEquals(FloatingKeyboard.MAX_DP, FloatingKeyboard.widthDp(1280))
        assertEquals(FloatingKeyboard.MIN_DP, FloatingKeyboard.widthDp(360))
        assertEquals(300, FloatingKeyboard.widthDp(300))
        assertEquals(850, FloatingKeyboard.height(1000, 300, 1000))
        assertEquals(900, FloatingKeyboard.height(1000, 450, 500))
        assertEquals(1000, FloatingKeyboard.height(1000, 500, 500))
    }

    @Test
    fun aSpotIsAShareOfTheRoomSoATurnOfTheScreenKeepsItsCorner() {
        assertEquals(150f to 600f, FloatingKeyboard.place(FloatingKeyboard.DEFAULT, 100f, 400f, 400f, 1000f))
        val spot = FloatingKeyboard.spot(300f, 0f, 100f, 400f, 400f, 1000f)
        assertEquals(Spot(1f, 0f), spot)
        // turned: the same corner of a wider, shorter screen
        assertEquals(900f to 0f, FloatingKeyboard.place(spot, 100f, 400f, 1000f, 400f))
        // dragged past an edge: kept within
        assertEquals(Spot(0f, 1f), FloatingKeyboard.spot(-50f, 900f, 100f, 400f, 400f, 1000f))
        // no room to move: as far as there is
        assertEquals(Spot(1f, 1f), FloatingKeyboard.spot(0f, 0f, 400f, 1000f, 400f, 1000f))
        assertEquals(0f to 0f, FloatingKeyboard.place(Spot(0.5f, 0.5f), 400f, 1000f, 400f, 1000f))
    }
}
