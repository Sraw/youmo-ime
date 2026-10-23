/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class FloatingHandleTest {

    private val detached = ArrayList<Pair<Float, Float>>()
    private val touches = ArrayList<HandoffTouch?>()

    // docked (始终显示悬浮条), attached: its long press is posted to the view
    private fun docked() = FloatingHandle(
        PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark, FloatingHandleSide.Bottom,
        FloatingHandle.Mode.Docked { x, y, touch ->
            detached += x to y
            touches += touch
        },
    ).also {
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(it, ViewGroup.LayoutParams(1000, 24))
        it.layout(0, 0, 1000, 24)
    }

    private fun waitLongPress() =
        shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getLongPressTimeout() + 50L, TimeUnit.MILLISECONDS)

    private fun touch(handle: FloatingHandle, action: Int, x: Float) =
        handle.onTouchEvent(MotionEvent.obtain(7, 7, action, x, 12f, 0))

    // each drop's lifted flag, for a drag ended by [end] and then a stray up
    private fun floatingDrops(end: Int): List<Boolean> {
        val drops = ArrayList<Boolean>()
        val handle = FloatingHandle(PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark, FloatingHandleSide.entries.first(), FloatingHandle.Mode.Floating({ _, _ -> }, { drops += it }) {})
        handle.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0))
        handle.onTouchEvent(MotionEvent.obtain(0, 1, end, 0f, 0f, 0))
        // ended once: a second end is no drop
        handle.onTouchEvent(MotionEvent.obtain(0, 2, MotionEvent.ACTION_UP, 0f, 0f, 0))
        return drops
    }

    // F2895/F3048: only a finger lifted drops the keyboard where it is; a cancel or a second finger
    // ends the drag as not lifted
    @Test
    fun aDragEndsLiftedOnlyOnAnUp() = assertRows(
        "a finger lifted drops it where it is" to { assertEquals(listOf(true), floatingDrops(MotionEvent.ACTION_UP)) },
        "a cancel ends the drag as not lifted" to { assertEquals(listOf(false), floatingDrops(MotionEvent.ACTION_CANCEL)) },
        "a second finger ends the drag as not lifted" to {
            assertEquals(listOf(false), floatingDrops(MotionEvent.ACTION_POINTER_DOWN))
        },
    )

    @Test
    fun dockedItsGripHeldLetsTheKeyboardGoAtTheFingerAndItsRightEndIsEmpty() {
        val handle = docked()
        assertEquals("the grip alone, no dock button", 1, handle.childCount)
        touch(handle, MotionEvent.ACTION_DOWN, 500f)
        touch(handle, MotionEvent.ACTION_MOVE, 503f)
        waitLongPress()
        assertEquals(listOf(503f to 12f), detached)
        // the touch and its finger, for the drag to go on with only them
        assertEquals(listOf(0), touches.map { it!!.pointerId })
        // once: the finger staying down after is the floating keyboard's drag
        waitLongPress()
        assertEquals(1, detached.size)
    }

    // which slide or end lets go is GripPress's (GripPressTest); here the view asks it and drops
    // its pending long press. Each row its own handle and a cleared record: no state between rows
    private fun noHoldAfter(vararg steps: Pair<Int, Float>) {
        detached.clear()
        val handle = docked()
        steps.forEach { (action, x) -> touch(handle, action, x) }
        waitLongPress()
        assertEquals(emptyList<Pair<Float, Float>>(), detached)
    }

    @Test
    fun dockedNoDetachWithoutAStillPressOnTheGrip() = assertRows(
        "the strip's end, nothing to hold" to { noHoldAfter(MotionEvent.ACTION_DOWN to 990f) },
        // F3557: its long press goes with the lift
        "a tap on the grip" to { noHoldAfter(MotionEvent.ACTION_DOWN to 500f, MotionEvent.ACTION_UP to 500f) },
        // F3557: a slide past the slop disarms it before the long press is due
        "a slide off the grip" to { noHoldAfter(MotionEvent.ACTION_DOWN to 500f, MotionEvent.ACTION_MOVE to 700f) },
    )

    // TalkBack's long press is no touch: nothing to carry on, or a later touch would be taken for it
    @Test
    fun dockedTalkBacksLongPressLetsItGoWithNoTouch() {
        val handle = docked()
        handle.performLongClick()
        assertEquals(1, detached.size)
        assertEquals(listOf<HandoffTouch?>(null), touches)
    }
}
