/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

/** The drag the docked handle began, carried on in the view made anew: [pointerId]'s events only, a new touch left alone. Not by down time: adb's injected events each carry their own. */
class HandoffTouch(val pointerId: Int) {

    enum class Action { Down, Move, PointerDown, PointerUp, Up, Cancel, Other }

    /** Lifted, or ended otherwise (no dock); not ours; another touch. */
    enum class Step { Move, Lift, End, Ignore, Abandon }

    /** [tracked]: the grip's finger is in it. */
    fun step(action: Action, actionPointerId: Int, tracked: Boolean): Step = when {
        action == Action.Down -> Step.Abandon
        action == Action.Move -> if (tracked) Step.Move else Step.Ignore
        // a second finger ends the drag unlifted, as on the floating keyboard's own handle
        action == Action.Cancel || action == Action.PointerDown -> Step.End
        (action == Action.PointerUp || action == Action.Up) && actionPointerId == pointerId -> Step.Lift
        // the last finger up, and not the grip's: that one was lost on the way
        action == Action.Up -> Step.End
        else -> Step.Ignore
    }
}
