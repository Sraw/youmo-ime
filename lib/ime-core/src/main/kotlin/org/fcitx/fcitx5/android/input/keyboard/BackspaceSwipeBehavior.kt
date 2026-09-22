/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * What swiping across the backspace key does.
 *
 * Two readings compete for the same gesture. With nothing being composed, dragging left grows
 * a selection and releasing deletes it. Mid-composition that would be destructive, so the
 * gesture is repurposed: dragging left instead resets the composition, and dragging right
 * does nothing.
 *
 * Which reading applies is decided once, on the first movement of the gesture, and then held
 * until the finger lifts -- otherwise a composition ending mid-swipe would switch the meaning
 * of a gesture already in progress.
 */
class BackspaceSwipeBehavior {

    enum class State {
        /** No swipe in progress. */
        Stopped,

        /** This swipe is growing a selection. */
        Selection,

        /** This swipe was started mid-composition, so it resets rather than selects. */
        Reset,
    }

    /** What the caller should do in response to a movement. */
    enum class MoveEffect {
        /** Extend the selection by the reported offsets. */
        ExtendSelection,

        /** Nothing: this swipe is a reset gesture, which only acts on release. */
        None,
    }

    /** What the caller should do when the finger lifts. */
    enum class ReleaseEffect {
        /** Delete the selection this swipe built. */
        DeleteSelection,

        /** Clear the composition instead. */
        ResetComposition,

        None,
    }

    var state: State = State.Stopped
        private set

    /**
     * @param composing whether something is being composed (a preedit, or candidates on offer)
     * when the gesture begins. Only consulted on the first movement.
     */
    fun onMove(composing: Boolean): MoveEffect = when (state) {
        State.Stopped -> {
            state = if (composing) State.Reset else State.Selection
            if (state == State.Selection) MoveEffect.ExtendSelection else MoveEffect.None
        }
        State.Selection -> MoveEffect.ExtendSelection
        State.Reset -> MoveEffect.None
    }

    /**
     * @param totalCount net swipe distance in steps; negative is leftwards. A reset gesture
     * only fires when the finger went left, so a rightward swipe cancels harmlessly.
     */
    fun onRelease(totalCount: Int): ReleaseEffect {
        val effect = when (state) {
            State.Stopped -> ReleaseEffect.None
            State.Selection -> ReleaseEffect.DeleteSelection
            State.Reset ->
                if (totalCount < 0) ReleaseEffect.ResetComposition else ReleaseEffect.None
        }
        state = State.Stopped
        return effect
    }

    /** Abandons an in-flight gesture without acting on it. */
    fun cancel() {
        state = State.Stopped
    }
}
