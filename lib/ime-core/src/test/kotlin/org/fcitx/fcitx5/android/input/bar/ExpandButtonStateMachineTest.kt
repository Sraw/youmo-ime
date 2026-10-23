/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToAttachWindow
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToDetachWindow
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.Hidden
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesAttached
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesDetached
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chevron at the right of the candidate strip. It is [Hidden] when there is nothing to
 * expand, points down when the expanded candidate window can be opened, and up when it can
 * be closed again.
 */
class ExpandButtonStateMachineTest {

    private val seen = mutableListOf<State>()

    private fun machine(state: State, expandedEmpty: Boolean) =
        ExpandButtonStateMachine.new { seen += it }.apply {
            setBooleanState(ExpandedCandidatesEmpty, expandedEmpty)
            if (state != currentState) unsafeJump(state)
            seen.clear()
        }

    private fun assertTransition(
        from: State,
        event: TransitionEvent,
        expandedEmpty: Boolean,
        expected: State,
    ) {
        val m = machine(from, expandedEmpty)
        m.push(event)
        assertEquals("$from + $event (expandedEmpty=$expandedEmpty)", expected, m.currentState)
        if (expected == from) {
            assertTrue("a no-op transition must not notify", seen.isEmpty())
        } else {
            assertEquals(listOf(expected), seen)
        }
    }

    @Test
    fun startsHiddenWithNoExpandedCandidates() {
        val m = ExpandButtonStateMachine.new { seen += it }
        assertEquals(Hidden, m.currentState)
        assertEquals(true, m.getBooleanState(ExpandedCandidatesEmpty))
    }

    @Test
    fun eachEventMovesTheButtonAsTheCandidatesLeft() = assertRows(
        "the button appears when expanded candidates arrive" to { assertTransition(Hidden, ExpandedCandidatesUpdated, expandedEmpty = false, expected = ClickToAttachWindow) },
        "the button stays hidden without expanded candidates" to { assertTransition(Hidden, ExpandedCandidatesUpdated, expandedEmpty = true, expected = Hidden) },
        "the button disappears when expanded candidates run out" to { assertTransition(ClickToAttachWindow, ExpandedCandidatesUpdated, expandedEmpty = true, expected = Hidden) },
        "the button stays visible while expanded candidates remain" to { assertTransition(ClickToAttachWindow, ExpandedCandidatesUpdated, expandedEmpty = false, expected = ClickToAttachWindow) },
        // no rule from ClickToDetachWindow for this event: while the window is open the button
        // keeps meaning "close me", even if the candidate list changes underneath
        "updating candidates while the window is open does not change the button (none left)" to { assertTransition(ClickToDetachWindow, ExpandedCandidatesUpdated, expandedEmpty = true, expected = ClickToDetachWindow) },
        "updating candidates while the window is open does not change the button (some left)" to { assertTransition(ClickToDetachWindow, ExpandedCandidatesUpdated, expandedEmpty = false, expected = ClickToDetachWindow) },
        "attaching flips the button to detach (candidates left)" to { assertTransition(ClickToAttachWindow, ExpandedCandidatesAttached, expandedEmpty = false, expected = ClickToDetachWindow) },
        "attaching flips the button to detach regardless of the candidate flag" to { assertTransition(ClickToAttachWindow, ExpandedCandidatesAttached, expandedEmpty = true, expected = ClickToDetachWindow) },
        "attaching while hidden is a no-op" to { assertTransition(Hidden, ExpandedCandidatesAttached, expandedEmpty = false, expected = Hidden) },
        "attaching while attached is a no-op" to { assertTransition(ClickToDetachWindow, ExpandedCandidatesAttached, expandedEmpty = false, expected = ClickToDetachWindow) },
        "detaching returns to attach when candidates remain" to { assertTransition(ClickToDetachWindow, ExpandedCandidatesDetached, expandedEmpty = false, expected = ClickToAttachWindow) },
        "detaching hides the button when nothing is left to expand" to { assertTransition(ClickToDetachWindow, ExpandedCandidatesDetached, expandedEmpty = true, expected = Hidden) },
        "detaching while hidden is a no-op" to { assertTransition(Hidden, ExpandedCandidatesDetached, expandedEmpty = false, expected = Hidden) },
        "detaching while detached is a no-op" to { assertTransition(ClickToAttachWindow, ExpandedCandidatesDetached, expandedEmpty = false, expected = ClickToAttachWindow) },
    )

    // region realistic sequences

    @Test
    fun openAndCloseTheExpandedWindowWithCandidatesStillPresent() {
        val m = ExpandButtonStateMachine.new { seen += it }

        m.push(ExpandedCandidatesUpdated, ExpandedCandidatesEmpty to false)
        assertEquals(ClickToAttachWindow, m.currentState)

        m.push(ExpandedCandidatesAttached)
        assertEquals(ClickToDetachWindow, m.currentState)

        m.push(ExpandedCandidatesDetached)
        assertEquals(ClickToAttachWindow, m.currentState)

        assertEquals(listOf(ClickToAttachWindow, ClickToDetachWindow, ClickToAttachWindow), seen)
    }

    /** Candidates disappear while the expanded window is open, so closing it hides the button. */
    @Test
    fun candidatesVanishingWhileOpenHidesTheButtonOnClose() {
        val m = ExpandButtonStateMachine.new { seen += it }
        m.push(ExpandedCandidatesUpdated, ExpandedCandidatesEmpty to false)
        m.push(ExpandedCandidatesAttached)

        m.push(ExpandedCandidatesUpdated, ExpandedCandidatesEmpty to true)
        assertEquals("still showing close while open", ClickToDetachWindow, m.currentState)

        m.push(ExpandedCandidatesDetached)
        assertEquals(Hidden, m.currentState)
    }

    // endregion
}
