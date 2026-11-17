/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class FloatingHandleTest {

    private val detached = ArrayList<Pair<Float, Float>>()
    private val touches = ArrayList<HandoffTouch?>()
    private val activities = ArrayList<ActivityController<Activity>>()

    @After
    fun destroy() = activities.forEach { it.pause().stop().destroy() }

    // docked (始终显示悬浮条), attached: its long press is posted to the view
    private fun docked() = FloatingHandle(
        PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, FloatingHandleSide.Bottom,
        FloatingHandle.Mode.Docked { x, y, touch ->
            detached += x to y
            touches += touch
        },
    ).also {
        Robolectric.buildActivity(Activity::class.java).setup().also(activities::add).get()
            .setContentView(it, ViewGroup.LayoutParams(1000, 24))
        it.layout(0, 0, 1000, 24)
    }

    private fun waitLongPress() =
        shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getLongPressTimeout() + 50L, TimeUnit.MILLISECONDS)

    private fun touch(handle: FloatingHandle, action: Int, x: Float) =
        handle.onTouchEvent(MotionEvent.obtain(7, 7, action, x, 12f, 0))

    // each drop's lifted flag, for a drag ended by [end] and then a stray up
    private fun floatingDrops(end: Int): List<Boolean> {
        val drops = ArrayList<Boolean>()
        val handle = FloatingHandle(PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, FloatingHandleSide.entries.first(), FloatingHandle.Mode.Floating({ _, _ -> }, { drops += it }, {}, NoResize))
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

    // a second finger on the strip while the first resizes, on the resize button while it drags, or on the dock button
    // while it resizes: it ends the first's resize (drag), and nothing of its own starts beside it
    @Test
    fun oneFingerAtATimeOnTheFloatingStrip() {
        val log = ArrayList<String>()
        val resize = object : FloatingHandle.Resizing {
            override fun start() { log += "resize" }
            override fun move(dx: Float, dy: Float) { log += "resize move" }
            override fun end() { log += "resize end" }
            override fun step(bigger: Boolean) = false
        }
        val handle = FloatingHandle(
            PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, FloatingHandleSide.Bottom,
            FloatingHandle.Mode.Floating({ _, _ -> log += "drag" }, { log += "drop" }, { log += "dock" }, resize),
        )
        Robolectric.buildActivity(Activity::class.java).setup().also(activities::add).get()
            .setContentView(handle, ViewGroup.LayoutParams(1000, 24))
        handle.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(24, View.MeasureSpec.EXACTLY))
        handle.layout(0, 0, 1000, 24)
        val button = handle.resizeButton!!
        val onButton = button.left + button.width / 2f
        fun two(action: Int, first: Float, second: Float) = MotionEvent.obtain(
            0, 0, action, 2, arrayOf(MotionEvent.PointerProperties().apply { id = 0 }, MotionEvent.PointerProperties().apply { id = 1 }),
            arrayOf(MotionEvent.PointerCoords().apply { x = first; y = 12f }, MotionEvent.PointerCoords().apply { x = second; y = 12f }),
            0, 0, 1f, 1f, 0, 0, 0, 0,
        )
        assertRows(
            "a resize, a second finger on the strip" to {
                log.clear()
                handle.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, onButton, 12f, 0))
                handle.dispatchTouchEvent(two(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), onButton, 500f))
                handle.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, onButton - 30f, 400f))
                assertEquals(listOf("resize", "resize end"), log)
            },
            "a drag, a second finger on the resize button" to {
                log.clear()
                handle.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 500f, 12f, 0))
                handle.dispatchTouchEvent(two(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 500f, onButton))
                handle.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 400f, onButton - 30f))
                assertEquals(listOf("drop"), log)
            },
            "a resize, a second finger tapping the dock button" to {
                log.clear()
                val onDock = 1000f - 10f
                handle.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, onButton, 12f, 0))
                handle.dispatchTouchEvent(two(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), onButton, onDock))
                handle.dispatchTouchEvent(two(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), onButton, onDock))
                handle.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, onButton, 12f, 0))
                assertEquals(listOf("resize", "resize end"), log)
            },
        )
    }

    // a right-to-left language (Arabic, Hebrew, Uyghur): the dock button at the right end still, the resize button at the
    // left, not both at the left with the resize button over the dock button (R26 F8126: Gravity.END was the left there)
    @Test
    @Uat("TB-24")
    fun rightToLeftTheDockButtonStaysAtTheRightEnd() {
        val handle = FloatingHandle(
            PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, FloatingHandleSide.Bottom,
            FloatingHandle.Mode.Floating({ _, _ -> }, {}, {}, NoResize),
        )
        handle.layoutDirection = View.LAYOUT_DIRECTION_RTL
        Robolectric.buildActivity(Activity::class.java).setup().also(activities::add).get()
            .setContentView(handle, ViewGroup.LayoutParams(1000, 24))
        handle.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(24, View.MeasureSpec.EXACTLY))
        handle.layout(0, 0, 1000, 24)
        assertEquals("laid out right to left", View.LAYOUT_DIRECTION_RTL, handle.layoutDirection)
        assertEquals(listOf(1000, 0), listOf(handle.dockButton!!.right, handle.resizeButton!!.left))
    }

    // TalkBack's long press is no touch: nothing to carry on, or a later touch would be taken for it
    @Test
    fun dockedTalkBacksLongPressLetsItGoWithNoTouch() {
        val handle = docked()
        handle.performLongClick()
        assertEquals(1, detached.size)
        assertEquals(listOf<HandoffTouch?>(null), touches)
    }
}
