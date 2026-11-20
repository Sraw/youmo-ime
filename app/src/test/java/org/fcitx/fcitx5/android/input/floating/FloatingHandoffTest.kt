/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The input view made anew under a finger still down: the framework sends the old view a cancel, the rest of the touch goes to the frame. */
@RunWith(RobolectricTestRunner::class)
class FloatingHandoffTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun event(action: Int, x: Float, y: Float) = MotionEvent.obtain(0, 0, action, x, y, 0)

    private val oldSaw = ArrayList<Int>()

    private val frame = FrameLayout(context).apply {
        addView(object : View(context) {
            override fun onTouchEvent(e: MotionEvent): Boolean {
                oldSaw += e.actionMasked
                return true
            }
        })
        measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
        layout(0, 0, 1000, 2000)
    }

    private val drags = ArrayList<Pair<Float, Float>>()
    private val drops = ArrayList<Boolean>()

    private val receiver = object : FloatingHandoff.Receiver {
        override fun drag(dx: Float, dy: Float) {
            drags += dx to dy
        }

        override fun drop(lifted: Boolean) {
            drops += lifted
        }
    }

    private fun heldThenMadeAnew() {
        frame.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 500f, 1900f))
        frame.removeAllViews()
        frame.addView(View(context))
        FloatingHandoff(frame, HandoffTouch(0), 500f, 1900f) { receiver }.start()
    }

    @Test
    fun theFingerStillDownDragsTheNewKeyboardTillItLifts() {
        heldThenMadeAnew()
        frame.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 480f, 1800f))
        frame.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 470f, 1750f))
        frame.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 470f, 1750f))
        assertEquals("the old view was told the touch is gone", listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), oldSaw)
        assertEquals(listOf(-20f to -100f, -10f to -50f), drags)
        assertEquals(listOf(true), drops)
        frame.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 10f, 10f))
        frame.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 10f, 10f))
        assertEquals(listOf(true), drops)
    }

    @Test
    fun aCancelEndsItUnliftedAndANewTouchUnheldLeavesItAlone() {
        heldThenMadeAnew()
        frame.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, 500f, 1900f))
        assertEquals(listOf(false), drops)
        drops.clear()
        heldThenMadeAnew()
        // its end never came here (the system took it): the next touch is not taken for it
        assertFalse(frame.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 10f, 10f)))
        frame.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 10f, 10f))
        assertEquals(emptyList<Boolean>(), drops)
    }

    // a thumb (pointer 0) on a key while the other hand holds the grip (pointer 1), unsplit
    private fun fingers(action: Int, downTime: Long, vararg at: Triple<Int, Float, Float>): MotionEvent {
        val props = at.map { MotionEvent.PointerProperties().apply { id = it.first; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = at.map { MotionEvent.PointerCoords().apply { x = it.second; y = it.third; pressure = 1f; size = 1f } }
        return MotionEvent.obtain(downTime, 0, action, at.size, props.toTypedArray(), coords.toTypedArray(), 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun upOf(index: Int) = MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun handOffTheSecondFinger() {
        frame.removeAllViews()
        FloatingHandoff(frame, HandoffTouch(1), 500f, 1950f) { receiver }.start()
    }

    @Test
    fun onlyTheGripsFingerDragsItAThumbOnAKeyIsNothingOfIts() {
        handOffTheSecondFinger()
        frame.dispatchTouchEvent(fingers(MotionEvent.ACTION_MOVE, 5L, Triple(0, 100f, 1900f), Triple(1, 480f, 1850f)))
        frame.dispatchTouchEvent(fingers(upOf(0), 5L, Triple(0, 100f, 1900f), Triple(1, 480f, 1850f)))
        frame.dispatchTouchEvent(fingers(MotionEvent.ACTION_MOVE, 5L, Triple(1, 470f, 1800f)))
        frame.dispatchTouchEvent(fingers(MotionEvent.ACTION_UP, 5L, Triple(1, 470f, 1800f)))
        assertEquals(listOf(-20f to -100f, -10f to -50f), drags)
        assertEquals(listOf(true), drops)
    }

    @Test
    fun theGripsFingerLiftedFirstItEndsThereTheThumbMovesNothing() {
        handOffTheSecondFinger()
        frame.dispatchTouchEvent(fingers(upOf(1), 5L, Triple(0, 100f, 1900f), Triple(1, 500f, 1950f)))
        frame.dispatchTouchEvent(fingers(MotionEvent.ACTION_MOVE, 5L, Triple(0, 300f, 1000f)))
        frame.dispatchTouchEvent(fingers(MotionEvent.ACTION_UP, 5L, Triple(0, 300f, 1000f)))
        assertEquals(emptyList<Pair<Float, Float>>(), drags)
        assertEquals(listOf(true), drops)
    }

    // adb's `input motionevent` sends each event with its own down time: telling the touch by down time dropped the drag
    @Test
    fun movesEachWithTheirOwnDownTimeStillDragIt() {
        handOffTheSecondFinger()
        frame.dispatchTouchEvent(fingers(MotionEvent.ACTION_MOVE, 900L, Triple(1, 500f, 1850f)))
        frame.dispatchTouchEvent(fingers(MotionEvent.ACTION_MOVE, 950L, Triple(1, 500f, 1100f)))
        frame.dispatchTouchEvent(fingers(MotionEvent.ACTION_UP, 1300L, Triple(1, 500f, 1100f)))
        assertEquals(listOf(0f to -100f, 0f to -750f), drags)
        assertEquals(listOf(true), drops)
    }

    // its end never came: a new touch on the input view ends it, so a later remade view never passes that on as this drag
    @Test
    fun aNewTouchOnTheInputViewEndsOneWhoseEndNeverCame() {
        handOffTheSecondFinger()
        FloatingHandoff.newTouch(frame)
        frame.dispatchTouchEvent(fingers(MotionEvent.ACTION_MOVE, 5L, Triple(1, 0f, 0f)))
        frame.dispatchTouchEvent(fingers(MotionEvent.ACTION_UP, 5L, Triple(1, 0f, 0f)))
        assertEquals(emptyList<Pair<Float, Float>>(), drags)
        assertEquals(emptyList<Boolean>(), drops)
        handOffTheSecondFinger()
        FloatingHandoff.newTouch(FrameLayout(context))
        frame.dispatchTouchEvent(fingers(MotionEvent.ACTION_UP, 5L, Triple(1, 0f, 0f)))
        assertEquals(listOf(true), drops)
    }
}
