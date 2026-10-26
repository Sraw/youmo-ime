/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualHostClockTest {

    @Test
    fun aTimerDueWhileAnotherSpendsRunsLateNotBackInTime() {
        // HostClock.uptimeMillis is monotonic: learning's now() and FieldPolicy read it in replays (F4008)
        val clock = VirtualHostClock()
        val seen = mutableListOf<Long>()
        clock.schedule(10) { clock.spend(1); seen += clock.uptimeMillis() }
        clock.schedule(10) { seen += clock.uptimeMillis() }
        clock.advance(10)
        assertEquals(listOf(11L, 11L), seen)
        assertEquals(0, clock.pending)
    }
}
