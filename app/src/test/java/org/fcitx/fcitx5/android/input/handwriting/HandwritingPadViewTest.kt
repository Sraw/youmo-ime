/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * The pad's touches and timer: a backspace timed the pause afresh, as the stroke it took back is
 * often written again (else the strokes left ended the character first), and a finger resting on
 * the pad left the writing one's later strokes unread. In a window, where the view's timer runs
 * on the looper, as on a phone.
 */
@RunWith(RobolectricTestRunner::class)
class HandwritingPadViewTest {

    private val reads = ArrayList<InkPad.Read>()
    private val finished = ArrayList<Int>()

    private fun pad() = HandwritingPadView(
        PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark,
        { reads += it }, { PAUSE }, { finished += it },
    ).also { Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(it) }

    /** Fingers (id, x, y) as one event of [action]. */
    private fun HandwritingPadView.touch(action: Int, vararg fingers: Triple<Int, Float, Float>) {
        val properties = fingers.map { f -> MotionEvent.PointerProperties().apply { id = f.first } }
        val coords = fingers.map { f ->
            MotionEvent.PointerCoords().apply {
                x = f.second
                y = f.third
                pressure = 1f
                size = 1f
            }
        }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, fingers.size, properties.toTypedArray(), coords.toTypedArray(), 0, 0, 1f, 1f, 0, 0, 0, 0)
        onTouchEvent(event)
        event.recycle()
    }

    private fun of(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun HandwritingPadView.stroke(x: Float, y: Float) {
        touch(MotionEvent.ACTION_DOWN, Triple(0, x, y))
        touch(MotionEvent.ACTION_MOVE, Triple(0, x + 10, y + 10))
        touch(MotionEvent.ACTION_UP, Triple(0, x + 10, y + 10))
    }

    @Test
    fun backspaceTimesThePauseAgainSoTheStrokeTakenBackIsRewrittenInTheSameCharacter() {
        val looper = shadowOf(Looper.getMainLooper())
        val view = pad()
        view.stroke(10f, 10f)
        view.stroke(20f, 20f)
        looper.idleFor(PAUSE / 2, TimeUnit.MILLISECONDS)
        assertTrue(view.undo())
        // past the pause since the last stroke went up, not since the backspace
        looper.idleFor(PAUSE / 2 + 100, TimeUnit.MILLISECONDS)
        assertEquals(emptyList<Int>(), finished)
        view.stroke(30f, 30f)
        assertEquals(2, reads.last().strokes.size)
        looper.idleFor(PAUSE, TimeUnit.MILLISECONDS)
        assertEquals(listOf(reads.last().piece), finished)
    }

    @Test
    fun aFingerRestingOnThePadDoesNotStopTheStrokesWrittenAfterIt() {
        val view = pad()
        // the writing finger (0) down, then a thumb (1) resting by it
        view.touch(MotionEvent.ACTION_DOWN, Triple(0, 10f, 10f))
        view.touch(of(MotionEvent.ACTION_POINTER_DOWN, 1), Triple(0, 10f, 10f), Triple(1, 90f, 90f))
        view.touch(MotionEvent.ACTION_MOVE, Triple(0, 20f, 20f), Triple(1, 95f, 95f))
        view.touch(of(MotionEvent.ACTION_POINTER_UP, 0), Triple(0, 20f, 20f), Triple(1, 95f, 95f))
        assertEquals(1, reads.size)
        // down again while the thumb rests: another pointer
        view.touch(of(MotionEvent.ACTION_POINTER_DOWN, 0), Triple(0, 30f, 30f), Triple(1, 95f, 95f))
        view.touch(MotionEvent.ACTION_MOVE, Triple(0, 40f, 40f), Triple(1, 96f, 96f))
        view.touch(of(MotionEvent.ACTION_POINTER_UP, 0), Triple(0, 40f, 40f), Triple(1, 96f, 96f))
        assertEquals(2, reads.size)
        // the thumb's moves are no ink, nor is its lifting a stroke
        assertArrayEquals(floatArrayOf(10f, 10f, 20f, 20f, 20f, 20f), reads[1].strokes[0], 0f)
        assertArrayEquals(floatArrayOf(30f, 30f, 40f, 40f, 40f, 40f), reads[1].strokes[1], 0f)
        view.touch(MotionEvent.ACTION_UP, Triple(1, 96f, 96f))
        assertEquals(2, reads.size)
    }

    private companion object {
        const val PAUSE = 700L
    }
}
