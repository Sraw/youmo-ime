/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * Swiping across backspace: with nothing composed, dragging left grows a selection deleted on release; while
 * composing, left resets the composition and right does nothing. Decided on the first move and held till the lift.
 * Every touch starts with [onTouchDown]: a cancelled one gets no [onRelease], and its reading must not carry over.
 */
class BackspaceSwipeBehavior {

    enum class State {
        Stopped,

        Selection,

        Reset,
    }

    enum class MoveEffect {
        ExtendSelection,

        /** A reset gesture acts only on release. */
        None,
    }

    enum class ReleaseEffect {
        DeleteSelection,

        ResetComposition,

        None,
    }

    var state: State = State.Stopped
        private set

    /** Whatever an earlier, cancelled one left behind no longer applies. */
    fun onTouchDown() {
        state = State.Stopped
    }

    /** [composing]: a preedit as the gesture begins (predictions with nothing typed are not); read on the first move only. */
    fun onMove(composing: Boolean): MoveEffect = when (state) {
        State.Stopped -> {
            state = if (composing) State.Reset else State.Selection
            if (state == State.Selection) MoveEffect.ExtendSelection else MoveEffect.None
        }
        State.Selection -> MoveEffect.ExtendSelection
        State.Reset -> MoveEffect.None
    }

    /** [totalCount] negative is leftwards: a reset fires only leftwards. */
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
}
