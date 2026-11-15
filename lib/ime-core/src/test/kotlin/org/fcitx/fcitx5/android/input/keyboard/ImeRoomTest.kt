/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Test

/** 061-landscape-height §5.2: the window less what the input view keeps, each background, each side of API 35. */
class ImeRoomTest {

    // a common phone on its side at 1x: 393 high, a 24 dp status bar, the input method's 48 dp bar under gestures
    private val h = 393
    private val status = 24
    private val nav = 48

    @Test
    fun theRoomUnderEachBackground() = assertRows(
        "全部 below 35: the window is the display less the status bar, the view keeps the bar" to {
            assertEquals(h - status - nav, ImeRoom.available(h, status, nav, api35 = false, fullBackground = true, flush = false))
        },
        "无/仅颜色 below 35: the system fits the window above the bar, the view keeps nothing" to {
            assertEquals(h - status - nav, ImeRoom.available(h, status, nav, api35 = false, fullBackground = false, flush = false))
        },
        "全部 from 35: edge to edge, the view keeps the bar" to {
            assertEquals(h - status - nav, ImeRoom.available(h, status, nav, api35 = true, fullBackground = true, flush = false))
        },
        "无/仅颜色 from 35: edge to edge and nothing kept, the bar's room is the keyboard's" to {
            assertEquals(h - status, ImeRoom.available(h, status, nav, api35 = true, fullBackground = false, flush = false))
        },
    )

    @Test
    @Uat("LS-50")
    fun flushAndFullScreenAndASideBar() = assertRows(
        "贴底 under 全部: the view keeps nothing" to {
            assertEquals(h - status, ImeRoom.available(h, status, nav, api35 = true, fullBackground = true, flush = true))
            assertEquals(h - status, ImeRoom.available(h, status, nav, api35 = false, fullBackground = true, flush = true))
        },
        "贴底 under 无 below 35: the system's fitting is no setting of ours, the bar still out" to {
            assertEquals(h - status - nav, ImeRoom.available(h, status, nav, api35 = false, fullBackground = false, flush = true))
        },
        "an app full screen hides the status bar" to {
            assertEquals(h - nav, ImeRoom.available(h, status, nav, api35 = true, fullBackground = true, flush = false, statusShown = false))
        },
        "three buttons turned on a phone: the bar is at a side, none under the keyboard" to {
            assertEquals(h - status, ImeRoom.available(h, status, 0, api35 = false, fullBackground = false, flush = false))
            assertEquals(h - status, ImeRoom.available(h, status, 0, api35 = true, fullBackground = true, flush = false))
        },
    )
}
