/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Test

/** The window less what the input view keeps. */
class ImeRoomTest {

    // a common phone on its side at 1x: 393 high, a 24 dp status bar, the input method's 48 dp bar under gestures
    private val h = 393
    private val status = 24
    private val nav = 48

    @Test
    fun theWindowLessTheStatusBarTheViewKeepingTheBar() {
        assertEquals(h - status - nav, ImeRoom.available(h, status, nav, flush = false))
    }

    @Test
    @Uat("LS-50")
    fun flushAndFullScreenAndASideBar() = assertRows(
        "贴底: the view keeps nothing" to {
            assertEquals(h - status, ImeRoom.available(h, status, nav, flush = true))
        },
        "an app full screen hides the status bar" to {
            assertEquals(h - nav, ImeRoom.available(h, status, nav, flush = false, statusShown = false))
        },
        "three buttons turned on a phone: the bar is at a side, none under the keyboard" to {
            assertEquals(h - status, ImeRoom.available(h, status, 0, flush = false))
        },
    )
}
