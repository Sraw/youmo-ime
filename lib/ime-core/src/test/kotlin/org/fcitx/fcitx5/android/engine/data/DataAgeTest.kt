/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataAgeTest {
    private val day = 24L * 60 * 60 * 1000
    private val built = 1_790_000_000_000L

    @Test
    fun halfAYearIsStaleAndLessIsNot() {
        assertFalse(DataAge.isStale(built, built))
        assertFalse(DataAge.isStale(built, built + DataAge.STALE_DAYS * day))
        assertTrue(DataAge.isStale(built, built + DataAge.STALE_DAYS * day + 1))
    }

    @Test
    fun aClockBehindTheBuildIsNotStale() {
        assertFalse(DataAge.isStale(built, built - 400 * day))
    }
}
