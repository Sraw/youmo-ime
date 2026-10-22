/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class LocalDaysTest {

    // the day is local already: west of UTC it once read as the day before
    @Test
    fun aDayIsItsOwnDateWestOfUtc() {
        val zone = TimeZone.getDefault()
        val locale = Locale.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            Locale.setDefault(Locale.US)
            // 20000 days after 1970-01-01
            assertEquals("Oct 4, 2024", formatLocalDay(20_000))
        } finally {
            TimeZone.setDefault(zone)
            Locale.setDefault(locale)
        }
    }
}
