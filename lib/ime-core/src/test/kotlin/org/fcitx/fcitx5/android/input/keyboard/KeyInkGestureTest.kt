/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.keyboard.GlideTouch.Route
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyInkGestureTest {

    // a keyboard's touch and its writing, new for each table row: what one row did is not the next one's start
    private class Keys {
        val ink = KeyInkGesture().apply { pauseMillis = 500 }
        val t = GlideTouch()

        // keys 100 by 140, the long press at 300 ms; [letter] false: another key, [gap]: a split keyboard's gap
        fun down(time: Long, typed: Boolean = false, letter: Boolean = true, gap: Boolean = false) =
            t.down(0, 50f, 70f, time, onLetter = letter && !gap, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300, inGap = gap, ink = ink, typed = typed)

        // a stroke of 0.3 key down, from [time]
        fun stroke(time: Long, typed: Boolean = false): Route {
            down(time, typed = typed)
            assertEquals(Route.KEYS, t.move(50f, 80f, time + 10))
            assertEquals(Route.CANCEL_KEYS, t.move(50f, 115f, time + 20))
            return t.up(50f, 120f, cancelled = false, time = time + 30)
        }
    }

    private val keys = Keys()
    private val ink = keys.ink
    private val t = keys.t

    private fun down(time: Long, typed: Boolean = false, letter: Boolean = true, gap: Boolean = false) = keys.down(time, typed, letter, gap)

    private fun stroke(time: Long, typed: Boolean = false): Route = keys.stroke(time, typed)

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
    fun aTapIsTakenBackOnlyAsTheFirstDotRightAfterIt() {
        // each row's touches on a keyboard of its own, then: no tap given back as a first dot
        fun row(touches: Keys.() -> Unit): () -> Unit = {
            val k = Keys()
            k.touches()
            assertNull(k.ink.takeTapped())
        }
        assertRows(
            "a stroke past the pause after the tap: input typed, no writing" to row {
                down(0)
                t.up(50f, 70f, cancelled = false, time = 40)
                down(600, typed = true)
                assertEquals(Route.KEYS, t.move(50f, 140f, 620))
            },
            "a second letter after the tap started no input: the touch after takes nothing back" to row {
                down(0)
                t.up(50f, 70f, cancelled = false, time = 20)
                down(50, typed = true)
                t.up(50f, 70f, cancelled = false, time = 60)
                down(100, typed = true)
                assertEquals(Route.KEYS, t.move(50f, 140f, 120))
            },
            "a key not a letter (backspace) between: that tap is no first dot" to row {
                down(0)
                t.up(50f, 70f, cancelled = false, time = 20)
                down(30, letter = false)
                t.up(50f, 70f, cancelled = false, time = 40)
                down(50, typed = true)
                assertEquals(Route.KEYS, t.move(50f, 140f, 70))
            },
            "another letter long pressed between (its popup's letter typed), lifted past the long press" to row {
                down(0)
                t.up(50f, 70f, cancelled = false, time = 40)
                down(80, typed = true)
                assertEquals(Route.KEYS, t.move(50f, 71f, 400))
                t.up(50f, 71f, cancelled = false, time = 450)
                down(480, typed = true)
                assertEquals(Route.KEYS, t.move(50f, 115f, 500))
            },
            "a cancelled touch after the tap" to row {
                down(0)
                t.up(50f, 70f, cancelled = false, time = 40)
                down(80, typed = true)
                t.up(50f, 70f, cancelled = true, time = 100)
                down(150, typed = true)
                assertEquals(Route.KEYS, t.move(50f, 115f, 170))
            },
            "a second finger after the tap" to row {
                down(0)
                t.up(50f, 70f, cancelled = false, time = 40)
                down(80, typed = true)
                assertEquals(Route.KEYS, t.otherDown())
                t.up(50f, 70f, cancelled = false, time = 100)
                down(150, typed = true)
                assertEquals(Route.KEYS, t.move(50f, 115f, 170))
            },
            "a key held past its long press is no tap" to row {
                down(0)
                t.up(50f, 70f, cancelled = false, time = 400)
                down(450, typed = true)
                assertEquals(Route.KEYS, t.move(50f, 140f, 470))
            },
            "the letter went in at once (no input kept), as the engine says: a stroke writes, nothing to take back" to row {
                down(0)
                t.up(50f, 70f, cancelled = false, time = 40)
                ink.engineTyped(false)
                assertEquals(Route.GLIDE, stroke(100))
            },
        )
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
    fun aTapInTheGapSendsNothingSoNothingIsTakenBackAndWritingGoesOn() {
        down(0, gap = true)
        t.up(50f, 70f, cancelled = false, time = 40)
        // within the pause: a stroke, no tap taken back
        assertEquals(Route.GLIDE, stroke(60))
        assertNull(ink.takeTapped())
        // after the pause: writing still allowed
        assertEquals(Route.GLIDE, stroke(1000))
        assertNull(ink.takeTapped())
    }

    @Test
    fun theEnginesWordReplacesATapsMark() {
        down(0)
        t.up(50f, 70f, cancelled = false, time = 40)
        ink.engineTyped(true)
        ink.engineTyped(false)
        // past the pause, the engine said nothing typed: a stroke writes
        assertEquals(Route.GLIDE, stroke(1000))
    }

    // a tap on a letter that started the input, the engine having heard 5 keys before it
    private fun tapAfterFiveKeys() {
        ink.engineTyped(false, keysHeard = 5)
        down(0)
        t.up(50f, 70f, cancelled = false, time = 40)
    }

    @Test
    fun aLateAnswerFromBeforeTheTapLeavesItsMark() {
        tapAfterFiveKeys()
        // the answer to a key before the tap, heard late: not about the tap, which stays typed
        ink.engineTyped(false, keysHeard = 5)
        assertEquals(Route.GLIDE, stroke(100))
        assertArrayEquals(floatArrayOf(50f, 70f, 50f, 70f), ink.takeTapped(), 0f)
    }

    @Test
    fun theAnswerAboutTheTapReplacesItsMark() {
        tapAfterFiveKeys()
        // the tap's own key answered (or an ink answer after it): the letter went in at once, nothing to take back
        ink.engineTyped(false, keysHeard = 6)
        assertEquals(Route.GLIDE, stroke(100))
        assertNull(ink.takeTapped())
    }

    @Test
    fun anEngineResetReplacesTheTapsMarkWhateverWasHeard() {
        tapAfterFiveKeys()
        ink.engineTyped(false)
        assertEquals(Route.GLIDE, stroke(100))
        assertNull(ink.takeTapped())
    }

    @Test
    fun aTapNeverAnsweredHoldsItsMarkOnlyTillThePause() {
        tapAfterFiveKeys()
        ink.engineTyped(false, keysHeard = 5)
        // past the pause, the tap's key never heard: what the engine last said counts, writing is not blocked
        assertEquals(Route.GLIDE, stroke(1000))
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
