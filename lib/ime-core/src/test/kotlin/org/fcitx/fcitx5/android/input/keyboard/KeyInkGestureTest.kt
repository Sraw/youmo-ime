/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.GlideTouch.Route
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyInkGestureTest {

    private val ink = KeyInkGesture().apply { pauseMillis = 500 }
    private val t = GlideTouch()

    // keys 100 by 140, the long press at 300 ms
    private fun down(time: Long, x: Float = 50f, y: Float = 70f, preedit: String = "", letter: Char? = 'a', pointer: Int = 0) =
        t.down(pointer, x, y, time, onLetter = letter != null, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300, inGap = letter == null, ink = ink, preedit = preedit, letter = letter)

    // a stroke of 0.3 key down, from [time]
    private fun stroke(time: Long, preedit: String = ""): Route {
        down(time, preedit = preedit)
        assertEquals(Route.KEYS, t.move(50f, 80f, time + 10))
        assertEquals(Route.CANCEL_KEYS, t.move(50f, 115f, time + 20))
        return t.up(50f, 120f, cancelled = false, time = time + 30)
    }

    @Test
    fun aMoveOfAThirdOfAKeyAnyWayIsAStroke() {
        down(0)
        assertEquals(Route.KEYS, t.move(70f, 70f, 10))
        // across: 0.3 of the width
        assertEquals(Route.CANCEL_KEYS, t.move(82f, 70f, 20))
        assertTrue(t.gliding)
        assertEquals(Route.GLIDE, t.up(80f, 70f, cancelled = false, time = 30))
        assertTrue(ink.writing(100))
    }

    @Test
    fun theWritingGoesOnTillThePauseEveryTouchAStrokeFromItsDownADotToo() {
        assertEquals(Route.GLIDE, stroke(0))
        // within the pause: a dot is a stroke, gliding from its down, no key pressed
        down(400)
        assertTrue(t.gliding)
        assertEquals(Route.GLIDE, t.up(51f, 70f, cancelled = false, time = 420))
        // the pause counts from the last lift
        assertTrue(ink.writing(900))
        assertFalse(ink.writing(920))
        down(920)
        assertFalse(t.gliding)
        assertEquals(Route.KEYS, t.up(50f, 70f, cancelled = false, time = 950))
    }

    @Test
    fun inTheWritingNoLongPressAndASecondFingerIsIgnored() {
        stroke(0)
        down(100)
        // past the long press: still the stroke's
        assertEquals(Route.GLIDE, t.move(50f, 71f, 600))
        assertEquals(Route.GLIDE, t.otherDown())
        assertEquals(Route.GLIDE, t.pointerUp(1, 200f, 70f, time = 610))
        assertEquals(Route.GLIDE, t.up(50f, 71f, cancelled = false, time = 620))
    }

    @Test
    fun outsideTheWritingTheLongPressIsTheKeys() {
        down(0)
        assertEquals(Route.KEYS, t.move(50f, 72f, 400))
        // moved after it came up: the popup's, never a stroke
        assertEquals(Route.KEYS, t.move(50f, 140f, 420))
        assertFalse(ink.writing(430))
    }

    @Test
    fun aStrokeMayStartInASplitKeyboardsGap() {
        down(0, letter = null)
        assertEquals(Route.CANCEL_KEYS, t.move(50f, 115f, 20))
    }

    @Test
    fun nothingIsWrittenWhileSomethingIsTyped() {
        down(0, preedit = "ni")
        assertEquals(Route.KEYS, t.move(50f, 140f, 20))
        assertFalse(ink.writing(30))
    }

    @Test
    fun aTapThatTypedAllTheInputIsTakenBackAsTheFirstDot() {
        down(0, letter = 'd')
        assertEquals(Route.KEYS, t.up(52f, 72f, cancelled = false, time = 40))
        // within the pause, the input that letter: a stroke may start, and takes the tap back
        assertEquals(Route.GLIDE, stroke(300, preedit = "d"))
        assertArrayEquals(floatArrayOf(50f, 70f, 52f, 72f), ink.takeTapped(), 0f)
        // given once
        assertNull(ink.takeTapped())
    }

    @Test
    fun aTapIsNotTakenBackLateOrWhereTheInputIsMore() {
        down(0, letter = 'd')
        t.up(50f, 70f, cancelled = false, time = 40)
        // past the pause
        down(600, preedit = "d")
        assertEquals(Route.KEYS, t.move(50f, 140f, 620))
        t.up(50f, 140f, cancelled = false, time = 630)
        // the input more than the letter
        down(700, letter = 'a')
        t.up(50f, 70f, cancelled = false, time = 720)
        down(800, preedit = "da")
        assertEquals(Route.KEYS, t.move(50f, 140f, 820))
        assertNull(ink.takeTapped())
    }

    @Test
    fun aStrokeWithNothingTypedTakesNoTapBack() {
        down(0, letter = 'd')
        t.up(50f, 70f, cancelled = false, time = 40)
        // the letter went in at once (no input kept): nothing to take back
        stroke(100)
        assertNull(ink.takeTapped())
    }

    @Test
    fun anUndoCountsThePauseAnewAndTheEndEndsIt() {
        stroke(0)
        ink.undone(400)
        assertTrue(ink.writing(850))
        ink.ended()
        assertFalse(ink.writing(860))
        // nothing to count from once ended
        ink.undone(870)
        assertFalse(ink.writing(880))
    }
}
