/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Which key of a long press's popup the finger picks: by how far it has moved since the popup
 * opened, [stepX] across and [stepY] up for each key, not by where it is. A key drawn as wide as
 * the finger took a whole key's travel to move one, and nine keys' keys, wider than the popup's,
 * left the finger far from the key it picked.
 *
 * Past a row's ends the finger stays on its first or last key, above the top row on the top row;
 * on an empty slot, the nearest key in that row. Only [cancelBelow] down, off the key pressed,
 * picks nothing: the popup's keys never end the long press however far the finger goes over them.
 *
 * @param rows each row's slots from the bottom: the key's index in each, -1 for an empty slot
 * @param startRow the row of the key in focus at first
 * @param startColumn its column
 * @param cancelAt where, in [moveTo]'s coordinates, the key pressed ends at the bottom: [cancelBelow]
 *   is then measured from the first point [moveTo] is given, not taken as is. A press near the
 *   key's bottom edge was otherwise cancelled only a whole key's height below it.
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

    /** The key the finger at [x], [y] picks, by how far it is from the first point given here. */
    fun moveTo(x: Float, y: Float): Int {
        if (x0.isNaN()) {
            x0 = x
            y0 = y
            // a finger that drifted past the edge while the long press waited is not cancelled by
            // the popup's first move: only by going on down from there, past a key's HYSTERESIS, as a
            // finger sliding across shakes a few pixels down too (F3217)
            if (!cancelAt.isNaN()) cancelBelow = (cancelAt - y).coerceAtLeast(HYSTERESIS * stepY)
        }
        return move(x - x0, y - y0)
    }

    /** The key [dx], [dy] from where the finger was when the popup opened picks, or [CANCELLED]. */
    fun move(dx: Float, dy: Float): Int {
        if (dy > cancelBelow) return CANCELLED
        row = settle(row, startRow - dy / stepY, rows.size)
        val slots = rows[row]
        column = settle(column, startColumn + dx / stepX, slots.size)
        return nearest(slots, column)
    }

    /** The key in focus as [move] last left it, the first one before any. */
    val focused: Int get() = nearest(rows[row], column)

    // the slot [at] falls in, kept at [current] until it is past the middle by HYSTERESIS: a finger
    // resting on a line between two keys would switch between them, buzzing at each
    private fun settle(current: Int, at: Float, size: Int): Int {
        if (abs(at - current) <= 0.5f + HYSTERESIS) return current.coerceIn(0, size - 1)
        return at.roundToInt().coerceIn(0, size - 1)
    }

    // an empty slot's nearest key in the row, the one toward the key in focus at first on a tie
    private fun nearest(slots: IntArray, at: Int): Int {
        if (slots[at] >= 0) return slots[at]
        val best = slots.indices.filter { slots[it] >= 0 }
            .minWithOrNull(compareBy<Int> { abs(it - at) }.thenBy { abs(it - startColumn) })
        return best?.let { slots[it] } ?: CANCELLED
    }

    companion object {
        const val CANCELLED = -1

        /** How far past the middle between two keys, in keys, the finger goes before the next. */
        const val HYSTERESIS = 0.15f

        /** How far the finger moves for the next key, in keys: less than one, a key's way. */
        const val STEP = 0.6f
    }
}
