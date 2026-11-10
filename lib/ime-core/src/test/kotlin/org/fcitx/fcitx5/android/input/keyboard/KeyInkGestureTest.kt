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

    // keys 100 by 140, the long press at 300 ms; [letter] false: another key, [gap]: a split keyboard's gap
    private fun down(time: Long, typed: Boolean = false, letter: Boolean = true, gap: Boolean = false) =
        t.down(0, 50f, 70f, time, onLetter = letter && !gap, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300, inGap = gap, ink = ink, typed = typed)

    // a stroke of 0.3 key down, from [time]
    private fun stroke(time: Long, typed: Boolean = false): Route {
        down(time, typed = typed)
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
    fun afterThePauseWritingGoesOnWhileWhatShowsIsHandwritings() {
        stroke(0)
        // the pause over, the character written shows: the engine says the input method has nothing typed of its own
        assertFalse(ink.writing(600))
        assertEquals(Route.GLIDE, stroke(600, typed = false))
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
        down(0, gap = true)
        assertEquals(Route.CANCEL_KEYS, t.move(50f, 115f, 20))
    }

    @Test
    fun nothingIsWrittenWhileSomethingIsTyped() {
        down(0, typed = true)
        assertEquals(Route.KEYS, t.move(50f, 140f, 20))
        assertFalse(ink.writing(30))
    }

    @Test
    fun aTapThatStartedTheInputIsTakenBackAsTheFirstDot() {
        down(0)
        assertEquals(Route.KEYS, t.up(52f, 72f, cancelled = false, time = 40))
        // within the pause, the input that tap's, whatever it shows (the nine keys' or zhuyin's own spelling)
        assertEquals(Route.GLIDE, stroke(300, typed = true))
        assertArrayEquals(floatArrayOf(50f, 70f, 52f, 72f), ink.takeTapped(), 0f)
        // given once
        assertNull(ink.takeTapped())
    }

    @Test
    fun aTapIsNotTakenBackLateOrAfterAnotherKey() {
        down(0)
        t.up(50f, 70f, cancelled = false, time = 40)
        // past the pause
        down(600, typed = true)
        assertEquals(Route.KEYS, t.move(50f, 140f, 620))
        t.up(50f, 140f, cancelled = false, time = 630)
        // a second letter started no input: the next touch takes nothing back
        down(700)
        t.up(50f, 70f, cancelled = false, time = 720)
        down(750, typed = true)
        t.up(50f, 70f, cancelled = false, time = 760)
        down(800, typed = true)
        assertEquals(Route.KEYS, t.move(50f, 140f, 820))
        t.up(50f, 140f, cancelled = false, time = 830)
        // a key not a letter between (backspace): that tap is no first dot
        down(900)
        t.up(50f, 70f, cancelled = false, time = 920)
        down(930, letter = false)
        t.up(50f, 70f, cancelled = false, time = 940)
        down(950, typed = true)
        assertEquals(Route.KEYS, t.move(50f, 140f, 970))
        assertNull(ink.takeTapped())
    }

    @Test
    fun aKeyHeldPastItsLongPressIsNoTap() {
        down(0)
        t.up(50f, 70f, cancelled = false, time = 400)
        down(450, typed = true)
        assertEquals(Route.KEYS, t.move(50f, 140f, 470))
    }

    @Test
    fun aStrokeWithNothingTypedTakesNoTapBack() {
        down(0)
        t.up(50f, 70f, cancelled = false, time = 40)
        // the letter went in at once (no input kept), as the engine says: nothing to take back
        ink.engineTyped(false)
        stroke(100)
        assertNull(ink.takeTapped())
    }

    @Test
    fun aFastDotThenStrokeIsTakenBackThoughTheEngineWasNotHeardFromYet() {
        down(0)
        t.up(50f, 70f, cancelled = false, time = 40)
        // what the keyboard last heard is from before the tap's key: nothing typed
        assertEquals(Route.GLIDE, stroke(60, typed = false))
        assertArrayEquals(floatArrayOf(50f, 70f, 50f, 70f), ink.takeTapped(), 0f)
        // the next stroke of the writing takes nothing more back
        down(100)
        assertEquals(Route.GLIDE, t.up(50f, 71f, cancelled = false, time = 110))
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
