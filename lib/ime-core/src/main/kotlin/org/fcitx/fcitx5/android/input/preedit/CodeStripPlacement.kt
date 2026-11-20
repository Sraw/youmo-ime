/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import org.fcitx.fcitx5.android.input.UndoChipPlacement.Box

/** The floating code strip: outside the board's top-left, left-aligned with it; no room above, below; neither, over the board's top. Never wider than the board. */
object CodeStripPlacement {

    /** [below]: hanging under the board, its rounded corners then the bottom two. */
    data class Place(val x: Int, val y: Int, val below: Boolean)

    /** [room]: the screen less the status bar. */
    fun place(board: Box, room: Box, width: Int, height: Int): Place {
        val below = board.top - height < room.top && board.bottom + height <= room.bottom
        // a keyboard as tall as the room: over its top keys rather than under the status bar or off the screen
        val y = if (below) board.bottom else (board.top - height).coerceAtLeast(room.top)
        val x = board.left.coerceAtMost(room.right - width).coerceAtLeast(room.left)
        return Place(x, y, below)
    }
}
