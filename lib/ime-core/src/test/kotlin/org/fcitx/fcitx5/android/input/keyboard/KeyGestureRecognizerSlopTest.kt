/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A drag past the slop on a key whose swipe repeats (PY-41): what it cancels, and when the long press may fire. */
class KeyGestureRecognizerSlopTest {

    /** A key 100x50, slop 8, swipe thresholds 24, swipe enabled. */
    private fun recognizer(longPress: Boolean = false, repeat: Boolean = false, swipeRepeat: Boolean = false) =
        KeyGestureRecognizer().apply {
            touchSlop = 8f
            viewWidth = 100
            viewHeight = 50
            swipeEnabled = true
            longPressEnabled = longPress
            repeatEnabled = repeat
            swipeRepeatEnabled = swipeRepeat
            swipeThresholdX = 24f
            swipeThresholdY = 24f
        }

    private fun KeyGestureRecognizer.move(x: Float, y: Float) =
        onMove(x, y, longPressTriggered = false, repeatStarted = false)

    // PY-41: a slow drag on the space reached its first step after the long press had taken the touch
    @Test
    fun aSlowDragPastTheSlopOnARepeatingSwipeCancelsTheLongPress() {
        val r = recognizer(longPress = true, swipeRepeat = true)
        r.onDown(50f, 25f)
        val within = r.move(57f, 25f)
        assertFalse(within.cancelLongPress)
        val past = r.move(60f, 25f)
        assertEquals(0, past.countX)
        assertTrue(past.cancelLongPress)
    }

    // holding the backspace: drifting past the slop before the repeat starts keeps the repeat
    @Test
    fun aDriftPastTheSlopKeepsAPendingRepeat() {
        val r = recognizer(longPress = true, repeat = true, swipeRepeat = true)
        r.onDown(50f, 25f)
        val past = r.move(60f, 25f)
        assertTrue(past.cancelLongPress)
        assertFalse(past.cancelRepeat)
        // a real swipe step still cancels it
        assertTrue(r.move(80f, 25f).cancelRepeat)
    }

    // decided at the timer, whether or not a move sample arrived to cancel it first
    @Test
    fun theLongPressTimerDoesNotFireAfterADragPastTheSlop() {
        val r = recognizer(longPress = true, swipeRepeat = true)
        r.onDown(50f, 25f)
        assertTrue(r.longPressMayFire())
        r.move(57f, 25f)
        assertTrue(r.longPressMayFire())
        r.move(60f, 25f)
        assertFalse(r.longPressMayFire())
    }

    @Test
    fun theLongPressTimerFiresOnALetterDraggedPastTheSlop() {
        val r = recognizer(longPress = true)
        r.onDown(50f, 25f)
        r.move(60f, 25f)
        assertTrue(r.longPressMayFire())
    }

    @Test
    fun aDragPastTheSlopOnALetterKeepsItsLongPress() {
        val r = recognizer(longPress = true)
        r.onDown(50f, 25f)
        assertFalse(r.move(60f, 25f).cancelLongPress)
    }
}
