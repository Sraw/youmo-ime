/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardDbEmpty
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardListeningEnabled
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.AddMore
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.EnableListening
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.Normal
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardDbUpdated
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardListeningUpdated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clipboard window has three faces: [Normal] lists entries, [AddMore] prompts the user to
 * copy something, and [EnableListening] prompts them to turn clipboard listening on.
 *
 * Unlike the other two machines this one takes its starting state from the caller, so the
 * tests construct it directly rather than jumping.
 */
class ClipboardStateMachineTest {

    private val seen = mutableListOf<State>()

    private fun machine(initial: State, empty: Boolean, listening: Boolean) =
        ClipboardStateMachine.new(initial, empty, listening) { seen += it }.apply { seen.clear() }

    private fun assertTransition(
        from: State,
        event: TransitionEvent,
        empty: Boolean,
        listening: Boolean,
        expected: State,
    ) {
        val m = machine(from, empty, listening)
        m.push(event)
        assertEquals("$from + $event (empty=$empty, listening=$listening)", expected, m.currentState)
        if (expected == from) {
            assertTrue("a no-op transition must not notify", seen.isEmpty())
        } else {
            assertEquals(listOf(expected), seen)
        }
    }

    @Test
    fun eachUpdateGoesWhereTheFlagsSay() = assertRows(
        "an empty database prompts the user to copy something" to { assertTransition(Normal, ClipboardDbUpdated, empty = true, listening = true, expected = AddMore) },
        "a non-empty database shows the list" to { assertTransition(AddMore, ClipboardDbUpdated, empty = false, listening = true, expected = Normal) },
        "a database update that changes nothing is a no-op (list)" to { assertTransition(Normal, ClipboardDbUpdated, empty = false, listening = true, expected = Normal) },
        "a database update that changes nothing is a no-op (prompt)" to { assertTransition(AddMore, ClipboardDbUpdated, empty = true, listening = true, expected = AddMore) },
        "the database update ignores the listening flag (to the prompt)" to { assertTransition(Normal, ClipboardDbUpdated, empty = true, listening = false, expected = AddMore) },
        "the database update ignores the listening flag (to the list)" to { assertTransition(AddMore, ClipboardDbUpdated, empty = false, listening = false, expected = Normal) },
        "turning listening off prompts to enable it, from the list" to { assertTransition(Normal, ClipboardListeningUpdated, empty = false, listening = false, expected = EnableListening) },
        "turning listening off prompts to enable it, from the copy prompt" to { assertTransition(AddMore, ClipboardListeningUpdated, empty = true, listening = false, expected = EnableListening) },
        "turning listening on goes to the list when the database has entries" to { assertTransition(EnableListening, ClipboardListeningUpdated, empty = false, listening = true, expected = Normal) },
        "turning listening on goes to the copy prompt when the database is empty" to { assertTransition(EnableListening, ClipboardListeningUpdated, empty = true, listening = true, expected = AddMore) },
        "a listening update that changes nothing is a no-op (list)" to { assertTransition(Normal, ClipboardListeningUpdated, empty = false, listening = true, expected = Normal) },
        "a listening update that changes nothing is a no-op (copy prompt)" to { assertTransition(AddMore, ClipboardListeningUpdated, empty = true, listening = true, expected = AddMore) },
        "a listening update that changes nothing is a no-op (listening prompt)" to { assertTransition(EnableListening, ClipboardListeningUpdated, empty = true, listening = false, expected = EnableListening) },
        // an asymmetry worth knowing: leaving Normal or AddMore for the listening prompt ignores
        // the database flag, so listening off from Normal with an empty database skips AddMore
        "leaving the list for the listening prompt ignores the database flag" to { assertTransition(Normal, ClipboardListeningUpdated, empty = true, listening = false, expected = EnableListening) },
        "leaving the copy prompt for the listening prompt ignores the database flag" to { assertTransition(AddMore, ClipboardListeningUpdated, empty = false, listening = false, expected = EnableListening) },
    )

    @Test
    fun theCallerSuppliesTheInitialFlags() {
        val m = machine(Normal, empty = false, listening = true)
        assertEquals(false, m.getBooleanState(ClipboardDbEmpty))
        assertEquals(true, m.getBooleanState(ClipboardListeningEnabled))
    }

    // region ClipboardDbUpdated

    /**
     * No rule leaves [EnableListening] on a database update: while listening is off the prompt
     * stays put no matter what lands in the database.
     */
    @Test
    fun aDatabaseUpdateCannotDismissTheListeningPrompt() {
        for (empty in listOf(true, false)) {
            for (listening in listOf(true, false)) {
                assertTransition(EnableListening, ClipboardDbUpdated, empty, listening, expected = EnableListening)
            }
        }
    }

    // endregion

    // region realistic sequences

    /** Open the clipboard with listening off, enable it, then copy something. */
    @Test
    fun enablingListeningThenCopyingReachesTheList() {
        val m = machine(EnableListening, empty = true, listening = false)

        m.push(ClipboardListeningUpdated, ClipboardListeningEnabled to true)
        assertEquals("nothing copied yet", AddMore, m.currentState)

        m.push(ClipboardDbUpdated, ClipboardDbEmpty to false)
        assertEquals(Normal, m.currentState)

        assertEquals(listOf(AddMore, Normal), seen)
    }

    /** Turning listening off from the list, then back on, returns to the list. */
    @Test
    fun togglingListeningOffAndOnRestoresTheList() {
        val m = machine(Normal, empty = false, listening = true)

        m.push(ClipboardListeningUpdated, ClipboardListeningEnabled to false)
        assertEquals(EnableListening, m.currentState)

        m.push(ClipboardListeningUpdated, ClipboardListeningEnabled to true)
        assertEquals(Normal, m.currentState)
    }

    /** Clearing the clipboard history while listening is on falls back to the copy prompt. */
    @Test
    fun clearingHistoryFallsBackToTheCopyPrompt() {
        val m = machine(Normal, empty = false, listening = true)
        m.push(ClipboardDbUpdated, ClipboardDbEmpty to true)
        assertEquals(AddMore, m.currentState)
    }

    // endregion
}
