/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.cursor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorRangeTest {

    // region construction

    @Test
    fun defaultsToCollapsedAtZero() {
        val r = CursorRange()
        assertTrue(r.rangeEquals(0, 0))
        assertTrue(r.isEmpty())
    }

    @Test
    fun constructorStoresBoundsVerbatim() {
        val r = CursorRange(3, 7)
        assertEquals(3, r.start)
        assertEquals(7, r.end)
    }

    /**
     * The constructor does NOT normalise, but [CursorRange.update] does. Anything that can
     * receive a reversed selection has to go through `update`, not the constructor.
     */
    @Test
    fun constructorKeepsReversedBoundsWhileUpdateNormalises() {
        val constructed = CursorRange(7, 3)
        assertTrue("constructor leaves it reversed", constructed.rangeEquals(7, 3))

        val updated = CursorRange()
        updated.update(7, 3)
        assertTrue("update swaps into ascending order", updated.rangeEquals(3, 7))
    }

    // endregion

    // region identity -- the sharp edges of a value class wrapping a mutable IntArray

    /**
     * `CursorRange` is a value class around an `IntArray`, so the generated `equals` compares
     * the array by reference. Two ranges with identical bounds are NOT `==`. Every comparison
     * must use [CursorRange.rangeEquals]; this test exists so that never silently changes.
     */
    @Test
    fun equalsIsReferenceBasedSoRangeEqualsIsMandatory() {
        val a = CursorRange(1, 2)
        val b = CursorRange(1, 2)
        assertNotEquals("== compares the backing array by reference", a, b)
        assertTrue("rangeEquals compares the bounds", a.rangeEquals(b))
    }

    /**
     * Assigning a `CursorRange` copies the reference to the same backing array, so mutating
     * one mutates the other. Callers that need an independent range must construct a new one.
     */
    @Test
    fun copiesAliasTheSameBackingArray() {
        val original = CursorRange(1, 2)
        val alias = original
        alias.update(9, 9)
        assertTrue("mutating the alias mutated the original", original.rangeEquals(9, 9))
    }

    // endregion

    // region update / clear / offset

    @Test
    fun updateWithSingleIndexCollapsesRange() {
        val r = CursorRange(2, 8)
        r.update(5)
        assertTrue(r.rangeEquals(5, 5))
        assertTrue(r.isEmpty())
    }

    @Test
    fun updateWithEqualBoundsIsCollapsed() {
        val r = CursorRange()
        r.update(4, 4)
        assertTrue(r.rangeEquals(4, 4))
        assertTrue(r.isEmpty())
    }

    @Test
    fun clearResetsToZero() {
        val r = CursorRange(10, 20)
        r.clear()
        assertTrue(r.rangeEquals(0, 0))
    }

    @Test
    fun offsetShiftsBothBounds() {
        val r = CursorRange(3, 7)
        r.offset(5)
        assertTrue(r.rangeEquals(8, 12))
    }

    @Test
    fun offsetAcceptsNegativeAndDoesNotClampAtZero() {
        val r = CursorRange(1, 2)
        r.offset(-5)
        // no clamping: the caller is responsible for keeping the range inside the buffer
        assertTrue(r.rangeEquals(-4, -3))
    }

    @Test
    fun offsetByZeroIsIdentity() {
        val r = CursorRange(3, 7)
        r.offset(0)
        assertTrue(r.rangeEquals(3, 7))
    }

    @Test
    fun offsetPreservesLength() {
        val r = CursorRange(3, 7)
        val lengthBefore = r.end - r.start
        r.offset(-3)
        assertEquals(lengthBefore, r.end - r.start)
    }

    // endregion

    // region emptiness

    @Test
    fun emptinessTracksWhetherBoundsCoincide() {
        val collapsed = CursorRange(4, 4)
        assertTrue(collapsed.isEmpty())
        assertFalse(collapsed.isNotEmpty())

        val selection = CursorRange(4, 5)
        assertFalse(selection.isEmpty())
        assertTrue(selection.isNotEmpty())
    }

    // endregion

    // region contains(Int)

    @Test
    fun containsIndexIsInclusiveAtBothEnds() {
        val r = CursorRange(2, 5)
        assertTrue("start is inside", r.contains(2))
        assertTrue("end is inside", r.contains(5))
        assertTrue(r.contains(3))
        assertFalse(r.contains(1))
        assertFalse(r.contains(6))
    }

    @Test
    fun collapsedRangeContainsOnlyItsOwnIndex() {
        val r = CursorRange(4, 4)
        assertTrue(r.contains(4))
        assertFalse(r.contains(3))
        assertFalse(r.contains(5))
    }

    /**
     * `contains(Int)` is implemented as `other in start..end`, which is an empty progression
     * when the range is reversed -- so a reversed range contains nothing, not even its bounds.
     */
    @Test
    fun reversedRangeContainsNothing() {
        val r = CursorRange(5, 2)
        assertFalse(r.contains(2))
        assertFalse(r.contains(3))
        assertFalse(r.contains(5))
    }

    // endregion

    // region contains(CursorRange)

    @Test
    fun containsRangeAcceptsSubRangesIncludingItself() {
        val outer = CursorRange(2, 8)
        assertTrue("identical range", outer.contains(CursorRange(2, 8)))
        assertTrue("strict sub-range", outer.contains(CursorRange(3, 7)))
        assertTrue("touching the start", outer.contains(CursorRange(2, 4)))
        assertTrue("touching the end", outer.contains(CursorRange(6, 8)))
        assertTrue("collapsed inside", outer.contains(CursorRange(5, 5)))
    }

    @Test
    fun containsRangeRejectsOverlapAndOutside() {
        val outer = CursorRange(2, 8)
        assertFalse("crosses the start", outer.contains(CursorRange(1, 4)))
        assertFalse("crosses the end", outer.contains(CursorRange(6, 9)))
        assertFalse("entirely before", outer.contains(CursorRange(0, 1)))
        assertFalse("entirely after", outer.contains(CursorRange(9, 10)))
        assertFalse("strict superset", outer.contains(CursorRange(1, 9)))
    }

    // endregion

    // region rangeEquals

    @Test
    fun rangeEqualsComparesBothBounds() {
        val r = CursorRange(3, 7)
        assertTrue(r.rangeEquals(3, 7))
        assertFalse("start differs", r.rangeEquals(2, 7))
        assertFalse("end differs", r.rangeEquals(3, 8))
    }

    @Test
    fun rangeEqualsWithOneArgumentMeansCollapsed() {
        assertTrue(CursorRange(4, 4).rangeEquals(4))
        assertFalse("a real selection is not collapsed", CursorRange(4, 5).rangeEquals(4))
    }

    @Test
    fun rangeEqualsDistinguishesReversedBounds() {
        assertFalse(CursorRange(3, 7).rangeEquals(7, 3))
    }

    // endregion

    // region misc

    @Test
    fun destructuringYieldsStartThenEnd() {
        val (start, end) = CursorRange(3, 7)
        assertEquals(3, start)
        assertEquals(7, end)
    }

    @Test
    fun toStringIsTheBracketedPair() {
        assertEquals("[3,7]", CursorRange(3, 7).toString())
    }

    // endregion
}
