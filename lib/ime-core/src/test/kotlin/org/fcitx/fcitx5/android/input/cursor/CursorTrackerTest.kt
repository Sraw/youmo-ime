/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.cursor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CursorTracker] reconciles the cursor position the IME optimistically predicts against the
 * position the editor actually reports back. `predict` queues an expected position; `consume`
 * is fed what the editor reported and drops every prediction up to and including the match.
 */
class CursorTrackerTest {

    private fun tracker(start: Int = 0, end: Int = start) =
        CursorTracker().apply { resetTo(start, end) }

    // region initial state

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

    // endregion

    // region predict

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

    /**
     * `predict` skips queuing a prediction equal to `latest`. That dedup is a memory
     * optimisation only -- mutation testing confirms it has no effect observable through the
     * public API, because duplicates are always adjacent (so `latest` is unchanged) and
     * `consume` drains greedily from the front. These three tests therefore assert what is
     * actually observable: repeated predictions keep the tracker in sync.
     */
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

    /**
     * `predict(CursorRange)` stores the instance it was handed. Because `CursorRange` wraps a
     * mutable array, mutating that instance afterwards rewrites the queued prediction.
     */
    @Test
    fun predictStoresTheGivenInstanceNotACopy() {
        val t = tracker(0)
        val range = CursorRange(3, 3)
        t.predict(range)
        range.update(8, 8)
        assertTrue("the queued prediction changed with the caller's object", t.latest.rangeEquals(8, 8))
    }

    // endregion

    // region predictOffset

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
        // grow the selection to the right only
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

    // endregion

    // region consume

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

    /**
     * Matching a prediction in the middle drops everything queued before it and keeps what
     * came after: the editor is behind, later predictions are still in flight.
     */
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

    /**
     * A position nobody predicted means an external edit (the user tapped elsewhere, another
     * app moved the cursor). Every prediction is stale, so the queue is emptied and `current`
     * jumps to what the editor reported.
     */
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

    /**
     * Pins an asymmetry: predictions are matched against the raw arguments, but `current` is
     * written through `update`, which normalises. So a prediction of [4,9] is NOT matched by
     * `consume(9, 4)` even though `current` ends up as [4,9] either way.
     */
    @Test
    fun consumeMatchesPredictionsBeforeNormalising() {
        val t = tracker(0)
        t.predict(4, 9)
        assertFalse("reversed report does not match the ascending prediction", t.consume(9, 4))
        assertTrue("current still normalises to the same range", t.current.rangeEquals(4, 9))
    }

    /**
     * Pins the `current` fast path: when the reported position already equals `current`,
     * `consume` returns early and leaves queued predictions in place. They remain matchable.
     */
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

    // endregion

    // region queue behaviour

    /**
     * The `ArrayDeque(16)` is an initial capacity, not a bound. Predictions past the 16th must
     * still be queued, otherwise a burst of edits would silently desynchronise the tracker.
     */
    @Test
    fun queueGrowsPastItsInitialCapacity() {
        val t = tracker(0)
        repeat(100) { t.predict(it + 1) }
        assertTrue("the 100th prediction is retained", t.latest.rangeEquals(100, 100))
        assertTrue("and the first is still matchable", t.consume(1))
        assertTrue("with the rest still in flight", t.latest.rangeEquals(100, 100))
    }

    // endregion

    // region end-to-end sequences

    /** Typing "abc" one character at a time, with the editor confirming each keystroke. */
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

    /** The IME runs ahead of a slow editor, then the editor catches up in one report. */
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

    /** The user taps elsewhere mid-composition; the tracker must resynchronise, not drift. */
    @Test
    fun externalCursorMoveDuringCompositionResynchronises() {
        val t = tracker(0)
        t.predictOffset(1)
        t.predictOffset(1)

        assertFalse("the tap was never predicted", t.consume(50))
        assertTrue(t.current.rangeEquals(50, 50))

        // subsequent typing continues from the new position
        t.predictOffset(1)
        assertTrue(t.consume(51))
        assertTrue(t.current.rangeEquals(51, 51))
    }

    /** Selecting a range, then replacing it with a single character. */
    @Test
    fun replacingASelectionCollapsesTheRange() {
        val t = tracker(0)
        t.resetTo(3, 8)
        t.predict(4, 4)
        assertTrue(t.consume(4, 4))
        assertTrue(t.current.isEmpty())
        assertTrue(t.current.rangeEquals(4, 4))
    }

    // endregion
}
