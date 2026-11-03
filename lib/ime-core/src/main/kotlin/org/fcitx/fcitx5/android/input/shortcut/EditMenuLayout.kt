/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.input.UndoChipPlacement

/**
 * The edit menu (编辑菜单) a swipe opens: five cells always in one order, 粘贴 over the finger's key,
 * so a swipe up and lift is a paste, a cell left a copy, a cell right an undo, learnt without
 * looking. Near the edge of the screen the row moves in; the cell over the finger stays. It goes
 * where the keyboard's notices go ([place]), not just over the key, where the finger hid it.
 */
object EditMenuLayout {

    val CELLS = listOf(KeyCommand.SelectAll, KeyCommand.Cut, KeyCommand.Copy, KeyCommand.Paste, KeyCommand.Undo)

    /** The cell over the finger as the menu opens: 粘贴. */
    val START = CELLS.indexOf(KeyCommand.Paste)

    /** Whether [command]'s cell is greyed: a password's field gives nothing to copy or cut. */
    fun disabled(command: KeyCommand, password: Boolean): Boolean = password && command.readsField

    /**
     * The row's left edge, in the coordinates of the bounds: [START]'s cell centred over [keyCenter],
     * moved in to stay within [outerLeft]..[outerRight] where it fits ([cell] wide each).
     */
    fun rowLeft(keyCenter: Int, cell: Int, outerLeft: Int, outerRight: Int): Int {
        val width = cell * CELLS.size
        val left = keyCenter - cell / 2 - cell * START
        // too narrow a window to fit: its left edge kept, the row's right end cut off
        return left.coerceAtMost(outerRight - width).coerceAtLeast(outerLeft)
    }

    /** The row's top-left corner, in window pixels. */
    data class Spot(val left: Int, val top: Int)

    /**
     * Where the row goes: up where a notice its size goes ([notice], [UndoChipPlacement]: above the
     * keyboard docked, on its side toward the screen's middle floating), out from under the finger
     * (the user, 0.5.1's test build: over the bottom row the finger covered it). Above or below
     * the keyboard, across as [rowLeft] has it, 粘贴 over the key ([keyCenter]); beside a floating
     * keyboard, where the notice goes. A place above [roomTop] (the view it is drawn in, where
     * nothing of it would show) is no place: over the bar instead, its middle at [barMiddle].
     * Picking stays by how far the finger moves ([org.fcitx.fcitx5.android.input.popup.PopupFocus]),
     * not where it is: the row's height above the key changes nothing.
     */
    fun place(
        notice: UndoChipPlacement.Place,
        keyCenter: Int,
        cell: Int,
        outerLeft: Int,
        outerRight: Int,
        roomTop: Int,
        barMiddle: Int,
    ): Spot {
        val across = notice.slide == UndoChipPlacement.Slide.Up || notice.slide == UndoChipPlacement.Slide.Down
        val left = if (across) rowLeft(keyCenter, cell, outerLeft, outerRight) else notice.left
        // over the bar, but not off the view's top either (a bar shorter than a cell, high up)
        val top = if (notice.top >= roomTop) notice.top else (barMiddle - cell / 2).coerceAtLeast(roomTop)
        return Spot(left, top)
    }
}
