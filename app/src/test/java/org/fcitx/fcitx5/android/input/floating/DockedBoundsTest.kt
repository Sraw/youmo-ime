/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.widget.FrameLayout
import org.fcitx.fcitx5.android.input.floating.FloatingSwitch.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DockedBoundsTest {

    private val frame = FrameLayout(RuntimeEnvironment.getApplication())
    private val docked = Bounds(0f, 1200f, 1000f, 800f)

    @Test
    fun theNextViewInTheSameFrameTakesThemOnce() {
        DockedBounds.leave(frame, docked)
        assertEquals(docked, DockedBounds.take(frame))
        assertNull("taken", DockedBounds.take(frame))
    }

    @Test
    fun anotherFrameOrNoneNeverShrinksFromThem() {
        DockedBounds.leave(frame, docked)
        assertNull(DockedBounds.take(FrameLayout(RuntimeEnvironment.getApplication())))
        assertNull("cleared by the view that came next", DockedBounds.take(frame))
        DockedBounds.leave(frame, docked)
        assertNull(DockedBounds.take(null))
        assertNull(DockedBounds.take(frame))
    }
}
