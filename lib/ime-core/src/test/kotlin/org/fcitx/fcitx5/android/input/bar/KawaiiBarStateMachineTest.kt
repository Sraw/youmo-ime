/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.CandidateEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.PreeditEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.State
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.State.Candidate
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.State.Idle
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.State.Title
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.ExtendedWindowAttached
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.PreeditUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.WindowDetached
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar above the keyboard: [Idle] shows the toolbar, [Candidate] shows the candidate strip,
 * [Title] shows the title of an attached window (symbol picker, clipboard, ...).
 */
class KawaiiBarStateMachineTest {


    private val seen = mutableListOf<State>()

    /** Builds a machine parked in [state] with the given flags, listener history cleared. */
    private fun machine(state: State, preeditEmpty: Boolean, candidateEmpty: Boolean) =
        KawaiiBarStateMachine.new { seen += it }.apply {
            setBooleanState(PreeditEmpty, preeditEmpty)
            setBooleanState(CandidateEmpty, candidateEmpty)
            if (state != currentState) unsafeJump(state)
            seen.clear()
        }

    private fun assertTransition(
        from: State,
        event: TransitionEvent,
        preeditEmpty: Boolean,
        candidateEmpty: Boolean,
        expected: State,
    ) {
        val m = machine(from, preeditEmpty, candidateEmpty)
        m.push(event)
        assertEquals(
            "$from + $event (preeditEmpty=$preeditEmpty, candidateEmpty=$candidateEmpty)",
            expected,
            m.currentState,
        )
        if (expected == from) {
            assertTrue("a no-op transition must not notify", seen.isEmpty())
        } else {
            assertEquals("exactly one notification", listOf(expected), seen)
        }
    }

    @Test
    fun startsIdleWithEverythingEmpty() {
        val m = KawaiiBarStateMachine.new { seen += it }
        assertEquals(Idle, m.currentState)
        assertEquals(true, m.getBooleanState(PreeditEmpty))
        assertEquals(true, m.getBooleanState(CandidateEmpty))
    }

    // region PreeditUpdated

    @Test
    fun preeditUpdatedEntersCandidateOnlyWhenBothPreeditAndCandidatesArePresent() {
        assertTransition(Idle, PreeditUpdated, preeditEmpty = false, candidateEmpty = false, expected = Candidate)
        assertTransition(Idle, PreeditUpdated, preeditEmpty = false, candidateEmpty = true, expected = Idle)
        assertTransition(Idle, PreeditUpdated, preeditEmpty = true, candidateEmpty = false, expected = Idle)
        assertTransition(Idle, PreeditUpdated, preeditEmpty = true, candidateEmpty = true, expected = Idle)
    }

    @Test
    fun preeditUpdatedLeavesCandidateWhenThePreeditIsCleared() {
        assertTransition(Candidate, PreeditUpdated, preeditEmpty = true, candidateEmpty = false, expected = Idle)
        assertTransition(Candidate, PreeditUpdated, preeditEmpty = true, candidateEmpty = true, expected = Idle)
    }

    @Test
    fun preeditUpdatedKeepsCandidateWhileThePreeditIsNonEmpty() {
        assertTransition(Candidate, PreeditUpdated, preeditEmpty = false, candidateEmpty = false, expected = Candidate)
        assertTransition(Candidate, PreeditUpdated, preeditEmpty = false, candidateEmpty = true, expected = Candidate)
    }

    @Test
    fun preeditUpdatedNeverDisturbsAnAttachedWindow() {
        for (preedit in listOf(true, false)) {
            for (candidate in listOf(true, false)) {
                assertTransition(Title, PreeditUpdated, preedit, candidate, expected = Title)
            }
        }
    }

    // endregion

    // region CandidatesUpdated

    @Test
    fun candidatesUpdatedEntersCandidateAsSoonAsThereAreCandidates() {
        assertTransition(Idle, CandidatesUpdated, preeditEmpty = true, candidateEmpty = false, expected = Candidate)
        assertTransition(Idle, CandidatesUpdated, preeditEmpty = false, candidateEmpty = false, expected = Candidate)
    }

    @Test
    fun candidatesUpdatedStaysIdleWithoutCandidates() {
        assertTransition(Idle, CandidatesUpdated, preeditEmpty = true, candidateEmpty = true, expected = Idle)
        assertTransition(Idle, CandidatesUpdated, preeditEmpty = false, candidateEmpty = true, expected = Idle)
    }

    @Test
    fun candidatesUpdatedLeavesCandidateOnlyWhenPreeditAndCandidatesAreBothEmpty() {
        assertTransition(Candidate, CandidatesUpdated, preeditEmpty = true, candidateEmpty = true, expected = Idle)
        assertTransition(Candidate, CandidatesUpdated, preeditEmpty = false, candidateEmpty = true, expected = Candidate)
        assertTransition(Candidate, CandidatesUpdated, preeditEmpty = true, candidateEmpty = false, expected = Candidate)
        assertTransition(Candidate, CandidatesUpdated, preeditEmpty = false, candidateEmpty = false, expected = Candidate)
    }

    @Test
    fun candidatesUpdatedNeverDisturbsAnAttachedWindow() {
        for (preedit in listOf(true, false)) {
            for (candidate in listOf(true, false)) {
                assertTransition(Title, CandidatesUpdated, preedit, candidate, expected = Title)
            }
        }
    }

    // endregion

    // region window attach / detach

    @Test
    fun attachingAWindowAlwaysShowsTheTitleRegardlessOfFlags() {
        for (from in listOf(Idle, Candidate)) {
            for (preedit in listOf(true, false)) {
                for (candidate in listOf(true, false)) {
                    assertTransition(from, ExtendedWindowAttached, preedit, candidate, expected = Title)
                }
            }
        }
    }

    @Test
    fun attachingAWindowWhileAlreadyShowingATitleIsANoOp() {
        assertTransition(Title, ExtendedWindowAttached, preeditEmpty = true, candidateEmpty = true, expected = Title)
    }

    /**
     * `WindowDetached` declares two rules from [Title]; the guarded one is written first so it
     * wins when candidates exist. This pins that ordering -- reordering the rules in the source
     * would silently send the bar to [Idle] with candidates still on screen.
     */
    @Test
    fun detachingAWindowPrefersCandidateOverIdleWhenCandidatesExist() {
        assertTransition(Title, WindowDetached, preeditEmpty = true, candidateEmpty = false, expected = Candidate)
        assertTransition(Title, WindowDetached, preeditEmpty = false, candidateEmpty = false, expected = Candidate)
    }

    @Test
    fun detachingAWindowFallsBackToIdleWithoutCandidates() {
        assertTransition(Title, WindowDetached, preeditEmpty = true, candidateEmpty = true, expected = Idle)
        assertTransition(Title, WindowDetached, preeditEmpty = false, candidateEmpty = true, expected = Idle)
    }

    @Test
    fun detachingAWindowDoesNothingWhenNoWindowIsAttached() {
        for (from in listOf(Idle, Candidate)) {
            assertTransition(from, WindowDetached, preeditEmpty = true, candidateEmpty = false, expected = from)
        }
    }

    // endregion

    // region realistic sequences

    /** Type a character, pick a candidate, end up back on the toolbar. */
    @Test
    fun typingThenCommittingReturnsToIdle() {
        val m = KawaiiBarStateMachine.new { seen += it }

        m.push(PreeditUpdated, PreeditEmpty to false)
        assertEquals("no candidates yet", Idle, m.currentState)

        m.push(CandidatesUpdated, CandidateEmpty to false)
        assertEquals(Candidate, m.currentState)

        m.push(CandidatesUpdated, CandidateEmpty to true)
        assertEquals("preedit is still there", Candidate, m.currentState)

        m.push(PreeditUpdated, PreeditEmpty to true)
        assertEquals(Idle, m.currentState)

        assertEquals(listOf(Candidate, Idle), seen)
    }

    /** Open the symbol picker mid-composition and close it again. */
    @Test
    fun openingAWindowMidCompositionRestoresTheCandidateBar() {
        val m = KawaiiBarStateMachine.new { seen += it }
        m.push(PreeditUpdated, PreeditEmpty to false, CandidateEmpty to false)
        assertEquals(Candidate, m.currentState)

        m.push(ExtendedWindowAttached)
        assertEquals(Title, m.currentState)

        m.push(WindowDetached)
        assertEquals("candidates are still present, so the strip comes back", Candidate, m.currentState)
    }

    // endregion
}
