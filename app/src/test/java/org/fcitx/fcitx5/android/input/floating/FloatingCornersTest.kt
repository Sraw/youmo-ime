/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FloatingCornersTest {

    private fun outline(view: View) = Outline().also { view.outlineProvider.getOutline(view, it) }

    @Test
    fun theKeyboardIsClippedToARoundedRectangleItsSizeWithAShadow() {
        val view = View(RuntimeEnvironment.getApplication())
        var radius = 40f
        FloatingCorners.frame(view, { radius }, 12f)
        view.layout(0, 0, 600, 400)
        assertTrue(view.clipToOutline)
        assertEquals(12f, view.elevation, 0f)
        assertEquals(40f, outline(view).radius, 0f)
        val bounds = Rect()
        assertTrue(outline(view).getRect(bounds))
        assertEquals(Rect(0, 0, 600, 400), bounds)
        // turned, or the corners switched off: read again, square too
        radius = 0f
        view.layout(0, 0, 400, 600)
        assertEquals(0f, outline(view).radius, 0f)
        assertTrue(outline(view).getRect(bounds))
        assertEquals(Rect(0, 0, 400, 600), bounds)
    }
}
