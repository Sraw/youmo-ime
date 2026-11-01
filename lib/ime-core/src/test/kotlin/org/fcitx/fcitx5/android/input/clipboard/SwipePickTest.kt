/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipePickTest {

    private val all = listOf("a", "b", "c", "d", "e")

    @Test
    fun aSweepAddsOrDropsTheRangeInTextOrder() {
        class Row(val why: String, val before: List<String>, val anchor: Int, val moves: List<Int>, val picks: List<String>)
        assertRows(
            listOf(
                Row("forward adds the range", emptyList(), 1, listOf(3), listOf("b", "c", "d")),
                Row("backward keeps text order", emptyList(), 3, listOf(1), listOf("b", "c", "d")),
                Row("from a picked piece drops the range", listOf("e", "b", "c"), 1, listOf(2), listOf("e")),
                Row("shrinking back restores what was before", listOf("d"), 0, listOf(4, 1), listOf("d", "a", "b")),
                Row("dropping, shrinking back restores", listOf("a", "b", "c"), 0, listOf(2, 0), listOf("b", "c")),
                Row("earlier picks keep their place and order before the swept", listOf("e", "a"), 1, listOf(2), listOf("e", "a", "b", "c")),
                Row("a picked piece inside an adding range keeps its place, once", listOf("c"), 1, listOf(3), listOf("c", "b", "d")),
                Row("past the end clamped", emptyList(), 3, listOf(9), listOf("d", "e")),
                Row("before the start clamped", emptyList(), 1, listOf(-1), listOf("a", "b")),
            ),
            { it.why },
        ) { row ->
            val swipe = SwipePick(all, row.before, row.anchor)
            assertEquals(row.picks, row.moves.map(swipe::to).last())
        }
    }

    @Test
    fun aSidewaysMoveStartsASweep() {
        class Row(val why: String, val dx: Float, val dy: Float, val starts: Boolean?)
        assertRows(
            listOf(
                Row("sideways past slop", -12f, 5f, true),
                Row("vertical past slop scrolls", 5f, 12f, false),
                Row("under slop not decided", 8f, -8f, null),
            ),
            { it.why },
        ) { assertEquals(it.starts, SwipePick.startsSwipe(it.dx, it.dy, 8f)) }
    }

    @Test
    fun theGridScrollsOnlyWhenDraggedIntoAnEdge() {
        class Row(val why: String, val y: Float, val minY: Float, val maxY: Float, val dir: Int)
        assertRows(
            listOf(
                Row("sideways along the top row: no scroll", 20f, 20f, 22f, 0),
                Row("sideways along the bottom row: no scroll", 480f, 478f, 480f, 0),
                Row("dragged up into the top band", 20f, 20f, 200f, -1),
                Row("dragged down into the bottom band", 480f, 200f, 480f, 1),
                Row("in the middle", 250f, 100f, 250f, 0),
                Row("toward the top but not in its band", 100f, 100f, 300f, 0),
                Row("up a little first, then down into the band: from the highest point", 480f, 150f, 200f, 1),
                Row("down a little first, then up into the band: from the lowest point", 20f, 20f, 260f, -1),
                Row("started in the band, dragged further toward the edge past slop", 11f, 11f, 20f, -1),
                Row("started in the band, exactly slop toward the edge", 12f, 12f, 20f, 0),
                Row("bottom band, exactly slop toward the edge", 488f, 480f, 488f, 0),
                Row("bottom band, past slop toward the edge", 489f, 480f, 489f, 1),
            ),
            { it.why },
        ) { assertEquals(it.dir, SwipePick.scrollDirection(it.y, it.minY, it.maxY, 500, 32, 8f)) }
    }
}
