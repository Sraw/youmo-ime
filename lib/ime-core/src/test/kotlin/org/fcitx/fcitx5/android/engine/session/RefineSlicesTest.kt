/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RefineSlices]: a slice at a time while the user pauses, as handwriting and the tables weigh. */
class RefineSlicesTest {

    private val order = listOf("甲", "乙", "丙")
    private val placed = ArrayList<Int>()

    private fun RefineSlices<List<String>>.slice() = slice("", { it }, { List(it.size) { 0f } }) { _, picked -> placed += picked }

    @Test
    fun aRefinerThatNeedsMoreSlicesGoesOnTillItPicks() {
        var asked = 0
        val slices = RefineSlices<List<String>>(SentenceRefiner { _, _, _, budget ->
            assertEquals(PinyinSession.REFINE_BUDGET, budget)
            if (++asked < 3) null else 2
        })
        slices.start(order, order.size)
        repeat(2) {
            slices.slice()
            assertTrue(slices.refines)
        }
        slices.slice()
        assertFalse(slices.refines)
        assertEquals(listOf(2), placed)
        // nothing left: no more asked
        slices.slice()
        assertEquals(3, asked)
    }

    @Test
    fun aRefinerThatNeverPicksIsGivenUpOnAfterItsSlices() {
        var asked = 0
        val slices = RefineSlices<List<String>>(SentenceRefiner { _, _, _, _ -> asked++; null })
        slices.start(order, order.size)
        repeat(SentenceRefiner.SLICES) { slices.slice() }
        assertTrue(slices.refines)
        slices.slice()
        assertFalse(slices.refines)
        assertEquals(SentenceRefiner.SLICES, asked)
        assertTrue(placed.isEmpty())
        // started again: its slices anew
        slices.start(order, order.size)
        slices.slice()
        assertEquals(SentenceRefiner.SLICES + 1, asked)
    }

    @Test
    fun nothingToSayPlacesNothing() {
        val slices = RefineSlices<List<String>>(SentenceRefiner { _, _, _, _ -> SentenceRefiner.NONE })
        slices.start(order, order.size)
        slices.slice()
        assertFalse(slices.refines)
        assertTrue(placed.isEmpty())
    }

    @Test
    fun oneReadingNoRefinerOrADropWeighNothing() {
        val refiner = SentenceRefiner { _, _, _, _ -> 1 }
        val one = RefineSlices<List<String>>(refiner).apply { start(listOf("甲"), 1) }
        assertFalse(one.refines)
        assertFalse(RefineSlices<List<String>>(null).apply { start(order, order.size) }.refines)
        val dropped = RefineSlices<List<String>>(refiner).apply { start(order, order.size) }
        dropped.drop()
        dropped.slice()
        assertTrue(placed.isEmpty())
    }
}
