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

    @Test
    fun crossStepsBeginAtTheFirstSidewaysStep() {
        val c = CrossSteps()
        assertEquals("no sideways step yet: none", 0, c.step(-100f, 0, 28f))
        assertEquals("begins here", 0, c.step(-100f, 1, 28f))
        assertEquals(-1, c.step(-130f, 0, 28f))
        assertEquals(2, c.step(-70f, 0, 28f))
        c.reset()
        assertEquals("reset: waits for a sideways step again", 0, c.step(100f, 0, 28f))
        assertEquals("no threshold: none", 0, CrossSteps().step(0f, 1, 0f))
    }
}
