/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagerTest {

    @Test
    fun aPageIsTurnedToOnlyWhereItHasACandidate() {
        val pager = Pager(5)
        assertTrue(pager.turn(1, 6))
        assertEquals(1, pager.page)
        assertEquals(5, pager.from)
        assertTrue(pager.hasPrevious)
        assertFalse(pager.hasNext(10))
        assertTrue(pager.hasNext(11))
        assertFalse(pager.turn(2, 10))
        assertFalse(pager.turn(-1, 10))
        assertEquals(1, pager.page)
        assertFalse(pager.turn(3) { it < 15 })
        assertTrue(pager.turn(2) { it < 15 })
    }

    @Test
    fun aKeyOnThePageIsItsCandidateAndOnePastItNone() {
        val pager = Pager(5)
        pager.turn(1, 10)
        assertEquals(7, pager.at(2))
        assertEquals(-1, pager.at(5))
        assertEquals(-1, pager.at(-1))
        assertEquals(listOf(5, 6), pager.of((0 until 7).toList()))
        assertEquals(emptyList<Int>(), pager.of(listOf(1)))
    }

    @Test
    fun thePageShownIsKeptWhileItHasCandidates() {
        val pager = Pager(5)
        pager.keep(1, 6)
        assertEquals(1, pager.page)
        pager.keep(1, 5)
        assertEquals(0, pager.page)
        pager.turn(1, 6)
        pager.reset()
        assertEquals(0, pager.page)
        assertFalse(pager.hasPrevious)
    }
}
