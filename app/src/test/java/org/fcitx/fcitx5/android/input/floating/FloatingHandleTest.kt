/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.view.MotionEvent
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FloatingHandleTest {

    // F2895/F3048: only a finger lifted drops the keyboard where it is; a cancel or a second finger
    // ends the drag as not lifted
    @Test
    fun aDragEndsLiftedOnlyOnAnUp() {
        for ((end, lifted) in listOf(MotionEvent.ACTION_UP to true, MotionEvent.ACTION_CANCEL to false, MotionEvent.ACTION_POINTER_DOWN to false)) {
            val drops = ArrayList<Boolean>()
            val handle = FloatingHandle(PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark, FloatingHandleSide.entries.first(), { _, _ -> }, { drops += it }) {}
            handle.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0))
            handle.onTouchEvent(MotionEvent.obtain(0, 1, end, 0f, 0f, 0))
            // ended once: a second end is no drop
            handle.onTouchEvent(MotionEvent.obtain(0, 2, MotionEvent.ACTION_UP, 0f, 0f, 0))
            assertEquals("$end", listOf(lifted), drops)
        }
    }
}
