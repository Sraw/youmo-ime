/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Under the vivo keypress workaround the keyboard hands each key a one-finger event of its own
 * ([forKey]). Built with id 0 for every finger, hold to talk followed the wrong one: lifting
 * another finger ended it, and sliding the held one up no longer cancelled.
 */
@RunWith(RobolectricTestRunner::class)
class BaseKeyboardTest {

    private fun twoFingers(): MotionEvent {
        val properties = intArrayOf(0, 3).map { pid -> MotionEvent.PointerProperties().apply { id = pid } }
        val coords = listOf(10f to 20f, 110f to 30f).map { (px, py) ->
            MotionEvent.PointerCoords().apply {
                x = px
                y = py
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            100L, 200L,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties.toTypedArray(), coords.toTypedArray(),
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
    }

    @Test
    fun theKeysEventKeepsItsFingersId() {
        val e = twoFingers().forKey(MotionEvent.ACTION_DOWN, 1, Rect(100, 0, 140, 50))
        assertEquals(1, e.pointerCount)
        assertEquals("the second finger's id, not 0", 3, e.getPointerId(0))
        assertEquals(MotionEvent.ACTION_DOWN, e.actionMasked)
    }

    @Test
    fun theKeysEventIsInTheKeysCoordinates() {
        val e = twoFingers().forKey(MotionEvent.ACTION_MOVE, 1, Rect(100, 0, 140, 50))
        assertEquals(10f, e.x, 0f)
        assertEquals(30f, e.y, 0f)
        assertEquals(100L, e.downTime)
        assertEquals(200L, e.eventTime)
    }

    @Test
    fun theFirstFingerKeepsItsIdToo() {
        val e = twoFingers().forKey(MotionEvent.ACTION_UP, 0, Rect(0, 0, 40, 50))
        assertEquals(0, e.getPointerId(0))
        assertEquals(10f, e.x, 0f)
        assertEquals(20f, e.y, 0f)
    }
}
