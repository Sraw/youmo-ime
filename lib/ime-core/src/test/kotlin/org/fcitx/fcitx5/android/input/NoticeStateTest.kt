/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.NoticeState.Companion.SAID_MS
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
        "a line said, 2 s" to { assertEquals(SAID_MS, NoticeState<String>().show(null, steady = false).ms) },
        "one with 撤销, 6 s" to { assertEquals(UNDO_MS, NoticeState<String>().show("删", steady = false).ms) },
        // F3464: rule 4, said once as a key fails, read before it goes
        "a failure, 6 s" to { assertEquals(UNDO_MS, NoticeState<String>().show(null, steady = true).ms) },
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
}
