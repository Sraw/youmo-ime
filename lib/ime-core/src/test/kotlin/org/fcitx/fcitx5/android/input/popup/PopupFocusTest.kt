/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.popup.PopupFocus.Companion.CANCELLED
import org.junit.Assert.assertEquals
import org.junit.Test

class PopupFocusTest {

    // 九键's 2: A B C 2 a b c, the digit first in focus; @ ! " above, the @ over the digit
    private fun nine() = PopupFocus(
        rows = listOf(intArrayOf(0, 1, 2, 3, 4, 5, 6), intArrayOf(-1, -1, 8, 7, 9, -1, -1)),
        startRow = 0, startColumn = 3, stepX = 20f, stepY = 25f, cancelBelow = 50f,
    )

    @Test
    fun aStepMovesOneKeyNotAKeysWidth() {
        val f = nine()
        assertEquals(3, f.focused)
        assertEquals(3, f.move(5f, 0f))
        assertEquals(4, f.move(20f, 0f))
        assertEquals(6, f.move(60f, 0f))
        assertEquals(2, f.move(-20f, 0f))
        assertEquals(0, f.move(-60f, 0f))
    }

    @Test
    fun pastARowsEndTheFingerStaysOnItsLastKey() {
        val f = nine()
        assertEquals(6, f.move(500f, 0f))
        assertEquals(0, f.move(-500f, 0f))
        // above the top row, the top row
        assertEquals(7, f.move(0f, -500f))
    }

    @Test
    fun anEmptySlotPicksTheNearestKeyInItsRow() {
        val f = nine()
        // up and two right: over an empty slot, " is the nearest
        assertEquals(9, f.move(40f, -25f))
        assertEquals(9, f.move(200f, -25f))
        assertEquals(8, f.move(-60f, -25f))
        assertEquals(7, f.move(0f, -25f))
    }

    @Test
    fun onlyFarDownCancels() {
        val f = nine()
        assertEquals(3, f.move(0f, 40f))
        assertEquals(CANCELLED, f.move(0f, 51f))
        // back up, a key again
        assertEquals(3, f.move(0f, 0f))
        assertEquals(6, f.move(900f, -10f))
    }

    @Test
    fun aFingerOnTheLineBetweenTwoKeysStaysOnOne() {
        val f = nine()
        // just past the middle: still the digit
        assertEquals(3, f.move(12f, 0f))
        assertEquals(4, f.move(14f, 0f))
        // back just under the middle: still a
        assertEquals(4, f.move(8f, 0f))
        assertEquals(3, f.move(6f, 0f))
    }

    @Test
    fun aRowOfOneKeyAlwaysPicksIt() {
        val f = PopupFocus(listOf(intArrayOf(0)), 0, 0, 20f, 25f, 50f)
        assertEquals(0, f.move(-300f, -300f))
        assertEquals(0, f.move(300f, 0f))
    }

    @Test
    fun goingOnDownPastTheKeyCancels() = assertRows(
        "move to counts from the first point and cancels below the keys bottom" to {
            // pressed 10 above the key's bottom edge at y = 100: 11 down is off the key
            val f = PopupFocus(listOf(intArrayOf(0, 1, 2)), 0, 1, 20f, 25f, cancelBelow = 50f, cancelAt = 100f)
            assertEquals(1, f.moveTo(40f, 90f))
            assertEquals(2, f.moveTo(60f, 99f))
            assertEquals(PopupFocus.CANCELLED, f.moveTo(40f, 101f))
        },
        "a finger already below the key when it opens cancels only going on down" to {
            // the finger drifted 3 past the key's bottom edge at y = 100 while the long press waited
            val f = PopupFocus(listOf(intArrayOf(0, 1, 2)), 0, 1, 20f, 25f, cancelBelow = 50f, cancelAt = 100f)
            assertEquals(1, f.moveTo(40f, 103f))
            // a shake down while sliding across (under 0.15 of a key's 25) is not going on down
            assertEquals(2, f.moveTo(60f, 106f))
            assertEquals(PopupFocus.CANCELLED, f.moveTo(40f, 107f))
        },
        "a finger just inside the edge gets the same shake room" to {
            // pressed 1 above the edge at y = 100: the floor (0.15 of the row step 25), not the 1 left, decides
            val f = PopupFocus(listOf(intArrayOf(0, 1, 2)), 0, 1, 20f, 25f, cancelBelow = 50f, cancelAt = 100f)
            assertEquals(1, f.moveTo(40f, 99f))
            assertEquals(2, f.moveTo(60f, 102f))
            assertEquals(PopupFocus.CANCELLED, f.moveTo(40f, 104f))
        },
    )
}
