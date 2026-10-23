/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * The keyboard area (preedit line, bar, keys and bottom padding) never takes more than
 * [MAX_FRACTION] of the screen height. The levels are percentages of the screen, so on a short
 * landscape screen 很高 plus the bar would otherwise leave the field almost no room (UAT LS-27:
 * 568 of ~727 px). In portrait the levels stay far below the cap.
 *
 * [barsHeight] is everything docked above the keys: the candidate bar and the code row over it (the
 * preedit line), both always laid out at their fixed heights, so no row pushes the area past the cap.
 */
object KeyboardHeightCap {
    const val MAX_FRACTION = 0.70

    /**
     * The keys' height: [requested] unless the whole area would pass the cap; never below 0.
     * [handleHeight] is a docked drag handle (始终显示悬浮条): added to the keyboard, the keys
     * keeping their height, but counted in the cap, which wins where it binds (the user, 2026-10-22).
     */
    fun keysHeight(requested: Int, screenHeight: Int, barsHeight: Int, bottomPadding: Int, handleHeight: Int = 0): Int {
        val room = (screenHeight * MAX_FRACTION).toInt() - barsHeight - handleHeight - bottomPadding
        return requested.coerceAtMost(room).coerceAtLeast(0)
    }
}
