/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.input.UndoChipPlacement

/**
 * 编辑菜单: five cells in a fixed order, 粘贴 over the finger's key, learnt without looking; placed where notices go, not under the finger.
 * The input methods' menu ([InputMethodMenu]) is placed the same way, by its own number of cells and start.
 */
object EditMenuLayout {

    val CELLS = listOf(KeyCommand.SelectAll, KeyCommand.Cut, KeyCommand.Copy, KeyCommand.Paste, KeyCommand.Undo)

    val START = CELLS.indexOf(KeyCommand.Paste)

    /** A password's field gives nothing to copy or cut. */
    fun disabled(command: KeyCommand, password: Boolean): Boolean = password && command.readsField

    /** A row of [count] cells, [start]'s centred over [keyCenter], kept within [outerLeft]..[outerRight] where it fits. */
    fun rowLeft(keyCenter: Int, cell: Int, outerLeft: Int, outerRight: Int, count: Int, start: Int): Int {
        val width = cell * count
        val left = keyCenter - cell / 2 - cell * start
        // too narrow a window to fit: its left edge kept, the row's right end cut off
        return left.coerceAtMost(outerRight - width).coerceAtLeast(outerLeft)
    }

    /** Window pixels. */
    data class Spot(val left: Int, val top: Int)

    /** Where [notice] goes ([UndoChipPlacement]), [start] over [keyCenter]; above [roomTop] nothing shows, so over the bar at [barMiddle]. Picking is by finger travel, not place. */
    fun place(
        notice: UndoChipPlacement.Place,
        keyCenter: Int,
        cell: Int,
        outerLeft: Int,
        outerRight: Int,
        roomTop: Int,
        barMiddle: Int,
        count: Int,
        start: Int,
    ): Spot {
        val across = notice.slide == UndoChipPlacement.Slide.Up || notice.slide == UndoChipPlacement.Slide.Down
        val left = if (across) rowLeft(keyCenter, cell, outerLeft, outerRight, count, start) else notice.left
        // over the bar, but not off the view's top either (a bar shorter than a cell, high up)
        val top = if (notice.top >= roomTop) notice.top else (barMiddle - cell / 2).coerceAtLeast(roomTop)
        return Spot(left, top)
    }
}
