/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeStepsTest {

    @Test
    fun wholeThresholdsTheRestCarried() {
        val s = SwipeSteps()
        s.start(100f)
        assertEquals(0, s.step(106f, 10f))
        assertEquals("6 carried and 6 more", 1, s.step(112f, 10f))
        assertEquals(-2, s.step(90f, 10f))
        assertEquals(-1, s.total)
    }

    @Test
    fun startForgetsTheLastDrag() {
        val s = SwipeSteps()
        s.start(0f)
        s.step(25f, 10f)
        s.start(50f)
        assertEquals(0, s.total)
        assertEquals("nothing carried from before", 0, s.step(59f, 10f))
    }
}
