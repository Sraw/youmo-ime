/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cursor in a table's code: a tap on the code row or an arrow puts it, keys go in there. */
class TableCursorTest {

    private val wubi = dictionary("w 人", "wqiy 你", "wun 们", "wqvb 你好", "vbg 好", "trnt 我")

    @Test
    fun aKeyMendedIntoTheCodeCommitsItWholeAsTypingItWould() {
        // a key leading nowhere commits nothing, as in 仓颉: wqy stays to be mended
        val t = TableSession(wubi, TableOptions.WUBI.copy(noMatchAutoSelectLength = 0))
        val (_, typed) = t.type("wqy")
        assertEquals("wqy", typed.preedit)
        assertEquals(-1, typed.cursor)
        // after the q
        val tapped = t.apply(Action.Cursor(2))
        assertTrue(tapped.handled)
        assertEquals(2, tapped.cursor)
        assertEquals("你", t.apply(Key('i')).commit)
    }

    @Test
    fun aCodeKeyBeforeTheEndGoesInThereCommittingNothing() {
        val t = TableSession(wubi, TableOptions.WUBI)
        t.type("wqi")
        // at the start: nothing before it to delete, not even the app's
        assertEquals(0, t.apply(Action.Step.START).cursor)
        val start = t.apply(Backspace)
        assertTrue(start.handled)
        assertEquals("wqi", start.preedit)
        // at the end t would commit 你 and start a code of its own; here it is mended into this one
        val typed = t.apply(Key('t'))
        assertEquals("", typed.commit)
        assertEquals("twqi", typed.preedit)
        assertEquals(1, typed.cursor)
        assertEquals("wqi", t.apply(Backspace).preedit)
        assertEquals(-1, t.apply(Action.Step(9)).cursor)
    }

    @Test
    fun aFullCodeTakesNoKeyBeforeItsEnd() {
        val t = TableSession(wubi, TableOptions.WUBI.copy(noMatchAutoSelectLength = 0, autoSelect = false))
        t.type("wqiy")
        t.apply(Action.Step(-2))
        val s = t.apply(Key('w'))
        assertEquals("wqiy", s.preedit)
        assertEquals(2, s.cursor)
    }

    @Test
    fun nothingTypedTheTapAndTheArrowAreTheApps() {
        val t = TableSession(wubi, TableOptions.WUBI)
        assertFalse(t.apply(Action.Cursor(0)).handled)
        assertFalse(t.apply(Action.Step(-1)).handled)
    }
}
