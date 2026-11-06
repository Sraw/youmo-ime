/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.VoiceTap.Act
import org.fcitx.fcitx5.android.input.voice.VoiceTap.Press
import org.fcitx.fcitx5.android.input.voice.VoiceTap.State
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTapTest {

    // [press] on a talk in [state]: what it does, and the state after
    private fun on(state: State, press: Press): Pair<Act, State> {
        val tap = VoiceTap()
        when (state) {
            State.LISTENING -> {}
            State.STOPPING -> tap.press(Press.MIC)
            State.OVER -> tap.ended()
        }
        assertEquals(state, tap.state)
        return tap.press(press) to tap.state
    }

    @Test
    fun whileListeningEveryStopStops() {
        for (press in listOf(Press.MIC, Press.MASK, Press.OUTSIDE, Press.QUIET)) {
            assertEquals(press.name, Act.STOP to State.STOPPING, on(State.LISTENING, press))
        }
    }

    @Test
    fun cancelDropsAndAnotherFieldTypesAtOnce() {
        assertEquals(Act.DROP to State.OVER, on(State.LISTENING, Press.CANCEL))
        assertEquals(Act.TYPE_NOW to State.OVER, on(State.LISTENING, Press.INPUT_ENDED))
        assertEquals(Act.DROP to State.OVER, on(State.STOPPING, Press.CANCEL))
        assertEquals(Act.TYPE_NOW to State.OVER, on(State.STOPPING, Press.INPUT_ENDED))
    }

    @Test
    fun theKeyboardHiddenStopsAndWaitsForTheRest() {
        assertEquals(Act.HIDE to State.STOPPING, on(State.LISTENING, Press.HIDDEN))
        // stopping already: the tail runs on, what is left still typed straight in
        assertEquals(Act.HIDE to State.STOPPING, on(State.STOPPING, Press.HIDDEN))
        // hidden, then another field before it was recognized: typed at once
        val tap = VoiceTap()
        tap.press(Press.HIDDEN)
        assertEquals(Act.TYPE_NOW, tap.press(Press.INPUT_ENDED))
    }

    @Test
    fun aPressWhileStoppingEndsTheTailTheQuietNothing() {
        for (press in listOf(Press.MIC, Press.MASK, Press.OUTSIDE)) {
            assertEquals(press.name, Act.CUT to State.STOPPING, on(State.STOPPING, press))
        }
        assertEquals(Act.NONE to State.STOPPING, on(State.STOPPING, Press.QUIET))
    }

    @Test
    fun overNothingActs() {
        for (press in Press.entries) assertEquals(press.name, Act.NONE to State.OVER, on(State.OVER, press))
    }
}
