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
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboard
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/** N6: docked, the handle (始终显示悬浮条) goes with the board at a side, as the bars do, its grip at the board's middle. */
@RunWith(RobolectricTestRunner::class)
class BoardFollowersTest {

    private val context = PlaceholderStrings(RuntimeEnvironment.getApplication())
    private val detached = ArrayList<Float>()

    private val root = ConstraintLayout(context)

    // the board one-handed at the right, 400 of 1000 wide
    private val board = View(context).apply { id = View.generateViewId() }

    private val strip = FloatingHandle(context, ThemePreset.PixelDark, FloatingHandleSide.Bottom, FloatingHandle.Mode.Docked { x, _, _ -> detached += x })

    init {
        root.addView(board, ConstraintLayout.LayoutParams(400, 300).apply {
            rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        root.addView(strip, ConstraintLayout.LayoutParams(0, 24).apply {
            leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
            rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        })
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(root)
    }

    private fun layOut() {
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1000, 400)
    }

    @Test
    fun atASideTheStripIsTheBoardsWidthOverItItsGripAtTheBoardsMiddle() {
        BoardFollowers.place(listOf(strip), board, SplitKeyboard.Dock.RIGHT, padding = 16)
        layOut()
        assertEquals(600 to 1000, strip.left to strip.right)
        assertEquals("no side padding at a side", 0, strip.paddingLeft)
        // held at the board's middle (local 200): it lets go
        strip.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 200f, 12f, 0))
        shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getLongPressTimeout() + 50L, TimeUnit.MILLISECONDS)
        assertEquals(1, detached.size)
    }

    @Test
    fun acrossItIsTheWholeWidthInFromTheSidesAsTheBoardIs() {
        BoardFollowers.place(listOf(strip), board, SplitKeyboard.Dock.RIGHT, padding = 16)
        layOut()
        BoardFollowers.place(listOf(strip), board, null, padding = 16)
        layOut()
        assertEquals(0 to 1000, strip.left to strip.right)
        assertEquals(16 to 16, strip.paddingLeft to strip.paddingRight)
    }
}
