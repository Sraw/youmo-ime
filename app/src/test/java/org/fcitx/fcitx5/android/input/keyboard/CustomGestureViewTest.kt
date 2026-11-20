/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.MotionEvent
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A double tap is timed by the touches' eventTime, not the wall clock, which jumps when the system time is set. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class CustomGestureViewTest {

    private var clicks = 0
    private var doubleTaps = 0

    private fun view() = CustomGestureView(RuntimeEnvironment.getApplication()).apply {
        doubleTapEnabled = true
        onDoubleTapListener = { doubleTaps++ }
        setOnClickListener { clicks++ }
    }

    private fun CustomGestureView.tapAt(downTime: Long) {
        listOf(MotionEvent.ACTION_DOWN to downTime, MotionEvent.ACTION_UP to downTime + 10).forEach { (action, at) ->
            val event = MotionEvent.obtain(downTime, at, action, 5f, 5f, 0)
            onTouchEvent(event)
            event.recycle()
        }
    }

    @Test
    fun twoTapsAreADoubleTapByTheirEventTime() {
        fun taps(second: Long, clicked: Int, doubled: Int) = {
            clicks = 0
            doubleTaps = 0
            view().apply {
                tapAt(1_000L)
                tapAt(second)
            }
            assertEquals(clicked, clicks)
            assertEquals(doubled, doubleTaps)
        }
        assertRows(
            "close in their event time: a double tap" to taps(1_050L, clicked = 1, doubled = 1),
            "far apart, however soon they arrive: two taps" to taps(11_000L, clicked = 2, doubled = 0),
        )
    }

    // where the finger came down goes with a click no plain tap made: a swipe whose slot is nothing and a stroke lifted
    // short; a swipe's own touch past the threshold has none
    @Test
    fun aSwipesTapAndAStrokeLiftedShortHaveWhereTheFingerCameDown() {
        val at = ArrayList<Pair<Float, Float>>()
        val v = CustomGestureView(RuntimeEnvironment.getApplication()).apply {
            layout(0, 0, 100, 200)
            swipeEnabled = true
            swipeThresholdY = 20f
            setOnClickListener { at += keyGesture.clickAt to keyGesture.clickDown }
            onGestureListener = CustomGestureView.OnGestureListener { view, e ->
                if (e.type == CustomGestureView.GestureType.Up && e.totalY != 0) view.performClick()
                e.type == CustomGestureView.GestureType.Up && e.totalY != 0
            }
        }
        listOf(MotionEvent.ACTION_DOWN to 50f, MotionEvent.ACTION_MOVE to 110f, MotionEvent.ACTION_UP to 110f).forEach { (action, y) ->
            val event = MotionEvent.obtain(1_000L, 1_010L, action, 30f, y, 0)
            v.onTouchEvent(event)
            event.recycle()
        }
        assertEquals(listOf(0.3f to 0.25f), at)
        at.clear()
        v.keyGesture.tap(70f, 150f)
        assertEquals(listOf(0.7f to 0.75f), at)
        assertTrue(v.clickAt.isNaN())
    }
}
