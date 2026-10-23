/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

/**
 * Which events of a touch carry on the drag the docked keyboard's handle began, once the view is made
 * anew under it: the finger that held the grip ([pointerId]). The input area then gets the whole
 * touch, unsplit: another finger already down on a key is in it too, and is nothing of the drag's. A
 * new touch coming down is not this one (its end never came): it is left alone. Not told by the
 * events' down time: events injected one at a time (adb's `input motionevent`, as the UAT drives it)
 * each carry their own, the gesture all the same.
 */
class HandoffTouch(val pointerId: Int) {

    enum class Action { Down, Move, PointerDown, PointerUp, Up, Cancel, Other }

    /** Move the keyboard by the finger; lifted, or ended otherwise (no dock); not ours; another touch. */
    enum class Step { Move, Lift, End, Ignore, Abandon }

    /** An [action] by [actionPointerId]; [tracked] if the grip's finger is in it. */
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
