/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.rare

/** 生僻字屏蔽's drag: cells from [start] to the finger show the opposite of [start]'s; left ones revert; non-cells ([isCell]) skipped. Pure. */
class DragRange(private val start: Int, private val held: (Int) -> Boolean, private val isCell: (Int) -> Boolean) {

    val target: Boolean = !held(start)

    /** Both ends in. */
    var range: IntRange = start..start
        private set

    /** The positions whose shown state changed, to redraw. */
    fun moveTo(position: Int): List<Int> {
        val before = range
        range = minOf(start, position)..maxOf(start, position)
        val touched = minOf(before.first, range.first)..maxOf(before.last, range.last)
        return touched.filter { isCell(it) && (it in before) != (it in range) && held(it) != target }
    }

    fun shown(position: Int): Boolean = if (position in range && isCell(position)) target else held(position)

    /** Those in range not already [target]. */
    fun changes(): List<Int> = range.filter { isCell(it) && held(it) != target }
}
