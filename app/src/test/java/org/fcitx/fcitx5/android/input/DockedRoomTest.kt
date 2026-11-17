/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.FcitxApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/** 061-landscape-height §5.2: the keys worked out again only where the room laid out moved by more than a dp, once a frame. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w851dp-h393dp-land-440dpi")
class DockedRoomTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun dp(v: Double) = (v * ctx.resources.displayMetrics.density).roundToInt()

    // in a window, as the input view is: a view not attached runs nothing it posts
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val view = FrameLayout(activity).also { activity.setContentView(FrameLayout(activity).apply { addView(it, FrameLayout.LayoutParams(100, 1)) }) }
    private val kept = View(activity).also { view.addView(it, FrameLayout.LayoutParams(1, 1)) }
    private var relayouts = 0
    private var docked = true

    // the keys worked out again: in the room as it is then (InputView.updateKeyboardSize takes it)
    private val room: DockedRoom = DockedRoom(view, kept, { docked }) {
        relayouts++
        room.take()
    }

    private fun laidOut(height: Int) {
        view.layoutParams.height = height
        view.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 100, height)
    }

    private fun frame() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun predictedUntilLaidOutThenTheRoomLaidOut() {
        assertNull(room.take())
        laidOut(dp(300.0))
        frame()
        // the first layout: worked out in the prediction, so once again in the room laid out
        assertEquals(1, relayouts)
        assertEquals(dp(300.0), room.take())
        frame()
        assertEquals("worked out in it: no more", 1, relayouts)
    }

    @Test
    fun aDpOrLessIsNoNewRoom() {
        laidOut(dp(300.0))
        frame()
        room.take()
        relayouts = 0
        laidOut(dp(300.0) + 2) // 2 px, under a dp
        frame()
        assertEquals(0, relayouts)
        laidOut(dp(300.0) - dp(48.0))
        frame()
        assertEquals("the bar's insets came: worked out again", 1, relayouts)
    }

    @Test
    fun aLayoutAndTheInsetsInOneFrameAreOneRelayout() {
        laidOut(dp(300.0))
        frame()
        room.take()
        relayouts = 0
        (kept.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = dp(48.0)
        room.check()
        laidOut(dp(200.0))
        frame()
        assertEquals(1, relayouts)
    }

    // the input view replaced between the post and its frame (detached, its listeners gone): nothing of it laid out again
    // (R26 F8411, F8486)
    @Test
    fun aViewDetachedBeforeTheFrameIsNotLaidOutAgain() {
        laidOut(dp(300.0))
        frame()
        room.take()
        relayouts = 0
        laidOut(dp(200.0))
        (view.parent as ViewGroup).removeView(view)
        frame()
        assertEquals(0, relayouts)
    }

    @Test
    fun floatingHasNoRoom() {
        docked = false
        laidOut(dp(300.0))
        frame()
        assertEquals(0, relayouts)
    }
}
