/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import kotlin.math.abs

/**
 * A finger swept across 「切词」's pieces, as in a photo grid: [all] from the [anchor] touched first to the one
 * under the finger, in reading order, is the range. Starting on a piece not in [before] adds the range after
 * those picked before, in text order whichever way the finger goes (the picks are the commit order, and a
 * right-to-left sweep should not reverse the words); starting on a picked one drops it. Pieces the range
 * leaves again are as they were in [before].
 */
class SwipePick<T>(private val all: List<T>, private val before: List<T>, private val anchor: Int) {

    // looked up on every move, for every piece in the range
    private val was = before.toHashSet()

    private val adding = all[anchor] !in was

    /** The picks with the finger over [index] (clamped to [all]). */
    fun to(index: Int): List<T> {
        val at = index.coerceIn(all.indices)
        val range = all.subList(minOf(anchor, at), maxOf(anchor, at) + 1)
        return if (adding) before + range.filter { it !in was } else before - range.toSet()
    }

    companion object {
        /** Null under [slop]; true when the move is sideways (a sweep), false when vertical (a scroll). */
        fun startsSwipe(dx: Float, dy: Float, slop: Float): Boolean? =
            if (abs(dx) <= slop && abs(dy) <= slop) null else abs(dx) > abs(dy)

        /**
         * The grid's auto-scroll with the finger at [y] in a list [height] tall: −1 up, 1 down, 0 none. Only in
         * the [band] at an edge *and* moved toward it past [slop] from the farthest it went the other way since
         * the finger came down ([minY] and [maxY], both the down y at first): a sideways sweep along the top or bottom
         * row visible should not scroll in rows never pointed at, and one that went up a little first still
         * scrolls down when dragged there.
         */
        fun scrollDirection(y: Float, minY: Float, maxY: Float, height: Int, band: Int, slop: Float): Int = when {
            y < band && y < maxY - slop -> -1
            y > height - band && y > minY + slop -> 1
            else -> 0
        }
    }
}
