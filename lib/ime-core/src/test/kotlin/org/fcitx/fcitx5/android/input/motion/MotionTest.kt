/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {

    /** F3460: nothing animates longer than 150 ms (user decision 2026-10-23). */
    @Test
    fun nothingPassesTheCapAndGoingTakesThreeQuartersOfComing() {
        val on = Motion.Settings.DEFAULT
        for (m in Motion.entries) {
            val enter = m.enter(on)
            assertTrue("$m: $enter ms", enter in 1..150)
            assertEquals("$m", enter * 3 / 4, m.exit(on))
        }
        assertEquals("ordered short to long", Motion.entries.map { it.enter(on) }.sorted(), Motion.entries.map { it.enter(on) })
    }

    @Test
    fun eitherTheSystemOrTheKeyboardTurningAnimationOffMakesEveryOneInstant() {
        for (off in listOf(Motion.Settings(systemScale = 0f, disabled = false), Motion.Settings(systemScale = 1f, disabled = true))) {
            assertEquals(false, off.animates)
            assertEquals(listOf(0L, 0L, 0L), Motion.entries.map { it.enter(off) + it.exit(off) })
        }
    }
}
