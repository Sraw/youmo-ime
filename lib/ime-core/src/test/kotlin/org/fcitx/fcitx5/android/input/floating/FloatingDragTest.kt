/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingDragTest {

    private val maxTop = 1000f

    @Test
    fun broughtDownToTheBottomItIsInTheZone() {
        val drag = FloatingDrag(slop = 20f)
        drag.start()
        assertEquals(900f to false, drag.moved(880f, 20f, maxTop))
        assertFalse(drag.inZone)
        // to the bottom: in it at once, and staying
        assertEquals(maxTop to true, drag.moved(900f, 120f, maxTop))
        assertEquals(maxTop to true, drag.moved(maxTop, 50f, maxTop))
    }

    @Test
    fun comingBackUpGivesThePushBackThenLeavesTheZone() {
        val drag = FloatingDrag(slop = 20f)
        drag.start()
        drag.move(950f, 100f, maxTop)
        assertTrue(drag.inZone)
        // 50 pushed past: up 30, still at the bottom, still in it
        assertEquals(maxTop to true, drag.moved(maxTop, -30f, maxTop))
        // up 50 more: 20 of push left, then 30 up (past the slop), out of it
        assertEquals(970f to false, drag.moved(maxTop, -50f, maxTop))
        assertFalse(drag.inZone)
        // back down: in it again
        assertEquals(maxTop to true, drag.moved(970f, 30f, maxTop))
    }

    @Test
    fun atTheBottomFromTheStartOnlyAPushTakesItIn() {
        // it first floats at the bottom: a drag along it, a little down, does not dock
        val drag = FloatingDrag(slop = 20f)
        drag.start()
        assertEquals(maxTop to false, drag.moved(maxTop, 5f, maxTop))
        drag.move(maxTop, 0f, maxTop)
        assertFalse(drag.inZone)
        // pushed on down by the slop: in
        assertEquals(maxTop to true, drag.moved(maxTop, 15f, maxTop))
        // a new drag forgets the last one's push
        drag.start()
        assertFalse(drag.inZone)
        assertEquals(maxTop to false, drag.moved(maxTop, 10f, maxTop))
        // a fraction off the bottom is still at it
        drag.start()
        assertEquals(999.5f to false, drag.moved(999.5f, 0f, maxTop))
        assertFalse(drag.inZone)
    }

    @Test
    fun aWobbleUpAlongTheBottomDoesNotDock() {
        val drag = FloatingDrag(slop = 20f)
        drag.start()
        assertEquals(998f to false, drag.moved(maxTop, -2f, maxTop))
        assertEquals(maxTop to false, drag.moved(998f, 2f, maxTop))
        assertFalse(drag.inZone)
        // up past the slop and back: brought down to it, in
        drag.move(maxTop, -25f, maxTop)
        assertEquals(maxTop to true, drag.moved(975f, 25f, maxTop))
    }

    @Test
    fun aKeyboardAsTallAsTheRoomIsNotDockedByAJiggle() {
        val drag = FloatingDrag(slop = 20f)
        drag.start()
        assertEquals(0f to false, drag.moved(0f, -10f, 0f))
        assertEquals(0f to false, drag.moved(0f, 10f, 0f))
        assertFalse(drag.inZone)
    }

    @Test
    fun neverAboveTheTop() {
        val drag = FloatingDrag(slop = 20f)
        drag.start()
        assertEquals(0f to false, drag.moved(10f, -50f, maxTop))
    }

    // the top, and whether the drag is in the zone after the move: its contract; FloatingPlacer
    // tells the move that takes it in (FloatingPlacerTest)
    private fun FloatingDrag.moved(top: Float, dy: Float, maxTop: Float): Pair<Float, Boolean> = move(top, dy, maxTop) to inZone
}
