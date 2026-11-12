/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.assertRows
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
    fun theFieldsStartStopsIt() {
        // to the right no read says the field ends: aShortReadAfterIsNotTheEndOfTheField covers it
        val s = begun(before = "ab", after = "c")
        assertEquals("0..2", s.move(-5).text())
        assertNull("nothing further left", s.move(-1))
    }

    // the bug: an app that gave less after the cursor than it has stopped the selection where the
    // read ended. Past it a step is tried, and goes on once the editor reports it taken
    @Test
    fun aShortReadAfterIsNotTheEndOfTheField() {
        val s = begun(before = "ab", after = "c")
        assertEquals("the read, then one tried", "2..4", s.move(2, CursorRange(2, 2), settled = true).text())
        assertEquals("taken: on past it", "2..5", s.move(1, CursorRange(2, 4), settled = true).text())
    }

    // the probe moves the right end alone: a report starting where it is (the cursor put there by
    // something else) is not it taken
    @Test
    fun aReportStartingAtTheProbeDoesNotTakeIt() {
        val s = begun(before = "ab")
        assertEquals("2..3", s.move(1, CursorRange(2, 2), settled = true).text())
        assertNull("still waiting", s.move(1, CursorRange(3, 5), settled = true))
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

    // no end known to the right: a step past what is known is tried alone, the steps after it kept
    // till the editor reports it taken
    @Test
    fun pastWhatIsKnownToTheRightTheStepsWaitForTheEditor() {
        val s = SwipeSelection()
        assertNull(s.begin(s.reached(), CursorRange(2, 2), null))
        assertEquals("one tried", "2..3", s.move(1, CursorRange(2, 2), settled = true).text())
        assertNull("not yet reported: kept", s.move(1, CursorRange(2, 2), settled = false))
        assertNull(s.move(1, CursorRange(2, 2), settled = false))
        assertEquals("taken: the next tried, the one after it kept", "2..4", s.move(0, CursorRange(2, 3), settled = true).text())
        assertEquals("taken: the one kept", "2..5", s.move(0, CursorRange(2, 4), settled = true).text())
        assertEquals("back within what is known", "2..4", s.move(-1, CursorRange(2, 5), settled = true).text())
    }

    // an older report that happens to end where the probe is does not pass for it: not all reported yet
    @Test
    fun aStaleReportDoesNotTakeAProbe() {
        val s = SwipeSelection()
        // a backspace from 4 to 3, its report not in: (4, 4) the last reported
        assertNull(s.begin(s.reached(), CursorRange(3, 3), null))
        assertEquals("3..4", s.move(1, CursorRange(4, 4), settled = false).text())
        assertNull("(4, 4) is not of the probe", s.move(1, CursorRange(4, 4), settled = false))
        assertEquals("ignored: as far back as the finger, from 3", "2..3", s.move(-3, CursorRange(3, 3), settled = false).text())
        assertEquals("given back", "3..4", s.untaken.text())
    }

    // the editor ignored a step past its end: a step back counts from where its selection still is
    @Test
    fun aStepPastTheEndNotTakenLeavesNoDeadStepBack() {
        val s = SwipeSelection()
        assertNull(s.begin(s.reached(), CursorRange(6, 6), null))
        assertEquals("6..7", s.move(1, CursorRange(6, 6), settled = true).text())
        assertNull(s.move(1, CursorRange(6, 6), settled = false))
        assertNull("the finger back on the probe: still waiting", s.move(-1, CursorRange(6, 6), settled = false))
        assertNull(s.untaken)
        assertNull("back off it: given up, the editor's end where it was, nothing to set", s.move(-1, CursorRange(6, 6), settled = false))
        assertEquals("given back", "6..7", s.untaken.text())
        assertEquals("a step back from the end, as the finger", "5..6", s.move(-1, CursorRange(6, 6), settled = true).text())
        assertNull(s.untaken)
    }

    // lifted on a probe not taken: it is given back, and the next touch goes on from where the editor is
    @Test
    fun endedOnAProbeNotTakenGivesItBack() {
        val s = SwipeSelection()
        assertNull(s.begin(s.reached(), CursorRange(6, 6), null))
        assertEquals("6..7", s.move(3, CursorRange(6, 6), settled = true).text())
        assertEquals("given back", "6..7", s.end().text())
        assertNull("once", s.end())
        // the editor still at 6: the next touch from there
        assertNull(s.begin(s.reached(), CursorRange(6, 6), null))
        assertEquals("5..6", s.move(-1).text())
        val none = SwipeSelection()
        none.begin(none.reached(), CursorRange(1, 1), null)
        none.move(-1)
        assertNull("no probe: nothing given back", none.end())
    }

    // an app that gives less than asked before the cursor: its start is not the field's
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
        s.cancel()
        assertNull(s.move(-1))
        val pending = SwipeSelection()
        val touch = pending.reached()
        pending.move(-1)
        pending.cancel()
        assertNull("ended before it began", pending.begin(touch, CursorRange(5, 5), null))
    }

    // lifted on a probe the editor reported taking: kept, the next touch going on from it
    @Test
    fun endedOnAProbeTakenKeepsItsAnchor() {
        val s = SwipeSelection()
        assertNull(s.begin(s.reached(), CursorRange(3, 3), null))
        assertEquals("3..4", s.move(1, CursorRange(3, 3), settled = true).text())
        assertNull("taken: nothing given back", s.end(CursorRange(3, 4), settled = true))
        assertNull(s.begin(s.reached(), CursorRange(3, 4), null))
        assertEquals("the anchor kept at 3", "3..3", s.move(-1).text())
    }

    // given up at the lift, its report late: the next touch beginning on it still goes on from it
    @Test
    fun aProbeGivenUpButTakenLateKeepsItsAnchor() {
        val s = SwipeSelection()
        assertNull(s.begin(s.reached(), CursorRange(3, 3), null))
        assertEquals("3..4", s.move(1, CursorRange(3, 3), settled = true).text())
        assertEquals("not reported yet: given back", "3..4", s.end(CursorRange(3, 3), settled = false).text())
        assertNull(s.begin(s.reached(), CursorRange(3, 4), null))
        assertEquals("the anchor kept at 3", "3..3", s.move(-1).text())
    }

    // lifted before it began (the host busy committing what was typed): the steps moved still taken
    // as it begins, none past what is known, nothing after the lift
    @Test
    fun liftedBeforeItBeganTakesTheStepsMoved() {
        val s = SwipeSelection()
        val touch = s.reached()
        s.move(-2)
        s.end()
        assertNull(s.move(-1))
        assertEquals("3..5", s.begin(touch, CursorRange(5, 5), null).text())
        val past = SwipeSelection()
        val t = past.reached()
        past.move(2)
        past.end()
        assertNull("no end known: no step past it tried", past.begin(t, CursorRange(5, 5), null))
        assertNull(past.end())
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

    // 滑选 up and down: the editor moves the free end a line on screen (shift+Up/Down), its report taken back
    @Test
    fun aLineLandsWhereTheEditorReportsTheAnchorKept() {
        val s = begun(before = "one two three")
        assertEquals("10..13", s.move(-3).text())
        assertEquals(true, s.lineable)
        s.lineSent()
        assertEquals(true, s.lining)
        assertNull("steps meanwhile wait", s.move(-1))
        // the editor's report: the anchor first, the end it moved before it
        assertEquals("then go on from the reported end", "3..13", s.lineLanded(CursorRange(13, 4)).text())
        assertEquals(13, s.anchorAt)
        assertEquals("sideways from there", "2..13", s.move(-1).text())
    }

    @Test
    fun aLinePastTheAnchorTurnsTheSelection() {
        val s = begun(before = "ab", after = "cdefghij")
        assertEquals("1..2", s.move(-1).text())
        s.lineSent()
        assertNull("nothing waiting: nothing to set, the editor has it", s.lineLanded(CursorRange(2, 7)))
        assertEquals(7, s.headAt)
        assertEquals("2..8", s.move(1).text())
        assertEquals("back over the anchor", "1..2", s.move(-7).text())
    }

    @Test
    fun rushedLinesWaitForTheirLastReport() {
        val s = begun(before = "abcdefghij")
        s.lineSent()
        s.lineSent()
        assertNull(s.move(1))
        assertNull("the first landed", s.lineLanded(CursorRange(10, 6)))
        assertEquals("the second: the step from there", "3..10", s.lineLanded(CursorRange(10, 2)).text())
    }

    // the editor moving the selection otherwise: the line no longer waited for, a report later not a line
    @Test
    fun aReportNotKeepingTheAnchorStopsWaiting() {
        fun otherwise(givenUp: Boolean) = {
            val s = begun(before = "abcdef")
            s.lineSent()
            if (givenUp) assertNull(s.linesUnreported()) else assertNull(s.move(-1))
            assertNull("the editor moved the selection otherwise", s.lineLanded(CursorRange(1, 1)))
            assertEquals(false, s.lining)
            assertEquals(false, s.awaitingLines)
            assertNull("not a line any more", s.lineLanded(CursorRange(6, 3)))
            assertEquals(6, s.headAt)
        }
        assertRows(
            "reported while the line is on time" to otherwise(givenUp = false),
            "reported after the wait gave the line up" to otherwise(givenUp = true),
        )
    }

    @Test
    fun linesNeverReportedGiveTheStepsBack() {
        val s = begun(before = "abcdef")
        s.lineSent()
        assertNull(s.move(-2))
        assertEquals("an editor ignoring shift+Up: the steps taken from where the end was", "4..6", s.linesUnreported().text())
        assertNull(s.linesUnreported())
    }

    // a line reported after the lift: set in order, the next touch keeping its anchor
    @Test
    fun aLineLandingAfterTheLiftIsKeptInOrder() {
        fun lifted(givenUp: Boolean) = {
            val s = begun(before = "abcdefgh")
            s.lineSent()
            if (givenUp) assertNull(s.linesUnreported())
            assertNull(s.end())
            assertEquals("the editor's, set in order", "3..8", s.lineLanded(CursorRange(8, 3)).text())
            assertEquals(false, s.awaitingLines)
            assertNull(s.begin(s.reached(), CursorRange(3, 8), EditPlan.Field("abc", "defgh", "")))
            assertEquals("the next touch keeps the anchor", "2..8", s.move(-1).text())
        }
        assertRows(
            "landing while still waited for" to lifted(givenUp = false),
            "landing after the wait gave it up, late" to lifted(givenUp = true),
        )
    }

    // the touch over a while: lines given up on no longer waited for, a report later the editor's own
    @Test
    fun lateLinesForgottenAreNoLongerWaitedFor() {
        val s = begun(before = "abcdef")
        s.lineSent()
        assertNull(s.end())
        assertEquals("lifted: the selection as it is, in order", "6..6", s.linesUnreported().text())
        s.linesForgotten()
        assertEquals(false, s.awaitingLines)
        assertNull("not a line", s.lineLanded(CursorRange(6, 3)))
        assertEquals(6, s.headAt)
    }

    @Test
    fun noLineWhileAProbeWaitsOrBeforeItBegins() {
        val s = SwipeSelection()
        s.reached()
        assertEquals("not begun", false, s.lineable)
        val p = begun(before = "ab")
        assertEquals("2..3", p.move(1, CursorRange(2, 2), settled = true).text())
        assertEquals("a probe waiting", false, p.lineable)
    }

    // the steps moved while a line was on its way, the finger lifted before it landed: taken from where it landed
    @Test
    fun stepsWaitingForALineLandedAfterTheLiftAreTaken() {
        val s = begun(before = "one two three")
        assertEquals("12..13", s.move(-1).text())
        s.lineSent()
        assertNull(s.move(-2))
        assertNull(s.end())
        assertEquals("the line's end, two to the left, in order", "2..13", s.lineLanded(CursorRange(13, 4)).text())
    }

    // a line landing past what was read: the field read anew there, its characters graphemes again
    @Test
    fun aLineLandingPastTheReadReadsTheFieldThere() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        val s = SwipeSelection()
        val a = "a".repeat(SwipeSelection.READ)
        assertNull(s.begin(s.reached(), CursorRange(1000, 1000), EditPlan.Field(a, "", a), at = 1000))
        s.lineSent()
        val head = 1000 - a.length - 100
        var reads = 0
        assertNull(
            s.lineLanded(CursorRange(1000, head)) {
                reads++
                EditPlan.Field("x$family", "b".repeat(1000 - head), "") to head
            },
        )
        assertEquals("read once, as it landed past the read", 1, reads)
        assertEquals("the family a step", "${head - family.length}..1000", s.move(-1).text())
    }

    // an editor echoing the selection set for it in its own order, the free end not moved: not the line
    @Test
    fun anEchoOfTheSelectionIsNotALine() {
        val s = begun(before = "abcdef")
        assertEquals("4..6", s.move(-2).text())
        s.lineSent()
        assertNull("the echo", s.lineLanded(CursorRange(4, 6)))
        assertEquals("still waiting for the line", true, s.lining)
        assertNull(s.lineLanded(CursorRange(6, 1)))
        assertEquals(1, s.headAt)
    }

    // a line reported after the wait gave it up: the free end where the editor has it, the steps after from there
    @Test
    fun aLineReportedLateIsStillWhereTheEndIs() {
        val s = begun(before = "abcdefghij")
        assertEquals("9..10", s.move(-1).text())
        s.lineSent()
        assertNull(s.linesUnreported())
        assertEquals(true, s.awaitingLines)
        assertNull("nothing to set: the editor has it", s.lineLanded(CursorRange(10, 4)))
        assertEquals(false, s.awaitingLines)
        assertEquals("on from the late line's end", "3..10", s.move(-1).text())
    }
}
