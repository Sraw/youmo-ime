/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.rare

/**
 * 生僻字屏蔽's drag over the grid (dev/design/061-rare-char-block.md §6.2, a gallery's gesture): from
 * the cell at [start], every cell between it and the finger (in list order) shown as the opposite of
 * what [start] was; a cell the range leaves shows as it was again. Rows that are no cell ([isCell]:
 * a group's head, a letter's) are passed over, so a drag goes on across a group. Pure: the page
 * hands positions in and draws what [shown] says, applying [changes] once the finger lifts.
 */
class DragRange(private val start: Int, private val held: (Int) -> Boolean, private val isCell: (Int) -> Boolean) {

    /** What the cells in range become: the opposite of the start's. */
    val target: Boolean = !held(start)

    /** The positions from the start to the finger, both ends in. */
    var range: IntRange = start..start
        private set

    /** The finger now over [position]: the positions whose shown state changed, to redraw. */
    fun moveTo(position: Int): List<Int> {
        val before = range
        range = minOf(start, position)..maxOf(start, position)
        val touched = minOf(before.first, range.first)..maxOf(before.last, range.last)
        return touched.filter { isCell(it) && (it in before) != (it in range) && held(it) != target }
    }

    /** The state [position] shows while dragged. */
    fun shown(position: Int): Boolean = if (position in range && isCell(position)) target else held(position)

    /** The cells the drag changes, once the finger lifts: those in range not already [target]. */
    fun changes(): List<Int> = range.filter { isCell(it) && held(it) != target }
}
