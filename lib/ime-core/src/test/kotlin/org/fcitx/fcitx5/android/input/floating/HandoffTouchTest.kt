/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import org.fcitx.fcitx5.android.input.floating.HandoffTouch.Action
import org.fcitx.fcitx5.android.input.floating.HandoffTouch.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class HandoffTouchTest {

    // the grip held by pointer 1, a thumb (0) resting on a key since before
    private val t = HandoffTouch(pointerId = 1)

    @Test
    fun onlyTheGripsFingerMovesTheKeyboardAndItsLiftEndsIt() {
        assertEquals(Step.Move, t.step(Action.Move, 0, tracked = true))
        assertEquals("the thumb lifted", Step.Ignore, t.step(Action.PointerUp, 0, tracked = true))
        assertEquals(Step.Lift, t.step(Action.PointerUp, 1, tracked = true))
        assertEquals(Step.Lift, t.step(Action.Up, 1, tracked = true))
    }

    @Test
    fun aTouchItIsNotInIsNothingOfItsAndAnotherTouchIsLeftAlone() {
        assertEquals(Step.Ignore, t.step(Action.Move, 0, tracked = false))
        assertEquals("the last up, not the grip's", Step.End, t.step(Action.Up, 0, tracked = false))
        // its end never came here: a new touch, unheld, is not taken for it
        assertEquals(Step.Abandon, t.step(Action.Down, 0, tracked = false))
        assertEquals(Step.Abandon, t.step(Action.Down, 1, tracked = true))
    }

    @Test
    fun cancelledOrASecondFingerEndsItUnlifted() {
        assertEquals(Step.End, t.step(Action.Cancel, 1, tracked = true))
        assertEquals(Step.End, t.step(Action.PointerDown, 2, tracked = true))
        assertEquals(Step.Ignore, t.step(Action.Other, 1, tracked = true))
    }
}
