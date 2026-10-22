/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DayCounts]: an undo's less than none, a merge's days out of order, an older build's counts past a short. */
class DayCountsTest {
    @Test
    fun aDayBeforeTheLastIsPutInOrder() {
        val c = DayCounts.of(3 to 1, 7 to 2)
        c.add(5, 4)
        c.add(1, 1)
        // past the first arrays' two
        assertEquals("[1:1,3:1,5:4,7:2]", c.toString())
        assertEquals(7, c.lastDay)
    }

    @Test
    fun aDayTakenBackToNoneIsDroppedAndNeverBelowNone() {
        val c = DayCounts.of(3 to 1, 5 to 2)
        c.add(3, -1)
        assertEquals("[5:2]", c.toString())
        c.add(5, -9)
        assertTrue(c.isEmpty())
        assertEquals(Int.MIN_VALUE, c.lastDay)
        // less than none of a day not counted: nothing
        c.add(4, -1)
        assertTrue(c.isEmpty())
    }

    @Test
    fun aCountStopsAtAShortsMost() {
        val c = DayCounts.of(1 to 40_000)
        assertEquals(Short.MAX_VALUE.toInt(), c.count(0))
        c.add(1, 5)
        assertEquals(Short.MAX_VALUE.toInt(), c.count(0))
    }

    @Test
    fun dropBeforeGivesWhatItDroppedAndSumFromWhatIsLeft() {
        val c = DayCounts.of(1 to 2, 2 to 3, 4 to 5)
        assertEquals(8, c.sumFrom(2))
        assertEquals(0, c.sumFrom(5))
        assertEquals(5, c.dropBefore(3))
        assertEquals("[4:5]", c.toString())
        assertEquals(0, c.dropBefore(3))
        assertEquals(5, c.sumFrom(0))
    }
}
