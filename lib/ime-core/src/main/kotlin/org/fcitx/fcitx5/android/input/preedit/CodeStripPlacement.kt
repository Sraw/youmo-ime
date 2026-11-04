/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import org.fcitx.fcitx5.android.input.UndoChipPlacement.Box

/**
 * Where the floating code strip (编码行 浮在键盘上方) goes, in the window's pixels: outside the
 * board's top-left corner, left-aligned with the board as drawn (docked, one-handed at a side,
 * floating anywhere), so it goes with the board. Where there is no room above it (a floating
 * keyboard dragged to the top), outside its bottom instead; room on neither side, over the board's
 * top within the room. Never wider than the board: the caller sizes [place]'s width to it.
 */
object CodeStripPlacement {

    /** The strip's top-left corner; [below] when it hangs under the board, its rounded corners then the bottom two. */
    data class Place(val x: Int, val y: Int, val below: Boolean)

    /** The strip [width] by [height] against [board], kept within [room] (the screen less the status bar). */
    fun place(board: Box, room: Box, width: Int, height: Int): Place {
        val below = board.top - height < room.top && board.bottom + height <= room.bottom
        // a keyboard as tall as the room: over its top keys rather than under the status bar or off the screen
        val y = if (below) board.bottom else (board.top - height).coerceAtLeast(room.top)
        val x = board.left.coerceAtMost(room.right - width).coerceAtLeast(room.left)
        return Place(x, y, below)
    }
}
