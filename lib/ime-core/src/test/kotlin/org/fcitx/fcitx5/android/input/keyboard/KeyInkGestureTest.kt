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
        fun down(time: Long, typed: Boolean = false, letter: Boolean = true, gap: Boolean = false, x: Float = 50f, y: Float = 70f) =
            t.down(0, x, y, time, onLetter = letter && !gap, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300, inGap = gap, ink = ink, typed = typed)

        // a finger down at [time] (at [x]), moved 0.32 then 0.54 of a key down, lifted: what the last move made of it
        fun drag(time: Long, typed: Boolean = false, x: Float = 50f): Route {
            down(time, typed = typed, x = x)
            assertEquals(Route.KEYS, t.move(x, 115f, time + 10))
            val r = t.move(x, 145f, time + 20)
            t.up(x, 150f, cancelled = false, time = time + 30)
            return r
        }

        // a stroke of half a key down and more, from [time]
        fun stroke(time: Long, typed: Boolean = false): Route {
            down(time, typed = typed)
            assertEquals(Route.KEYS, t.move(50f, 80f, time + 5))
            // a third of a key: a tap's drag yet
            assertEquals(Route.KEYS, t.move(50f, 115f, time + 10))
            assertEquals(Route.CANCEL_KEYS, t.move(50f, 145f, time + 20))
            return t.up(50f, 150f, cancelled = false, time = time + 30)
        }

        // a tap on another key (a space): down at [time], lifted 20 ms on
        fun key(time: Long) {
            down(time, letter = false)
            assertEquals(Route.KEYS, t.up(50f, 70f, cancelled = false, time = time + 20))
        }
    }

    private val keys = Keys()
    private val ink = keys.ink
    private val t = keys.t

    private fun down(time: Long, typed: Boolean = false, letter: Boolean = true, gap: Boolean = false) = keys.down(time, typed, letter, gap)

    private fun stroke(time: Long, typed: Boolean = false): Route = keys.stroke(time, typed)

    @Test
    fun aMoveOfHalfAKeyAnyWayIsAStroke() {
        down(0)
        assertEquals(Route.KEYS, t.move(70f, 70f, 10))
        // across: 0.4 of the width, a tap dragged
        assertEquals(Route.KEYS, t.move(90f, 70f, 15))
        // half of it
        assertEquals(Route.CANCEL_KEYS, t.move(100f, 70f, 20))
        assertTrue(t.gliding)
        assertEquals(Route.GLIDE, t.up(100f, 70f, cancelled = false, time = 30))
        assertTrue(ink.writing(100))
    }

    @Test
    fun aTapDraggedUnderHalfAKeyIsTheKeysAndStartsNoWriting() {
        // 0.45 of a key across and down (a third of a 140 pixel key's height), as a quick tap's finger rolls at its lift
        down(0)
        assertEquals(Route.KEYS, t.move(65f, 80f, 40))
        assertEquals(Route.KEYS, t.move(90f, 100f, 70))
        assertFalse(t.gliding)
        assertEquals(Route.KEYS, t.up(90f, 100f, cancelled = false, time = 80))
        assertFalse(ink.writing(100))
    }

    @Test
    fun charactersFirstStrokesStartTheWriting() {
        // first strokes as written two key heights tall (Make Me a Hanzi's, design 060-kbd-handwriting-start), each
        // reaching its length within 150 ms: the 一 of 一 and 十, the 丨 of 口 and 中, the 丿 of 的 and 人
        fun row(dx: Float, dy: Float): () -> Unit = {
            val k = Keys()
            k.down(0, x = 300f, y = 200f)
            val r = (1..6).map { k.t.move(300f + dx * 100f * it / 6, 200f + dy * 140f * it / 6, 25L * it) }
            assertTrue(r.toString(), Route.CANCEL_KEYS in r)
            assertTrue(k.t.gliding)
        }
        assertRows(
            "一, 2.4 keys across" to row(2.4f, 0f),
            "the 丨 of 口, 0.9 of a key down" to row(0f, 0.9f),
            "the 丨 of 中, 0.6 of a key down" to row(0f, 0.6f),
            "the 丿 of 的, 0.54 of a key down and left" to row(-0.2f, 0.5f),
            "the 丿 of 人, 1.7 keys" to row(-0.6f, 1.6f),
        )
    }

    @Test
    fun noWritingStartsRightAfterAKeyTypedNorIsATapThenAFirstDot() {
        // a space (or a mark, backspace) lifted at 20: a tap dragged half a key within the next 500 ms is the key's
        keys.key(0)
        assertEquals(Route.KEYS, keys.drag(300))
        assertFalse(ink.writing(340))
        // a tap then is typing, no first dot: the stroke after it takes nothing back
        down(400)
        t.up(50f, 70f, cancelled = false, time = 420)
        assertEquals(Route.KEYS, keys.drag(500, typed = true))
        assertNull(ink.takeTapped())
        // past 500 ms from the last key (the drag's lift at 530, a letter typed): a stroke writes
        assertEquals(Route.GLIDE, stroke(1100, typed = false))
    }

    @Test
    fun theTypingLastsFiveHundredMillisecondsFromTheKeysLift() {
        // a space lifted at 20: a drag down at 519 is the key's, at 520 a stroke
        keys.key(0)
        assertEquals(Route.KEYS, keys.drag(519))
        val next = Keys()
        next.key(0)
        assertEquals(Route.CANCEL_KEYS, next.drag(20 + KeyInkGesture.TYPING_MILLIS))
    }

    @Test
    fun aLetterTypedWithInputGoingOnIsTypingToo() {
        // a letter where no writing may start (input typed): told as a key, and the drag right after it no stroke
        down(0, typed = true)
        assertEquals(Route.KEYS, t.up(50f, 70f, cancelled = false, time = 20))
        assertEquals(Route.KEYS, keys.drag(200))
        // 500 ms from that drag's lift, a letter typed too
        assertEquals(Route.CANCEL_KEYS, keys.drag(800))
    }

    @Test
    fun aKeyAfterTheWritingIsNoTypingTillALetterIsTyped() {
        stroke(0)
        // the space that picks what was written, past the pause, then the next character at once
        keys.key(600)
        assertEquals(Route.GLIDE, stroke(650))
        // a letter typed after the writing (the input started), a space: typing again
        down(1300)
        t.up(50f, 70f, cancelled = false, time = 1320)
        ink.engineTyped(false)
        keys.key(1400)
        assertEquals(Route.KEYS, keys.drag(1500))
        assertEquals(Route.CANCEL_KEYS, keys.drag(2100))
    }

    @Test
    fun onlyAStrokeNearTheTapTakesItBack() {
        down(0)
        t.up(50f, 70f, cancelled = false, time = 20)
        // 2.6 keys off: the next letter typed dragged, the tap no first dot
        assertEquals(Route.KEYS, keys.drag(200, typed = true, x = 310f))
        assertNull(ink.takeTapped())
        val near = Keys()
        near.down(0)
        near.t.up(50f, 70f, cancelled = false, time = 20)
        // 2.4 keys off: a dot character's second stroke, as 主's 横 written large
        assertEquals(Route.CANCEL_KEYS, near.drag(200, typed = true, x = 290f))
        assertArrayEquals(floatArrayOf(50f, 70f, 50f, 70f), near.ink.takeTapped(), 0f)
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
        assertEquals(Route.CANCEL_KEYS, t.move(50f, 145f, 20))
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
            "the letter went in at once (no input kept), as the engine says: typing, then a stroke writes, nothing to take back" to row {
                down(0)
                t.up(50f, 70f, cancelled = false, time = 40)
                ink.engineTyped(false)
                assertEquals(Route.KEYS, drag(100))
                assertEquals(Route.GLIDE, stroke(700))
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

    @Test
    fun onlyTheAnswerToTheTapsOwnKeyOrALaterOneReplacesItsMark() {
        // each row on a keyboard of its own: keys numbered as the keyboard sends them, 5 sent and answered,
        // then a space (6) sent, a tap on a letter that started the input (its key 7), [then] the answers
        // that come, and a stroke at [strokeAt]; [takenBack] whether the stroke takes the tap back
        // not [takenBack]: the tap was typing (its letter went in), so no stroke within 500 ms of it
        fun row(strokeAt: Long = 100, takenBack: Boolean, then: KeyInkGesture.() -> Unit): () -> Unit = {
            val k = Keys()
            var sent = 5L
            k.ink.nextKey = { sent + 1 }
            k.ink.engineTyped(false, keysHeard = 5)
            sent++
            k.down(0)
            k.t.up(50f, 70f, cancelled = false, time = 40)
            sent++
            k.ink.then()
            if (!takenBack && strokeAt - 40 < KeyInkGesture.TYPING_MILLIS) {
                assertEquals(Route.KEYS, k.drag(strokeAt))
            } else {
                assertEquals(Route.GLIDE, k.stroke(strokeAt))
            }
            val tap = k.ink.takeTapped()
            if (takenBack) assertArrayEquals(floatArrayOf(50f, 70f, 50f, 70f), tap, 0f) else assertNull(tap)
        }
        assertRows(
            "the space's answer, sent before the tap and heard after it, is not the tap's: its mark stays" to
                row(takenBack = true) { engineTyped(false, keysHeard = 6) },
            "an answer to a key or context from before the space, late, leaves the mark too" to
                row(takenBack = true) { engineTyped(false, keysHeard = 5) },
            "the tap's own key answered: the letter went in at once, nothing to take back" to
                row(takenBack = false) { engineTyped(false, keysHeard = 7) },
            "an answer after the tap's key (an ink answer, a context) is about the tap too" to
                row(takenBack = false) { engineTyped(false, keysHeard = 8) },
            "an engine reset replaces the mark whatever was heard" to
                row(takenBack = false) { engineTyped(false) },
            "the tap's key never answered: its mark held only till the pause, then what the engine last said counts" to
                row(strokeAt = 1000, takenBack = false) { engineTyped(false, keysHeard = 6) },
        )
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
