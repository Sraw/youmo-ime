/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The space's cursor up and down (060-space-2d): vertical steps only after a sideways one. */
class KeyGestureCrossYTest {

    private fun recognizer(cross: Float = 28f) = KeyGestureRecognizer().apply {
        touchSlop = 8f
        viewWidth = 400
        viewHeight = 100
        swipeEnabled = true
        swipeRepeatEnabled = true
        axisLock = true
        swipeThresholdX = 10f
        swipeThresholdY = 30f
        crossStepY = cross
    }

    private fun KeyGestureRecognizer.move(x: Float, y: Float) =
        onMove(x, y, longPressTriggered = false, repeatStarted = false)

    @Test
    fun afterASidewaysStepUpAndDownMoveLines() {
        val r = recognizer()
        r.onDown(200f, 50f)
        assertEquals(2, r.move(222f, 52f).countX)
        // counted from where the finger was at the first sideways step
        assertEquals(0, r.move(222f, 30f).countCrossY)
        assertEquals(-1, r.move(222f, 20f).countCrossY)
        assertEquals(-2, r.move(222f, -40f).countCrossY)
        assertEquals(3, r.move(222f, 70f).countCrossY)
        assertEquals("the swipe up is not counted", 0, r.swipeTotalY)
        assertFalse(r.swipedY)
        // sideways goes on as before
        assertEquals(-1, r.move(205f, 70f).countX)
    }

    @Test
    fun aDiagonalSampleCarriesBoth() {
        val r = recognizer()
        r.onDown(200f, 50f)
        assertEquals(1, r.move(212f, 50f).countX)
        val both = r.move(242f, 20f)
        assertEquals(3, both.countX)
        assertEquals(-1, both.countCrossY)
    }

    @Test
    fun sidewaysStepsAreTheSameWithLinesOnOrOff() {
        val path = listOf(213f to 52f, 236f to 30f, 251f to 5f, 230f to 40f, 190f to 90f, 160f to 60f, 171f to -20f)
        fun xs(cross: Float) = recognizer(cross).run {
            onDown(200f, 50f)
            path.map { (x, y) -> move(x, y).countX }
        }
        assertEquals(xs(0f), xs(28f))
    }

    @Test
    fun upFirstIsTheSwipeUpAndNoLine() {
        val r = recognizer()
        r.onDown(200f, 80f)
        assertEquals(-1, r.move(202f, 45f).countY)
        assertEquals(0, r.move(260f, 0f).countCrossY)
        assertTrue(r.swipedY)
    }

    @Test
    fun aSmallDriftWhileSlidingSidewaysMovesNoLine() {
        val r = recognizer()
        r.onDown(200f, 50f)
        r.move(215f, 50f)
        var lines = 0
        for (i in 1..20) lines += r.move(215f + i * 10, 50f + (i % 3) * 8).countCrossY
        assertEquals(0, lines)
    }

    @Test
    fun offOrANewTouchCountsNone() {
        val off = recognizer(cross = 0f)
        off.onDown(200f, 50f)
        off.move(222f, 50f)
        assertEquals(0, off.move(222f, -50f).countCrossY)

        val r = recognizer()
        r.onDown(200f, 50f)
        r.move(222f, 50f)
        r.onUp(0, longPressTriggered = false, repeatStarted = false)
        r.resetForNextTouch()
        r.onDown(200f, 50f)
        assertEquals("a new touch going up is its swipe up", -1, r.move(200f, 15f).countY)
        assertEquals(0, r.move(200f, -50f).countCrossY)
    }
}
