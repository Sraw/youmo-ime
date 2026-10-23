/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.assertRows
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

    /** Every pair of preedit-empty and candidates-empty flags. */
    private fun eachFlags(check: (Boolean, Boolean) -> Unit) {
        for (preedit in listOf(true, false)) for (candidate in listOf(true, false)) check(preedit, candidate)
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

    @Test
    fun eachEventMovesTheBarAsThePreeditAndCandidatesSay() = assertRows(
        "a preedit with candidates enters Candidate" to { assertTransition(Idle, PreeditUpdated, preeditEmpty = false, candidateEmpty = false, expected = Candidate) },
        "a preedit without candidates stays Idle" to { assertTransition(Idle, PreeditUpdated, preeditEmpty = false, candidateEmpty = true, expected = Idle) },
        "no preedit, though candidates, stays Idle on a preedit update" to { assertTransition(Idle, PreeditUpdated, preeditEmpty = true, candidateEmpty = false, expected = Idle) },
        "nothing at all stays Idle on a preedit update" to { assertTransition(Idle, PreeditUpdated, preeditEmpty = true, candidateEmpty = true, expected = Idle) },
        "the preedit cleared leaves Candidate (candidates left)" to { assertTransition(Candidate, PreeditUpdated, preeditEmpty = true, candidateEmpty = false, expected = Idle) },
        "the preedit cleared leaves Candidate (none left)" to { assertTransition(Candidate, PreeditUpdated, preeditEmpty = true, candidateEmpty = true, expected = Idle) },
        "Candidate is kept while the preedit is non-empty (candidates)" to { assertTransition(Candidate, PreeditUpdated, preeditEmpty = false, candidateEmpty = false, expected = Candidate) },
        "Candidate is kept while the preedit is non-empty (no candidates)" to { assertTransition(Candidate, PreeditUpdated, preeditEmpty = false, candidateEmpty = true, expected = Candidate) },
        "a preedit update never disturbs an attached window" to {
            eachFlags { preedit, candidate -> assertTransition(Title, PreeditUpdated, preedit, candidate, expected = Title) }
        },
        "candidates enter Candidate as soon as there are some (no preedit)" to { assertTransition(Idle, CandidatesUpdated, preeditEmpty = true, candidateEmpty = false, expected = Candidate) },
        "candidates enter Candidate as soon as there are some (a preedit)" to { assertTransition(Idle, CandidatesUpdated, preeditEmpty = false, candidateEmpty = false, expected = Candidate) },
        "no candidates stay Idle (no preedit)" to { assertTransition(Idle, CandidatesUpdated, preeditEmpty = true, candidateEmpty = true, expected = Idle) },
        "no candidates stay Idle (a preedit)" to { assertTransition(Idle, CandidatesUpdated, preeditEmpty = false, candidateEmpty = true, expected = Idle) },
        "Candidate is left only when preedit and candidates are both empty" to { assertTransition(Candidate, CandidatesUpdated, preeditEmpty = true, candidateEmpty = true, expected = Idle) },
        "a preedit left keeps Candidate though the candidates went" to { assertTransition(Candidate, CandidatesUpdated, preeditEmpty = false, candidateEmpty = true, expected = Candidate) },
        "candidates left keep Candidate (no preedit)" to { assertTransition(Candidate, CandidatesUpdated, preeditEmpty = true, candidateEmpty = false, expected = Candidate) },
        "candidates left keep Candidate (a preedit)" to { assertTransition(Candidate, CandidatesUpdated, preeditEmpty = false, candidateEmpty = false, expected = Candidate) },
        "a candidates update never disturbs an attached window" to {
            eachFlags { preedit, candidate -> assertTransition(Title, CandidatesUpdated, preedit, candidate, expected = Title) }
        },
        "attaching a window always shows the title, whatever the flags" to {
            for (from in listOf(Idle, Candidate)) {
                eachFlags { preedit, candidate -> assertTransition(from, ExtendedWindowAttached, preedit, candidate, expected = Title) }
            }
        },
        "attaching a window while already showing a title is a no-op" to { assertTransition(Title, ExtendedWindowAttached, preeditEmpty = true, candidateEmpty = true, expected = Title) },
        // WindowDetached declares two rules from Title; the guarded one is written first so it wins
        // when candidates exist: reordering them would send the bar to Idle with candidates shown
        "detaching a window prefers Candidate over Idle when candidates exist (no preedit)" to { assertTransition(Title, WindowDetached, preeditEmpty = true, candidateEmpty = false, expected = Candidate) },
        "detaching a window prefers Candidate over Idle when candidates exist (a preedit)" to { assertTransition(Title, WindowDetached, preeditEmpty = false, candidateEmpty = false, expected = Candidate) },
        "detaching a window falls back to Idle without candidates (no preedit)" to { assertTransition(Title, WindowDetached, preeditEmpty = true, candidateEmpty = true, expected = Idle) },
        "detaching a window falls back to Idle without candidates (a preedit)" to { assertTransition(Title, WindowDetached, preeditEmpty = false, candidateEmpty = true, expected = Idle) },
        "detaching does nothing when no window is attached" to {
            for (from in listOf(Idle, Candidate)) assertTransition(from, WindowDetached, preeditEmpty = true, candidateEmpty = false, expected = from)
        },
    )

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
