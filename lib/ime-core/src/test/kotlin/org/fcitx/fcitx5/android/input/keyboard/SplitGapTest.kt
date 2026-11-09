/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitGapTest {

    // keys 100 wide, rows 100 tall from y 0
    private fun row(y: Int, vararg lefts: Int) = lefts.flatMap { listOf(it.toFloat(), y * 100f, it + 100f, y * 100f + 100f) }

    // a split row of five and five, 200 between the halves (500..700), the row under it as split
    private val split = (row(0, 0, 100, 200, 300, 400, 700, 800, 900, 1000, 1100) + row(1, 0, 100, 200, 300, 400, 700, 800, 900, 1000, 1100)).toFloatArray()

    // a whole row, the one under it half a key in (as A is), ⇧ no letter
    private val whole = (row(0, 0, 100, 200, 300, 400, 500, 600, 700, 800, 900) + row(1, 50, 150, 250, 350, 450, 550, 650, 750, 850) +
        row(2, 150, 250, 350, 450, 550, 650, 750)).toFloatArray()

    @Test
    fun betweenTheHalvesInTheirRowsIsTheGap() {
        assertTrue(SplitGap.contains(split, 600f, 50f, 100f))
        assertTrue(SplitGap.contains(split, 501f, 150f, 100f))
        assertTrue(SplitGap.contains(split, 699f, 199f, 100f))
    }

    @Test
    fun onALetterAboveOrBelowTheRowsOrOutsideThemIsNot() {
        assertFalse(SplitGap.contains(split, 450f, 50f, 100f))
        assertFalse(SplitGap.contains(split, 750f, 50f, 100f))
        assertFalse(SplitGap.contains(split, 600f, -1f, 100f))
        assertFalse(SplitGap.contains(split, 600f, 200f, 100f))
        assertFalse(SplitGap.contains(split, 1250f, 50f, 100f))
        assertFalse(SplitGap.contains(split, -10f, 50f, 100f))
        assertFalse(SplitGap.contains(FloatArray(0), 500f, 50f, 100f))
    }

    @Test
    fun anUnsplitKeyboardHasNone() {
        // the room at a row's ends, ⇧ under A and S (another row's letters left of it), keys' own spacing
        for ((x, y) in listOf(20f to 150f, 970f to 150f, 120f to 250f, 880f to 250f, 500f to 50f)) {
            assertFalse("$x, $y", SplitGap.contains(whole, x, y, 100f))
        }
        val spaced = (row(0, 0, 110, 220)).toFloatArray()
        assertFalse(SplitGap.contains(spaced, 105f, 50f, 100f))
    }

    @Test
    fun theNarrowestGapCountsANarrowerSpaceDoesNot() {
        val gap = (row(0, 0, 150)).toFloatArray()
        assertTrue(SplitGap.contains(gap, 120f, 50f, 100f))
        val narrower = (row(0, 0, 149)).toFloatArray()
        assertFalse(SplitGap.contains(narrower, 120f, 50f, 100f))
    }
}
