/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tap in the preedit while a table looks a character up by pinyin: the preedit and its cursor are the pinyin's. */
class TablePinyinLookupTest {

    /** Shows what it is typed, 我 for wo; records every action. */
    private class Pinyin : Session {
        val actions = ArrayList<Action>()
        private val typed = StringBuilder()
        private var at = -1
        override var learning = true

        override fun reads(c: Char) = c in 'a'..'z'
        override fun candidates(from: Int, count: Int) = emptyList<Choice>()
        override fun offers(index: Int) = emptySet<Offer>()

        override fun apply(action: Action): Snapshot {
            actions += action
            if (action is Action.Key) typed.append(action.char)
            if (action == Action.Backspace) typed.setLength(typed.length - 1)
            if (action is Action.Cursor) at = action.at
            if (action == Action.Reset) typed.setLength(0)
            val candidates = if (typed.toString() == "wo") listOf("我") else emptyList()
            return Snapshot("", typed.toString(), candidates, 0, hasPreviousPage = false, hasNextPage = false, handled = true, predicting = false, cursor = at)
        }
    }

    @Test
    fun aTapInALookupIsThePinyins() {
        val pinyin = Pinyin()
        val t = TableSession(dictionary("trnt 我", "wqiy 你"), TableOptions.WUBI, pinyin)
        t.type("zwo")
        val tapped = t.apply(Action.Cursor(1))
        assertTrue(tapped.handled)
        assertEquals(1, tapped.cursor)
        assertEquals(listOf(Action.Cursor(1)), pinyin.actions.filterIsInstance<Action.Cursor>())
        // the pinyin key alone typed: nothing to put the cursor in
        t.apply(Action.Reset)
        val (_, z) = t.type("z")
        assertEquals(z, t.apply(Action.Cursor(0)))
    }

    @Test
    fun backspaceWithThePinyinsCursorAtItsStartDeletesNothing() {
        val pinyin = Pinyin()
        val t = TableSession(dictionary("trnt 我", "wqiy 你"), TableOptions.WUBI, pinyin)
        t.type("zwo")
        t.apply(Action.Cursor(0))
        assertEquals("wo", t.apply(Action.Backspace).preedit)
        assertTrue("the pinyin is not told", pinyin.actions.none { it == Action.Backspace })
    }
}
