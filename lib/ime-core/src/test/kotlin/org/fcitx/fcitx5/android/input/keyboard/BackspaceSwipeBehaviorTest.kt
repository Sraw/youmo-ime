/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.BackspaceSwipeBehavior.MoveEffect
import org.fcitx.fcitx5.android.input.keyboard.BackspaceSwipeBehavior.ReleaseEffect
import org.fcitx.fcitx5.android.input.keyboard.BackspaceSwipeBehavior.State
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Swiping the backspace key means two different things depending on whether a composition is
 * in progress, and picking the wrong one either eats the user's text or silently does nothing.
 */
class BackspaceSwipeBehaviorTest {

    private val behavior = BackspaceSwipeBehavior()

    @Test
    fun startsIdle() {
        assertEquals(State.Stopped, behavior.state)
    }

    // region selecting, with nothing composed

    @Test
    fun swipingWithNothingComposedGrowsASelection() {
        assertEquals(MoveEffect.ExtendSelection, behavior.onMove(composing = false))
        assertEquals(State.Selection, behavior.state)
    }

    @Test
    fun everySubsequentMovementKeepsExtending() {
        behavior.onMove(composing = false)
        repeat(5) {
            assertEquals(MoveEffect.ExtendSelection, behavior.onMove(composing = false))
        }
    }

    @Test
    fun releasingAfterSelectingDeletesIt() {
        behavior.onMove(composing = false)
        assertEquals(ReleaseEffect.DeleteSelection, behavior.onRelease(totalCount = -3))
        assertEquals(State.Stopped, behavior.state)
    }

    /** Direction is irrelevant once a selection exists: releasing always deletes it. */
    @Test
    fun aRightwardSelectionIsStillDeletedOnRelease() {
        behavior.onMove(composing = false)
        assertEquals(ReleaseEffect.DeleteSelection, behavior.onRelease(totalCount = 2))
    }

    // endregion

    // region resetting, mid-composition

    @Test
    fun swipingMidCompositionDoesNotTouchTheSelection() {
        assertEquals(MoveEffect.None, behavior.onMove(composing = true))
        assertEquals(State.Reset, behavior.state)
    }

    @Test
    fun aLeftwardResetGestureClearsTheComposition() {
        behavior.onMove(composing = true)
        assertEquals(ReleaseEffect.ResetComposition, behavior.onRelease(totalCount = -2))
        assertEquals(State.Stopped, behavior.state)
    }

    /** Swiping the other way is a harmless cancel, not a reset. */
    @Test
    fun aRightwardResetGestureDoesNothing() {
        behavior.onMove(composing = true)
        assertEquals(ReleaseEffect.None, behavior.onRelease(totalCount = 2))
        assertEquals(State.Stopped, behavior.state)
    }

    @Test
    fun aResetGestureThatEndedWhereItStartedDoesNothing() {
        behavior.onMove(composing = true)
        assertEquals(ReleaseEffect.None, behavior.onRelease(totalCount = 0))
    }

    @Test
    fun aResetGestureIgnoresEveryMovementAfterTheFirst() {
        behavior.onMove(composing = true)
        repeat(5) {
            assertEquals(MoveEffect.None, behavior.onMove(composing = false))
        }
        assertEquals("still a reset gesture", State.Reset, behavior.state)
    }

    // endregion

    // region the reading is fixed at the start of the gesture

    /**
     * The composition may finish while the finger is still down. The gesture must keep the
     * meaning it started with, or a swipe the user began as a reset would suddenly start
     * selecting their text.
     */
    @Test
    fun aCompositionEndingMidSwipeDoesNotTurnItIntoASelection() {
        behavior.onMove(composing = true)
        assertEquals(MoveEffect.None, behavior.onMove(composing = false))
        assertEquals(MoveEffect.None, behavior.onMove(composing = false))
        assertEquals(State.Reset, behavior.state)
        assertEquals(ReleaseEffect.ResetComposition, behavior.onRelease(totalCount = -1))
    }

    /** And the other way round: a selection swipe is not hijacked by a new composition. */
    @Test
    fun aCompositionStartingMidSwipeDoesNotTurnItIntoAReset() {
        behavior.onMove(composing = false)
        assertEquals(
            "the selection keeps growing even though a composition appeared",
            MoveEffect.ExtendSelection,
            behavior.onMove(composing = true),
        )
        assertEquals(State.Selection, behavior.state)
        assertEquals(ReleaseEffect.DeleteSelection, behavior.onRelease(totalCount = -1))
    }

    // endregion

    // region release without a swipe

    @Test
    fun releasingWithoutHavingMovedDoesNothing() {
        assertEquals(ReleaseEffect.None, behavior.onRelease(totalCount = 0))
        assertEquals(State.Stopped, behavior.state)
    }

    @Test
    fun releasingWithoutHavingMovedDoesNothingEvenAfterALeftwardCount() {
        assertEquals(ReleaseEffect.None, behavior.onRelease(totalCount = -5))
    }

    // endregion

    // region consecutive gestures

    @Test
    fun aSecondGestureIsReadAfresh() {
        behavior.onMove(composing = false)
        behavior.onRelease(totalCount = -1)

        assertEquals("the next swipe re-reads the composition", MoveEffect.None, behavior.onMove(composing = true))
        assertEquals(State.Reset, behavior.state)
    }

    @Test
    fun alternatingGesturesDoNotLeakStateIntoEachOther() {
        behavior.onMove(composing = true)
        assertEquals(ReleaseEffect.ResetComposition, behavior.onRelease(-1))

        behavior.onMove(composing = false)
        assertEquals(ReleaseEffect.DeleteSelection, behavior.onRelease(-1))

        behavior.onMove(composing = true)
        assertEquals(ReleaseEffect.None, behavior.onRelease(1))
    }

    // endregion
}
