/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardManagerTest {

    @Test
    fun theNewestByWhenLastCopiedAreKept() {
        // ids 1 to 4 copied at 100, 200, 300, 500, and 2 again at 450: 2, 3 and 4 stay
        assertEquals(300L, keptSince(listOf(100L, 450L, 300L, 500L), 3))
    }

    @Test
    fun noneIsKeptWithNoRoomForOne() {
        assertNull(keptSince(listOf(100L, 200L), 0))
    }
}
