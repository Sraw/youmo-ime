/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.NoticeState.Companion.SAID_MS
import org.fcitx.fcitx5.android.input.NoticeState.Cover
import org.fcitx.fcitx5.android.input.NoticeState.Companion.UNDO_MS
import org.fcitx.fcitx5.android.input.NoticeState.Shown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeStateTest {

    @Test
    fun howLongEachStays() = assertRows(
        // rule 5: nothing to reach for
        "a line said, 1 s" to { assertEquals(1000L, NoticeState<String>().show(null, steady = false).ms) },
        "one with 撤销, 3 s" to { assertEquals(3000L, NoticeState<String>().show("删", steady = false).ms) },
        // rule 4, said once as a key fails, read before it goes
        "a failure, 2 s" to { assertEquals(2000L, NoticeState<String>().show(null, steady = true).ms) },
        // a button to reach for wins
        "a failure with a button, 3 s" to { assertEquals(3000L, NoticeState<String>().show("删", steady = true).ms) },
        // news of several lines: the time its sayer asks, the kinds' own unchanged
        "a time asked, that" to { assertEquals(4000L, NoticeState<String>().show(null, steady = false, ms = 4000L).ms) },
        "a time asked, typed away all the same" to {
            assertTrue(NoticeState<String>().apply { show(null, steady = false, ms = 4000L) }.typed())
        },
    )

    // a table's words offered: a word tapped as it goes is not taken
    @Test
    fun anOffersWordTakesATapOnlyWhileItIsShown() = assertRows(
        "shown, no time of its own, typing puts it away" to {
            val s = NoticeState<String>()
            assertEquals(NoticeState.STAYS, s.show(null, steady = false, ms = NoticeState.STAYS).ms)
            assertTrue(s.choose())
            assertTrue(s.typed())
        },
        "going" to { assertFalse(NoticeState<String>().apply { show(null, steady = false) }.apply { dismiss() }.choose()) },
    )

    @Test
    fun whetherTypingPutsItAway() = assertRows(
        "what was done goes" to { assertTrue(NoticeState<String>().apply { show(null, steady = false) }.typed()) },
        // the key the engine failed on comes after the notice
        "a failure stays" to { assertFalse(NoticeState<String>().apply { show(null, steady = true) }.typed()) },
        // one exit, not started again by each key
        "one on its way out goes on" to {
            assertFalse(NoticeState<String>().apply { show(null, steady = false); dismiss() }.typed())
        },
        "a new one after it went goes again" to {
            assertTrue(NoticeState<String>().apply { show(null, steady = false); dismiss(); gone(); show(null, steady = false) }.typed())
        },
        "a new one in the place of one going is typed away" to {
            assertTrue(NoticeState<String>().apply { show(null, steady = false); dismiss(); show(null, steady = false) }.typed())
        },
        // each show says anew whether it stays: a failure's steadiness is not carried over
        "a line in a failure's place is typed away" to {
            assertTrue(NoticeState<String>().apply { show(null, steady = true); show(null, steady = false) }.typed())
        },
    )

    @Test
    fun aNewOneLapsesTheShownOnesAct() {
        val s = NoticeState<String>()
        assertEquals(Shown<String>(null, UNDO_MS), s.show("一", steady = false))
        assertEquals("replaced, its act stands", Shown("一", SAID_MS), s.show(null, steady = false))
        assertNull("lapsed once", s.dismiss())
    }

    @Test
    fun undoTakesBackOnlyThePendingOne() {
        val s = NoticeState<String>()
        s.show("一", steady = false)
        s.show("二", steady = false)
        assertFalse("the faded one's 撤销 takes nothing back", s.undo("一"))
        assertTrue(s.undo("二"))
        assertNull("taken, not lapsed after", s.dismiss())
        assertFalse("once", s.undo("二"))
    }

    @Test
    fun dismissedItLapsesOnce() {
        val s = NoticeState<String>()
        s.show("删", steady = false)
        assertEquals("删", s.dismiss())
        assertNull(s.dismiss())
    }

    // another notice waits while an act's 撤销 still works, and only then
    @Test
    fun whetherItHoldsAnUndo() = assertRows(
        "shown with 撤销" to { assertTrue(NoticeState<String>().apply { show("删", steady = false) }.holdsUndo) },
        "shown without" to { assertFalse(NoticeState<String>().apply { show(null, steady = false) }.holdsUndo) },
        "put away: lapsed" to { assertFalse(NoticeState<String>().apply { show("删", steady = false); dismiss() }.holdsUndo) },
        "taken back" to { assertFalse(NoticeState<String>().apply { show("删", steady = false); undo("删") }.holdsUndo) },
        "replaced by one without: the act lapsed" to {
            assertFalse(NoticeState<String>().apply { show("删", steady = false); show(null, steady = false) }.holdsUndo)
        },
        "none shown yet" to { assertFalse(NoticeState<String>().holdsUndo) },
    )

    // the edit menu goes where the notices go, under their window
    @Test
    fun theEditMenuPutsAwayOrHides() = assertRows(
        "what was done goes, as typing would" to {
            val n = NoticeState<String>().apply { show("删", steady = false) }
            assertEquals(Cover.Away, n.cover())
            assertFalse("not back", n.uncover())
        },
        "a failure is hidden, and back as the menu goes" to {
            val n = NoticeState<String>().apply { show(null, steady = true) }
            assertEquals(Cover.Hide, n.cover())
            assertEquals("hidden once", Cover.None, n.cover())
            assertTrue(n.uncover())
            assertFalse("back once", n.uncover())
            assertTrue("still steady", !n.typed())
        },
        "one going already is left to go" to {
            val n = NoticeState<String>().apply { show(null, steady = true); dismiss() }
            assertEquals(Cover.None, n.cover())
            assertFalse(n.uncover())
        },
        "a hidden one put away meanwhile (the keyboard gone) is not back" to {
            val n = NoticeState<String>().apply { show(null, steady = true) }
            n.cover()
            n.dismiss()
            n.gone()
            assertFalse(n.uncover())
        },
        // said while the menu is up where it goes, any notice: not over the menu, not lost
        "one said under the menu is hidden, and back once as it goes" to {
            val n = NoticeState<String>()
            n.show(null, steady = false, hidden = true)
            assertEquals("hidden already", Cover.None, n.cover())
            assertTrue(n.uncover())
            assertFalse(n.uncover())
            assertTrue("then put away by typing as any", n.typed())
        },
        "a new one in its place is shown, not hidden" to {
            val n = NoticeState<String>().apply { show(null, steady = true) }
            n.cover()
            n.show(null, steady = true)
            assertFalse(n.uncover())
            assertEquals("the new one hides in turn", Cover.Hide, n.cover())
        },
    )
}
