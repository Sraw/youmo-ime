/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitGapTest {

    // keys 100 wide, rows 100 tall from y 0
    private fun row(y: Int, vararg lefts: Int) = lefts.flatMap { listOf(it.toFloat(), y * 100f, it + 100f, y * 100f + 100f) }

    // a split row of five and five, 200 between the halves (500..700), the row under it as split
    private val split = (row(0, 0, 100, 200, 300, 400, 700, 800, 900, 1000, 1100) + row(1, 0, 100, 200, 300, 400, 700, 800, 900, 1000, 1100)).toFloatArray()

    // a whole row, the one under it half a key in (as A is), ⇧ no letter
    private val whole = (row(0, 0, 100, 200, 300, 400, 500, 600, 700, 800, 900) + row(1, 50, 150, 250, 350, 450, 550, 650, 750, 850) +
        row(2, 150, 250, 350, 450, 550, 650, 750)).toFloatArray()

    private class Row(val why: String, val keys: FloatArray, val x: Float, val y: Float, val gap: Boolean)

    @Test
    fun whereTheGapIs() {
        val spaced = row(0, 0, 110, 220).toFloatArray()
        val narrowest = row(0, 0, 150).toFloatArray()
        val narrower = row(0, 0, 149).toFloatArray()
        val rows = listOf(
            Row("between the halves, first row", split, 600f, 50f, true),
            Row("just past the left half, second row", split, 501f, 150f, true),
            Row("just before the right half, the rows' bottom", split, 699f, 199f, true),
            Row("on a letter left of it", split, 450f, 50f, false),
            Row("on a letter right of it", split, 750f, 50f, false),
            Row("above the rows", split, 600f, -1f, false),
            Row("below the rows", split, 600f, 200f, false),
            Row("past the right end", split, 1250f, 50f, false),
            Row("before the left end", split, -10f, 50f, false),
            Row("no keys", FloatArray(0), 500f, 50f, false),
            Row("unsplit: the room at a row's start", whole, 20f, 150f, false),
            Row("unsplit: the room at a row's end", whole, 970f, 150f, false),
            Row("unsplit: shift under A, another row's letters left of it", whole, 120f, 250f, false),
            Row("unsplit: right of the last row", whole, 880f, 250f, false),
            Row("unsplit: on a letter", whole, 500f, 50f, false),
            Row("keys' own spacing", spaced, 105f, 50f, false),
            Row("the narrowest gap counts", narrowest, 120f, 50f, true),
            Row("a narrower space does not", narrower, 120f, 50f, false),
        )
        assertRows(rows, { it.why }) { assertEquals(it.gap, SplitGap.contains(it.keys, it.x, it.y, 100f)) }
    }
}
