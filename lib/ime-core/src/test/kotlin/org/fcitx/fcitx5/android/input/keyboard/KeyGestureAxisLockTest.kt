/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A key swiped both ways (the space's cursor and its shortcut up): one axis a touch, picked as it leaves the slop. */
class KeyGestureAxisLockTest {

    private fun locked(lock: Boolean = true) =
        recognizer(swipeRepeat = true, width = 400, height = 100, thresholdX = 10f, thresholdY = 30f).apply { axisLock = lock }

    @Test
    fun sidewaysFirstThenUpMovesTheCursorOnly() {
        val r = locked()
        r.onDown(200f, 50f)
        assertEquals(2, r.move(222f, 50f).countX)
        val up = r.move(222f, 0f)
        assertEquals(0, up.countY)
        assertEquals(0, r.swipeTotalY)
        assertFalse(r.swipedY)
    }

    @Test
    fun upFirstThenSidewaysIsTheSwipeUpOnly() {
        val r = locked()
        r.onDown(200f, 80f)
        assertEquals(0, r.move(202f, 60f).countX)
        assertEquals(-1, r.move(202f, 45f).countY)
        assertEquals(0, r.move(260f, 45f).countX)
        assertEquals(0, r.swipeTotalX)
        assertTrue(r.swipedY)
    }

    @Test
    fun withinTheSlopNothingIsDecidedNorCounted() {
        val r = locked()
        r.onDown(200f, 50f)
        val small = r.move(205f, 47f)
        assertEquals(0, small.countX)
        assertTrue(small.dispatchMove)
        // counted from where the touch went down once decided
        assertEquals(2, r.move(221f, 50f).countX)
    }

    @Test
    fun unlockedBothAxesCountAsBefore() {
        val r = locked(lock = false)
        r.onDown(200f, 80f)
        val both = r.move(222f, 40f)
        assertEquals(2, both.countX)
        assertEquals(-1, both.countY)
    }

    @Test
    fun aSwipeUpAndBackIsSwipedThoughAtZero() {
        val r = locked(lock = false)
        r.onDown(200f, 80f)
        r.move(200f, 45f)
        r.move(200f, 80f)
        assertEquals(0, r.swipeTotalY)
        assertTrue(r.swipedY)
        r.onUp(0L, longPressTriggered = false, repeatStarted = false)
        r.resetForNextTouch()
        assertFalse(r.swipedY)
    }
}
