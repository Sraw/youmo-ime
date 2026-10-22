/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentItemsTest {

    @Test
    fun theNewestFirstTheOldestGonePastTheLimit() {
        val recent = RecentItems(3)
        listOf("a", "b", "c", "d").forEachIndexed { i, s -> recent.use(s, i.toLong(), habits = true) }
        assertEquals(listOf("d", "c", "b"), recent.shown(habits = true))
        // used again, it comes first, with its new time
        recent.use("b", 9, habits = true)
        assertEquals(listOf("b", "d", "c"), recent.shown(habits = true))
        assertEquals(listOf("c", "d", "b"), recent.stored)
        assertEquals(listOf(2L, 3L, 9L), recent.storedTimes)
    }

    // U-67, U-93: while the habits are off none is shown nor kept; on again, those from before are back
    @Test
    fun withTheHabitsOffNoneIsShownNorKept() {
        val recent = RecentItems(5)
        recent.use("a", 1, habits = true)
        assertFalse(recent.use("b", 2, habits = false))
        assertEquals(emptyList<String>(), recent.shown(habits = false))
        assertEquals(listOf("a"), recent.shown(habits = true))
    }

    // U-88, U-86: cleared with the rest of what was learned, from a time on or all
    @Test
    fun thoseUsedSinceATimeAreCleared() {
        val recent = RecentItems(5)
        listOf("a" to 10L, "b" to 20L, "c" to 30L).forEach { (s, at) -> recent.use(s, at, habits = true) }
        assertTrue(recent.clearSince(20))
        assertEquals(listOf("a"), recent.shown(habits = true))
        assertFalse(recent.clearSince(20))
        assertTrue(recent.clearSince(0))
        assertEquals(emptyList<String>(), recent.shown(habits = true))
    }

    // an older build kept no times: as of long ago, cleared only with all; more than the limit, the newest
    @Test
    fun whatAnOlderBuildKeptIsReadAsOfLongAgo() {
        val recent = RecentItems(2)
        recent.load(listOf("a", "b", "c"), emptyList())
        assertEquals(listOf("c", "b"), recent.shown(habits = true))
        assertFalse(recent.clearSince(1))
        recent.load(listOf("a", "b"), listOf(5L, 7L))
        recent.clearSince(6)
        assertEquals(listOf("a"), recent.stored)
        assertEquals(listOf(5L), recent.storedTimes)
        // the items rewritten by an older build, the times not: none trusted
        recent.load(listOf("x", "a", "b"), listOf(5L, 7L))
        assertFalse(recent.clearSince(1))
        assertEquals(listOf(0L, 0L), recent.storedTimes)
    }
}
