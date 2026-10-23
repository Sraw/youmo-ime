/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GripPressTest {

    private val press = GripPress(slop = 10f, gripWidth = 120f)

    @Test
    fun aStillPressOnTheGripCountsOnce() {
        assertTrue(press.down(500f, 12f, 1000f))
        press.move(505f, 15f)
        assertTrue("within the slop", press.fire())
        assertFalse("fired once", press.fire())
    }

    @Test
    fun onlyTheGripAboutTheMiddleIsHeld() {
        assertTrue("its edge", press.down(560f, 0f, 1000f))
        assertFalse("beside it", press.down(561f, 0f, 1000f))
        assertFalse(press.fire())
        assertFalse("the strip's end", press.down(990f, 12f, 1000f))
        assertFalse(press.fire())
    }

    // each on a press of its own: one failing hides none of the others
    @Test
    fun aFingerSlidingAwayLiftedOrJoinedIsNoHold() = assertRows(
        "past the slop" to {
            val p = GripPress(slop = 10f, gripWidth = 120f)
            p.down(500f, 12f, 1000f)
            p.move(511f, 12f)
            assertFalse(p.fire())
        },
        "lifted, or a second finger" to {
            val p = GripPress(slop = 10f, gripWidth = 120f)
            p.down(500f, 12f, 1000f)
            p.move(500f, 12f)
            p.end()
            assertFalse(p.fire())
        },
        "back over the grip is not a hold again" to {
            val p = GripPress(slop = 10f, gripWidth = 120f)
            p.down(500f, 12f, 1000f)
            p.move(520f, 12f)
            p.move(500f, 12f)
            assertFalse(p.fire())
        },
    )
}
