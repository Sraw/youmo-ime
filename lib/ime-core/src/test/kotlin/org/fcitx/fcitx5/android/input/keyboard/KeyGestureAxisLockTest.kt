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

    private fun recognizer(lock: Boolean = true) = KeyGestureRecognizer().apply {
        touchSlop = 8f
        viewWidth = 400
        viewHeight = 100
        swipeEnabled = true
        swipeRepeatEnabled = true
        axisLock = lock
        swipeThresholdX = 10f
        swipeThresholdY = 30f
    }

    private fun KeyGestureRecognizer.move(x: Float, y: Float) =
        onMove(x, y, longPressTriggered = false, repeatStarted = false)

    @Test
    fun sidewaysFirstThenUpMovesTheCursorOnly() {
        val r = recognizer()
        r.onDown(200f, 50f)
        assertEquals(2, r.move(222f, 50f).countX)
        val up = r.move(222f, 0f)
        assertEquals(0, up.countY)
        assertEquals(0, r.swipeTotalY)
        assertFalse(r.swipedY)
    }

    @Test
    fun upFirstThenSidewaysIsTheSwipeUpOnly() {
        val r = recognizer()
        r.onDown(200f, 80f)
        assertEquals(0, r.move(202f, 60f).countX)
        assertEquals(-1, r.move(202f, 45f).countY)
        assertEquals(0, r.move(260f, 45f).countX)
        assertEquals(0, r.swipeTotalX)
        assertTrue(r.swipedY)
    }

    @Test
    fun withinTheSlopNothingIsDecidedNorCounted() {
        val r = recognizer()
        r.onDown(200f, 50f)
        val small = r.move(205f, 47f)
        assertEquals(0, small.countX)
        assertTrue(small.dispatchMove)
        // counted from where the touch went down once decided
        assertEquals(2, r.move(221f, 50f).countX)
    }

    @Test
    fun unlockedBothAxesCountAsBefore() {
        val r = recognizer(lock = false)
        r.onDown(200f, 80f)
        val both = r.move(222f, 40f)
        assertEquals(2, both.countX)
        assertEquals(-1, both.countY)
    }

    @Test
    fun aSwipeUpAndBackIsSwipedThoughAtZero() {
        val r = recognizer(lock = false)
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
