/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The popup key picked by how far the finger moved since the popup opened, not where it is: wide keys took a whole
 * key's travel to move one. Past a row's ends it stays on the end key; only [cancelBelow] down picks nothing.
 * [rows]: each row's slots from the bottom, -1 empty. [cancelAt]: the pressed key's bottom in [moveTo]'s
 * coordinates, so a press near its bottom edge is not cancelled only a whole key below.
 */
class PopupFocus(
    private val rows: List<IntArray>,
    private val startRow: Int,
    private val startColumn: Int,
    private val stepX: Float,
    private val stepY: Float,
    private var cancelBelow: Float,
    private val cancelAt: Float = Float.NaN,
) {
    private var row = startRow
    private var column = startColumn

    private var x0 = Float.NaN
    private var y0 = Float.NaN

    /** By how far it is from the first point given here. */
    fun moveTo(x: Float, y: Float): Int {
        if (x0.isNaN()) {
            x0 = x
            y0 = y
            // a finger that drifted past the edge while the long press waited is cancelled only going on down from there
            if (!cancelAt.isNaN()) cancelBelow = (cancelAt - y).coerceAtLeast(HYSTERESIS * stepY)
        }
        return move(x - x0, y - y0)
    }

    /** Or [CANCELLED]. */
    fun move(dx: Float, dy: Float): Int {
        if (dy > cancelBelow) return CANCELLED
        row = settle(row, startRow - dy / stepY, rows.size, HYSTERESIS)
        val slots = rows[row]
        column = settle(column, startColumn + dx / stepX, slots.size, HYSTERESIS)
        return nearest(slots, column)
    }

    /** The first one before any. */
    val focused: Int get() = nearest(rows[row], column)

    // kept at [current] until past the middle by [slack]: a finger on the line between two keys would buzz between them
    private fun settle(current: Int, at: Float, size: Int, slack: Float): Int {
        if (abs(at - current) <= 0.5f + slack) return current.coerceIn(0, size - 1)
        return at.roundToInt().coerceIn(0, size - 1)
    }

    // the one toward the first focused on a tie
    private fun nearest(slots: IntArray, at: Int): Int {
        if (slots[at] >= 0) return slots[at]
        val best = slots.indices.filter { slots[it] >= 0 }
            .minWithOrNull(compareBy<Int> { abs(it - at) }.thenBy { abs(it - startColumn) })
        return best?.let { slots[it] } ?: CANCELLED
    }

    companion object {
        const val CANCELLED = -1

        /** The key's own first, then the rest as listed; the caller places them round it as the full keyboard does. */
        fun ownFirst(size: Int, own: Int): IntArray =
            (listOf(own) + (0 until size).filter { it != own }).toIntArray()

        const val HYSTERESIS = 0.15f

        /** Less than one: a key's way. */
        const val STEP = 0.6f

    }
}
