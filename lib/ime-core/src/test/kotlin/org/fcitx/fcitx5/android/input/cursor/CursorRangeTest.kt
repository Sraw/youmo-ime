/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.cursor

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorRangeTest {

    @Test
    fun constructionKeepsTheBoundsAndOnlyUpdateNormalises() = assertRows(
        "defaults to collapsed at zero" to {
            assertTrue(CursorRange().rangeEquals(0, 0))
            assertTrue(CursorRange().isEmpty())
        },
        "the constructor stores the bounds verbatim" to {
            val r = CursorRange(3, 7)
            assertEquals(3, r.start)
            assertEquals(7, r.end)
        },
        // anything that can receive a reversed selection has to go through update, not the constructor
        "the constructor leaves it reversed, update swaps" to {
            assertTrue(CursorRange(7, 3).rangeEquals(7, 3))
            assertTrue(CursorRange().apply { update(7, 3) }.rangeEquals(3, 7))
        },
    )

    @Test
    fun updateAndClearSetTheBounds() = assertRows(
        "update with one index collapses" to {
            val r = CursorRange(2, 8).apply { update(5) }
            assertTrue(r.rangeEquals(5, 5))
            assertTrue(r.isEmpty())
        },
        "update with equal bounds is collapsed" to {
            val r = CursorRange().apply { update(4, 4) }
            assertTrue(r.rangeEquals(4, 4))
            assertTrue(r.isEmpty())
        },
        "clear resets to zero" to { assertTrue(CursorRange(10, 20).apply { clear() }.rangeEquals(0, 0)) },
    )

    @Test
    fun anOffsetShiftsBothBounds() = assertRows(
        "offset shifts both bounds" to { assertTrue(CursorRange(3, 7).apply { offset(5) }.rangeEquals(8, 12)) },
        "offset by zero is identity" to { assertTrue(CursorRange(3, 7).apply { offset(0) }.rangeEquals(3, 7)) },
        // no clamping: the caller is responsible for keeping the range inside the buffer
        "offset accepts negative and does not clamp at zero" to {
            assertTrue(CursorRange(1, 2).apply { offset(-5) }.rangeEquals(-4, -3))
        },
    )

    @Test
    fun emptinessTracksWhetherBoundsCoincide() {
        assertTrue(CursorRange(4, 4).isEmpty())
        assertFalse(CursorRange(4, 4).isNotEmpty())
        assertFalse(CursorRange(4, 5).isEmpty())
        assertTrue(CursorRange(4, 5).isNotEmpty())
    }

    @Test
    fun containsAnIndexInclusiveAtBothEnds() = assertRows(
        "start, end and between are inside" to {
            val r = CursorRange(2, 5)
            assertTrue(r.contains(2))
            assertTrue(r.contains(5))
            assertTrue(r.contains(3))
            assertFalse(r.contains(1))
            assertFalse(r.contains(6))
        },
        "a collapsed range contains only its own index" to {
            val r = CursorRange(4, 4)
            assertTrue(r.contains(4))
            assertFalse(r.contains(3))
            assertFalse(r.contains(5))
        },
        // `other in start..end` is an empty progression when reversed: not even its bounds
        "a reversed range contains nothing" to {
            val r = CursorRange(5, 2)
            assertFalse(r.contains(2))
            assertFalse(r.contains(3))
            assertFalse(r.contains(5))
        },
    )

    @Test
    fun containsARangeOnlyInsideIt() {
        val outer = CursorRange(2, 8)
        assertRows(
            *listOf(2 to 8, 3 to 7, 2 to 4, 6 to 8, 5 to 5).map { (s, e) ->
                "[$s,$e] is inside" to { assertTrue(outer.contains(CursorRange(s, e))) }
            }.toTypedArray(),
            *listOf(1 to 4, 6 to 9, 0 to 1, 9 to 10, 1 to 9).map { (s, e) ->
                "[$s,$e] is not inside" to { assertFalse(outer.contains(CursorRange(s, e))) }
            }.toTypedArray(),
        )
    }

    /** A value class over an `IntArray`: `equals` compares the array by reference; [CursorRange.rangeEquals] compares bounds. */
    @Test
    fun rangeEqualsComparesBothBounds() = assertRows(
        "another range" to {
            assertTrue(CursorRange(1, 2).rangeEquals(CursorRange(1, 2)))
            assertFalse(CursorRange(1, 2).rangeEquals(CursorRange(1, 3)))
        },
        "two bounds" to {
            val r = CursorRange(3, 7)
            assertTrue(r.rangeEquals(3, 7))
            assertFalse(r.rangeEquals(2, 7))
            assertFalse(r.rangeEquals(3, 8))
            assertFalse(r.rangeEquals(7, 3))
        },
        "one argument means collapsed" to {
            assertTrue(CursorRange(4, 4).rangeEquals(4))
            assertFalse(CursorRange(4, 5).rangeEquals(4))
        },
    )

    @Test
    fun toStringIsTheBracketedPair() {
        assertEquals("[3,7]", CursorRange(3, 7).toString())
    }
}
