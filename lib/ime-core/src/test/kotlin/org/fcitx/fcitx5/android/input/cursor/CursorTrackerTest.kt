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

class CursorTrackerTest {

    private fun tracker(start: Int = 0, end: Int = start) =
        CursorTracker().apply { resetTo(start, end) }

    @Test
    fun startsCollapsedAtZero() {
        val t = CursorTracker()
        assertTrue(t.current.rangeEquals(0, 0))
        assertTrue("latest falls back to current when nothing is predicted", t.latest.rangeEquals(0, 0))
    }

    @Test
    fun resetToSetsCurrentAndLatest() {
        val t = tracker(5)
        assertTrue(t.current.rangeEquals(5, 5))
        assertTrue(t.latest.rangeEquals(5, 5))
    }

    @Test
    fun resetToNormalisesReversedBounds() {
        val t = CursorTracker()
        t.resetTo(9, 4)
        assertTrue(t.current.rangeEquals(4, 9))
    }

    @Test
    fun resetToDiscardsPendingPredictions() {
        val t = tracker(0)
        t.predict(1)
        t.predict(2)
        t.resetTo(10)
        assertTrue("latest is the reset position, not a stale prediction", t.latest.rangeEquals(10, 10))
        // nothing is left to match, so an unrelated report cannot be consumed
        assertFalse(t.consume(2))
    }

    // an address bar typed in, then the screen turned: input restarts with the first EditorInfo, all selected
    @Test
    fun inputStartedAgainKeepsTheSelectionFollowedNotTheFirstStarts() {
        val t = tracker(0)
        t.predict(6)
        t.startInput(again = true, initialStart = 0, initialEnd = 11)
        assertTrue(t.latest.rangeEquals(6, 6))
        assertTrue(t.settled)
        // an app restarting input with an EditorInfo of its own: its selection is the editor's now
        t.startInput(again = false, initialStart = 0, initialEnd = 5)
        assertTrue(t.latest.rangeEquals(0, 5))
    }

    @Test
    fun predictMovesLatestButNotCurrent() {
        val t = tracker(0)
        t.predict(3)
        assertTrue("current still reflects the editor", t.current.rangeEquals(0, 0))
        assertTrue("latest reflects the prediction", t.latest.rangeEquals(3, 3))
    }

    @Test
    fun latestFollowsTheMostRecentPrediction() {
        val t = tracker(0)
        t.predict(1)
        t.predict(2)
        t.predict(3)
        assertTrue(t.latest.rangeEquals(3, 3))
    }

    /** The dedup of a prediction equal to `latest` is a memory optimisation only: these assert what is observable. */
    @Test
    fun predictingThePositionAlreadyHeldStaysInSync() {
        val t = tracker(4)
        t.predict(4)
        assertTrue(t.consume(4))
        assertTrue(t.latest.rangeEquals(4, 4))
    }

    @Test
    fun repeatedIdenticalPredictionsStayInSync() {
        val t = tracker(0)
        t.predict(5)
        t.predict(5)
        t.predict(5)
        assertTrue(t.consume(5))
        assertTrue(t.current.rangeEquals(5, 5))
    }

    @Test
    fun nonConsecutiveDuplicatePredictionsAreKept() {
        val t = tracker(0)
        t.predict(5)
        t.predict(6)
        t.predict(5)
        assertTrue("first 5 matched", t.consume(5))
        assertTrue("6 is still queued", t.consume(6))
        // current is 6 now: latest can only be 5 if the later 5 is queued
        assertTrue("the later 5 is still queued behind 6", t.latest.rangeEquals(5, 5))
    }

    @Test
    fun predictAcceptsSelections() {
        val t = tracker(0)
        t.predict(2, 6)
        assertTrue(t.latest.rangeEquals(2, 6))
        assertTrue(t.consume(2, 6))
        assertTrue(t.current.rangeEquals(2, 6))
    }

    /** `CursorRange` is mutable: changing the caller's instance must not rewrite the queued prediction. */
    @Test
    fun predictQueuesACopyOfTheGivenRange() {
        val t = tracker(0)
        val range = CursorRange(3, 3)
        t.predict(range)
        range.update(8, 8)
        assertTrue("the queued prediction kept its bounds", t.latest.rangeEquals(3, 3))
        assertTrue(t.consume(3))
    }

    @Test
    fun predictOffsetIsRelativeToLatestNotCurrent() {
        val t = tracker(0)
        t.predict(10)
        t.predictOffset(1)
        assertTrue("offset applied on top of the pending prediction", t.latest.rangeEquals(11, 11))
        assertTrue("current is untouched", t.current.rangeEquals(0, 0))
    }

    @Test
    fun predictOffsetChainsAcrossCalls() {
        val t = tracker(5)
        t.predictOffset(1)
        t.predictOffset(1)
        t.predictOffset(1)
        assertTrue(t.latest.rangeEquals(8, 8))
    }

    @Test
    fun predictOffsetHandlesBackspaceLikeNegativeSteps() {
        val t = tracker(5)
        t.predictOffset(-1)
        assertTrue(t.latest.rangeEquals(4, 4))
    }

    @Test
    fun predictOffsetCanMoveTheTwoBoundsIndependently() {
        val t = tracker(0)
        t.resetTo(3, 7)
        t.predictOffset(0, 2)
        assertTrue(t.latest.rangeEquals(3, 9))
    }

    @Test
    fun predictOffsetOfZeroLeavesThePositionUnchanged() {
        val t = tracker(5)
        t.predictOffset(0)
        assertTrue(t.latest.rangeEquals(5, 5))
        assertTrue(t.consume(5))
        assertTrue(t.current.rangeEquals(5, 5))
    }

    @Test
    fun consumingTheSinglePredictionMatches() {
        val t = tracker(0)
        t.predict(1)
        assertTrue(t.consume(1))
        assertTrue(t.current.rangeEquals(1, 1))
        assertTrue("queue is drained", t.latest.rangeEquals(1, 1))
    }

    @Test
    fun consumingReportedPositionAlreadyEqualToCurrentSucceeds() {
        val t = tracker(4)
        assertTrue("no prediction needed when nothing moved", t.consume(4))
    }

    /** Matching mid-queue drops everything before it and keeps what came after. */
    @Test
    fun consumingAMiddlePredictionDropsEarlierOnesAndKeepsLaterOnes() {
        val t = tracker(0)
        t.predict(1)
        t.predict(2)
        t.predict(3)

        assertTrue("matched the second prediction", t.consume(2))
        assertTrue("current caught up to the editor", t.current.rangeEquals(2, 2))
        assertTrue("prediction 3 is still in flight", t.latest.rangeEquals(3, 3))

        assertTrue("and can still be consumed", t.consume(3))
        assertTrue(t.latest.rangeEquals(3, 3))
    }

    /** An unpredicted position is an external edit: the queue empties and `current` jumps. */
    @Test
    fun consumingAnUnpredictedPositionResynchronises() {
        val t = tracker(0)
        t.predict(1)
        t.predict(2)

        assertFalse("nothing predicted position 99", t.consume(99))
        assertTrue("current follows the editor anyway", t.current.rangeEquals(99, 99))
        assertTrue("every prediction was discarded", t.latest.rangeEquals(99, 99))
    }

    @Test
    fun consumeReturnsFalseWhenNothingWasPredicted() {
        val t = tracker(0)
        assertFalse(t.consume(7))
        assertTrue(t.current.rangeEquals(7, 7))
    }

    @Test
    fun consumeNormalisesReversedBoundsIntoCurrent() {
        val t = tracker(0)
        assertFalse(t.consume(9, 4))
        assertTrue("current is stored ascending", t.current.rangeEquals(4, 9))
    }

    /** Predictions match raw arguments while `current` normalises: [4,9] is not matched by `consume(9, 4)`. */
    @Test
    fun consumeMatchesPredictionsBeforeNormalising() {
        val t = tracker(0)
        t.predict(4, 9)
        assertFalse("reversed report does not match the ascending prediction", t.consume(9, 4))
        assertTrue("current still normalises to the same range", t.current.rangeEquals(4, 9))
    }

    /** A report equal to `current` returns early, leaving the queue matchable. */
    @Test
    fun consumingCurrentPositionLeavesPendingPredictionsQueued() {
        val t = tracker(4)
        t.predict(7)

        assertTrue("fast path: the editor reported what current already held", t.consume(4))
        assertTrue("the prediction survived", t.latest.rangeEquals(7, 7))
        assertTrue("and is still matchable afterwards", t.consume(7))
        assertTrue(t.current.rangeEquals(7, 7))
    }

    @Test
    fun consumingSelectionsMatchesOnBothBounds() {
        val t = tracker(0)
        t.predict(2, 6)
        assertFalse("same start, different end is not a match", t.consume(2, 5))
        assertTrue(t.current.rangeEquals(2, 5))
    }

    /** The `ArrayDeque(16)` is a capacity, not a bound. */
    @Test
    fun queueGrowsPastItsInitialCapacity() {
        val t = tracker(0)
        repeat(100) { t.predict(it + 1) }
        assertTrue("the 100th prediction is retained", t.latest.rangeEquals(100, 100))
        assertTrue("and the first is still matchable", t.consume(1))
        assertTrue("with the rest still in flight", t.latest.rangeEquals(100, 100))
    }

    /** One deleted and one put in: back where the editor reported, which it has not reported yet. */
    @Test
    fun settledOnlyOnceEveryPredictionIsReported() {
        val t = tracker(4)
        assertTrue(t.settled)
        t.predictOffset(-1)
        t.predict(4)
        assertTrue("latest equals current", t.latest.rangeEquals(t.current))
        assertFalse(t.settled)
        assertTrue(t.consume(3))
        assertFalse("4 is still in flight", t.settled)
        assertTrue(t.consume(4))
        assertTrue(t.settled)
    }

    // PostedCommit only reads whether it is the same at two reads, not by how much
    @Test
    fun changesCountsPredictionsUnexpectedReportsAndResetsButNotConfirmations() {
        val t = tracker(0)
        var before = t.changes
        t.predict(1)
        assertNotEquals("a prediction", before, t.changes)
        before = t.changes
        // even of where it already is
        t.predict(1)
        assertNotEquals("a prediction of where it is", before, t.changes)
        before = t.changes
        assertTrue(t.consume(1))
        assertEquals("a confirmation changes nothing", before, t.changes)
        assertTrue("the editor reporting where current is", t.consume(1))
        assertEquals("nor does that", before, t.changes)
        assertFalse(t.consume(9))
        assertNotEquals("a report none expected", before, t.changes)
        before = t.changes
        t.resetTo(2)
        assertNotEquals("a reset", before, t.changes)
    }

    @Test
    fun typingSequenceStaysInSync() {
        val t = tracker(0)
        repeat(3) { i ->
            t.predictOffset(1)
            assertTrue("keystroke ${i + 1} confirmed", t.consume(i + 1))
        }
        assertTrue(t.current.rangeEquals(3, 3))
        assertTrue(t.latest.rangeEquals(3, 3))
    }

    @Test
    fun editorLaggingBehindSeveralPredictionsCatchesUpAtOnce() {
        val t = tracker(0)
        t.predictOffset(1)
        t.predictOffset(1)
        t.predictOffset(1)
        assertTrue("IME believes it is at 3", t.latest.rangeEquals(3, 3))
        assertTrue("editor reports the final position", t.consume(3))
        assertTrue(t.current.rangeEquals(3, 3))
        assertTrue("nothing left in flight", t.latest.rangeEquals(3, 3))
    }

    @Test
    fun externalCursorMoveDuringCompositionResynchronises() {
        val t = tracker(0)
        t.predictOffset(1)
        t.predictOffset(1)

        assertFalse("the tap was never predicted", t.consume(50))
        assertTrue(t.current.rangeEquals(50, 50))

        t.predictOffset(1)
        assertTrue(t.consume(51))
        assertTrue(t.current.rangeEquals(51, 51))
    }

    @Test
    fun replacingASelectionCollapsesTheRange() {
        val t = tracker(0)
        t.resetTo(3, 8)
        t.predict(4, 4)
        assertTrue(t.consume(4, 4))
        assertTrue(t.current.isEmpty())
        assertTrue(t.current.rangeEquals(4, 4))
    }

}
