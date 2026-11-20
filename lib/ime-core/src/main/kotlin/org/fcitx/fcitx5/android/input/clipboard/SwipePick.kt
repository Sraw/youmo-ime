/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import kotlin.math.abs

/** A sweep over 「切词」's pieces: [anchor] to the finger is the range; from an unpicked piece it adds in text order whichever way (picks are commit order), from a picked one it drops. */
class SwipePick<T>(private val all: List<T>, private val before: List<T>, private val anchor: Int) {

    // looked up on every move, for every piece in the range
    private val was = before.toHashSet()

    private val adding = all[anchor] !in was

    /** [index] clamped to [all]. */
    fun to(index: Int): List<T> {
        val at = index.coerceIn(all.indices)
        val range = all.subList(minOf(anchor, at), maxOf(anchor, at) + 1)
        return if (adding) before + range.filter { it !in was } else before - range.toSet()
    }

    companion object {
        /** Null under [slop]; true sideways (a sweep), false vertical (a scroll). */
        fun startsSwipe(dx: Float, dy: Float, slop: Float): Boolean? =
            if (abs(dx) <= slop && abs(dy) <= slop) null else abs(dx) > abs(dy)

        /** −1, 1, 0: only in the edge [band] and moved toward it past [slop] from the farthest the other way, so a sweep along an edge row does not scroll. */
        fun scrollDirection(y: Float, minY: Float, maxY: Float, height: Int, band: Int, slop: Float): Int = when {
            y < band && y < maxY - slop -> -1
            y > height - band && y > minY + slop -> 1
            else -> 0
        }
    }
}
