/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.platform

import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import org.fcitx.fcitx5.android.host.VirtualHostClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ClockTest {
    // what the date phrases are filled in with: the clock's own time, in its zone, never the system's
    @Test
    fun aClocksCalendarIsItsTimeInItsZone() {
        val virtual = VirtualHostClock(epochMillis = 1_700_000_000_000L).apply { advance(90_000) }
        // as the engines read it: through the port, its default calendar
        val clock: Clock = virtual
        val calendar = clock.calendar()
        assertEquals(clock.now(), calendar.timeInMillis)
        assertEquals(0, calendar.timeZone.getOffset(clock.now()))
        assertEquals(90_000L, clock.uptimeMillis())
    }

    @Test
    fun theDevicesClockIsTheSystems() {
        val before = System.currentTimeMillis()
        val now = JvmClock.now()
        assertTrue(now >= before && now <= System.currentTimeMillis())
        assertEquals(Calendar.getInstance().timeZone, JvmClock.calendar().timeZone)
        val up = JvmClock.uptimeMillis()
        assertTrue(JvmClock.uptimeMillis() >= up)
    }
}
