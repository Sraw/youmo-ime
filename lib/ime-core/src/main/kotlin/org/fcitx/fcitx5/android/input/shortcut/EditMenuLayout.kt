/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

/**
 * The edit menu (编辑菜单) a swipe opens over its key: five cells always in one order, 粘贴 over the
 * finger, so a swipe up and lift is a paste, a cell left a copy, a cell right an undo, learnt
 * without looking. Near the edge of the screen the row moves in; the cell over the finger stays.
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
}
