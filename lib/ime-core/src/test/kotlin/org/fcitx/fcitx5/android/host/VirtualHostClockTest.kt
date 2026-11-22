/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualHostClockTest {

    @Test
    fun timersRunAtTheirTimeInOrderAndOnlyThoseDue() {
        val clock = VirtualHostClock()
        val seen = mutableListOf<Pair<Int, Long>>()
        clock.schedule(10) { seen += 1 to clock.uptimeMillis() }
        clock.schedule(5) { seen += 2 to clock.uptimeMillis() }
        clock.schedule(10) { seen += 3 to clock.uptimeMillis() }
        clock.schedule(20) { seen += 4 to clock.uptimeMillis() }
        clock.advance(10)
        assertEquals(listOf(2 to 5L, 1 to 10L, 3 to 10L), seen)
        assertEquals(1, clock.pending)
    }
}
