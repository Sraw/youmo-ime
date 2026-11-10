/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.shortcut.EditPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeSelectionTest {

    private fun CursorRange?.text() = this?.let { "${it.start}..${it.end}" }

    // "hello world|": a field read whole, the cursor at its end
    private fun begun(before: String = "hello world", after: String = "", selected: String = ""): SwipeSelection {
        val s = SwipeSelection()
        val start = before.length
        assertNull(s.begin(s.reached(), CursorRange(start, start + selected.length), EditPlan.Field(before, selected, after)))
        return s
    }

    @Test
    fun leftGrowsFromTheCursorRightShrinksThenGrowsPastIt() {
        val s = begun(before = "hello", after = " world")
        assertEquals("3..5", s.move(-2).text())
        assertEquals("4..5", s.move(1).text())
        assertEquals("past the anchor, the other way", "5..7", s.move(3).text())
        assertEquals("5..6", s.move(-1).text())
        assertEquals("back at the anchor: a cursor", "5..5", s.move(-1).text())
    }

    @Test
    fun theFieldsEndsStopIt() {
        val s = begun(before = "ab", after = "c")
        assertEquals("0..2", s.move(-5).text())
        assertNull("nothing further left", s.move(-1))
        assertEquals("2..3", s.move(9).text())
        assertNull("nothing further right", s.move(1))
    }

    @Test
    fun aCharacterIsWhatIsSeenAsOne() {
        // a flag, a family (ZWJ), an accent combined: one step each
        val flag = "🇨🇳"
        val family = "👨‍👩‍👧"
        val accent = "é"
        val before = "a$flag$family$accent"
        val s = begun(before = before)
        val end = before.length
        assertEquals("${end - 2}..$end", s.move(-1).text())
        assertEquals("${end - 2 - family.length}..$end", s.move(-1).text())
        assertEquals("1..$end", s.move(-1).text())
        assertEquals("0..$end", s.move(-1).text())
    }

    @Test
    fun nothingReadIsUnitsFromTheStartOfTheField() {
        val s = SwipeSelection()
        assertNull(s.begin(s.reached(), CursorRange(2, 2), null))
        assertEquals("0..2", s.move(-3).text())
        assertEquals("back to the cursor, known to be there", "2..2", s.move(2).text())
    }

    // no end known to the right: a step past what is known is tried alone, the next once the editor took it
    @Test
    fun pastWhatIsKnownToTheRightAStepWaitsForTheEditor() {
        val s = SwipeSelection()
        assertNull(s.begin(s.reached(), CursorRange(2, 2), null))
        assertEquals("one tried", "2..3", s.move(3, CursorRange(2, 2)).text())
        assertNull("not yet reported: no further", s.move(1, CursorRange(2, 2)))
        assertEquals("taken: the next", "2..4", s.move(1, CursorRange(2, 3)).text())
        assertEquals("back within what is known", "2..3", s.move(-1, CursorRange(2, 4)).text())
    }

    // the editor ignored a step past its end: a step back counts from where its selection still is
    @Test
    fun aStepPastTheEndNotTakenLeavesNoDeadStepBack() {
        val s = SwipeSelection()
        assertNull(s.begin(s.reached(), CursorRange(6, 6), null))
        assertEquals("6..7", s.move(1, CursorRange(6, 6)).text())
        assertNull(s.move(1, CursorRange(6, 6)))
        assertEquals("one back from the end the editor has", "5..6", s.move(-1, CursorRange(6, 6)).text())
    }

    // an app that gives less than asked before the cursor (review M7): its start is not the field's
    @Test
    fun aShortReadBeforeIsNotTheStartOfTheField() {
        val s = SwipeSelection()
        val at = 10
        s.begin(s.reached(), CursorRange(at, at), EditPlan.Field("abc", "", ""))
        assertEquals("the read, then units on", "${at - 5}..$at", s.move(-5).text())
    }

    // a read whose cursor is not the tracked one (a cursor tracked behind the editor's): its text not used
    @Test
    fun aReadElsewhereIsNotUsed() {
        val s = SwipeSelection()
        s.begin(s.reached(), CursorRange(2, 2), EditPlan.Field("😀", "", ""), at = 3)
        assertEquals("units, not the emoji shifted", "1..2", s.move(-1).text())
    }

    @Test
    fun anotherKeyActingEndsIt() {
        val s = begun()
        assertEquals("10..11", s.move(-1).text())
        s.end()
        assertNull(s.move(-1))
        val pending = SwipeSelection()
        val touch = pending.reached()
        pending.move(-1)
        pending.end()
        assertNull("ended before it began", pending.begin(touch, CursorRange(5, 5), null))
    }

    @Test
    fun aTouchThatMovesNothingKeepsTheAnchorForTheNext() {
        val s = begun(before = "hello", after = " world")
        assertEquals("5..8", s.move(3).text())
        assertNull(s.begin(s.reached(), CursorRange(5, 8), EditPlan.Field("hello", " wo", "rld")))
        // lifted with no step: the next still goes on with the right end free
        assertNull(s.begin(s.reached(), CursorRange(5, 8), EditPlan.Field("hello", " wo", "rld")))
        assertEquals("5..9", s.move(1).text())
    }

    @Test
    fun pastWhatWasReadUnitsGoOn() {
        val before = "x".repeat(SwipeSelection.READ)
        val s = SwipeSelection()
        val at = 1000
        s.begin(s.reached(), CursorRange(at, at), EditPlan.Field(before, "", "yz"))
        assertEquals("the read's start, then a unit", "${at - SwipeSelection.READ - 1}..$at", s.move(-(SwipeSelection.READ + 1)).text())
    }

    @Test
    fun aReadNotOfThisSelectionIsNotUsed() {
        val s = SwipeSelection()
        // the editor gave no selected text for a selection of two: positions not trusted to the text
        s.begin(s.reached(), CursorRange(4, 6), EditPlan.Field("😀😀", "", ""))
        assertEquals("units", "3..6", s.move(-1).text())
    }

    @Test
    fun aSelectionThereGoesOnFromItsStartTheAnchorItsEnd() {
        val s = begun(before = "hello ", selected = "wor", after = "ld")
        assertEquals("5..9", s.move(-1).text())
        assertEquals("7..9", s.move(2).text())
    }

    @Test
    fun theSelectionTheLastTouchLeftKeepsItsAnchor() {
        val s = begun(before = "hello", after = " world")
        assertEquals("5..8", s.move(3).text())
        // the next touch on it: the anchor still at 5, the right end still the free one
        assertNull(s.begin(s.reached(), CursorRange(5, 8), EditPlan.Field("hello", " wo", "rld")))
        assertEquals("5..9", s.move(1).text())
        // another selection: from its end as any
        assertNull(s.begin(s.reached(), CursorRange(1, 3), EditPlan.Field("h", "el", "lo world")))
        assertEquals("2..3", s.move(1).text())
    }

    @Test
    fun stepsBeforeItBeginsAreTakenAsItBegins() {
        val s = SwipeSelection()
        val touch = s.reached()
        assertNull(s.move(-1))
        assertNull(s.move(-2))
        assertEquals("2..5", s.begin(touch, CursorRange(5, 5), EditPlan.Field("hello", "", "")).text())
        assertNull("once", s.begin(touch, CursorRange(2, 5), null))
    }

    @Test
    fun anOlderTouchBeginsNothing() {
        val s = SwipeSelection()
        val old = s.reached()
        s.move(-1)
        val now = s.reached()
        assertNull(s.begin(old, CursorRange(5, 5), null))
        assertNull("the new touch's steps start at none", s.begin(now, CursorRange(5, 5), null))
        assertEquals("4..5", s.move(-1).text())
    }
}
