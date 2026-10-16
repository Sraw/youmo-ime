/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.session.sessionTestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tap in the preedit while a table looks a character up by pinyin: the preedit and its cursor are the pinyin's. */
class TablePinyinLookupTest {

    /**
     * Shows what it is typed, 我 for wo; keys go in at its cursor and backspace deletes before it,
     * Enter gives the keys as it has them, as PinyinSession does; records every action.
     */
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
            var commit = ""
            val end = if (at < 0) typed.length else at
            when (action) {
                is Action.Key -> {
                    typed.insert(end, action.char)
                    if (at >= 0) at++
                }
                Action.Backspace -> if (end > 0) {
                    typed.deleteCharAt(end - 1)
                    if (at >= 0) at--
                }
                is Action.Cursor -> at = action.at
                Action.CommitRaw, Action.Reset -> {
                    if (action == Action.CommitRaw) commit = typed.toString()
                    typed.setLength(0)
                    at = -1
                }
                else -> Unit
            }
            val candidates = if (typed.toString() == "wo") listOf("我") else emptyList()
            return Snapshot(commit, typed.toString(), candidates, 0, hasPreviousPage = false, hasNextPage = false, handled = true, predicting = false, cursor = at)
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

    @Test
    fun keysMendedAtThePinyinsCursorAreCommittedAsItHasThemWithThePinyinKey() {
        val t = TableSession(dictionary("trnt 我", "wqiy 你"), TableOptions.WUBI, Pinyin())
        t.type("zwo")
        t.apply(Action.Cursor(0))
        assertEquals("nwo", t.apply(Action.Key('n')).preedit)
        // as many keys as the pinyin's, not in the order typed: nothing picked
        assertEquals("znwo", t.apply(Action.CommitRaw).commit)
        // a key the app takes commits them so too
        t.type("zwo")
        t.apply(Action.Cursor(0))
        t.apply(Action.Key('n'))
        val comma = t.apply(Action.Key(','))
        assertEquals("znwo", comma.commit)
        assertFalse(comma.handled)
        // one deleted in the middle
        t.type("zwo")
        t.apply(Action.Cursor(1))
        assertEquals("o", t.apply(Action.Backspace).preedit)
        assertEquals("zo", t.apply(Action.CommitRaw).commit)
    }

    // the fake above against the real thing: PinyinSession's Enter gives the keys as it has them
    @Test
    fun keysMendedAtARealPinyinsCursorAreCommittedAsItHasThem() {
        val t = TableSession(dictionary("trnt 我", "wqiy 你"), TableOptions.WUBI, PinyinSession(sessionTestData(), PinyinSegmenter(), prediction = false))
        t.type("zwo")
        t.apply(Action.Cursor(0))
        t.apply(Action.Key('n'))
        assertEquals("znwo", t.apply(Action.CommitRaw).commit)
        // one deleted in the middle
        t.type("zwo")
        t.apply(Action.Cursor(1))
        t.apply(Action.Backspace)
        assertEquals("zo", t.apply(Action.CommitRaw).commit)
    }
}
