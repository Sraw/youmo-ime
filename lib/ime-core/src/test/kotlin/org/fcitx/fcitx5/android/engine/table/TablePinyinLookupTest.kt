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

/**
 * A tap in the preedit while a table looks a character up by pinyin is fcitx's, as it is in a code:
 * reported handled, fcitx's own handling (the preedit committed, then a reset) was skipped.
 */
class TablePinyinLookupTest {

    /** Shows what it is typed, 我 for wo; records every action. */
    private class Pinyin : Session {
        val actions = ArrayList<Action>()
        private val typed = StringBuilder()
        override var learning = true

        override fun reads(c: Char) = c in 'a'..'z'
        override fun candidates(from: Int, count: Int) = emptyList<Choice>()
        override fun offers(index: Int) = emptySet<Offer>()

        override fun apply(action: Action): Snapshot {
            actions += action
            if (action is Action.Key) typed.append(action.char)
            if (action == Action.Reset) typed.setLength(0)
            val candidates = if (typed.toString() == "wo") listOf("我") else emptyList()
            return Snapshot("", typed.toString(), candidates, 0, hasPreviousPage = false, hasNextPage = false, handled = true, predicting = false)
        }
    }

    @Test
    fun aTapInALookupsPreeditIsLeftToFcitxAsItWas() {
        val pinyin = Pinyin()
        val t = TableSession(dictionary("trnt 我", "wqiy 你"), TableOptions.WUBI, pinyin)
        val (_, typed) = t.type("zwo")
        assertEquals(listOf("我"), typed.candidates)
        assertEquals(typed.copy(handled = false), t.apply(Action.Cursor(1)))
        assertTrue("the pinyin is not told", pinyin.actions.none { it is Action.Cursor })
        // the pinyin key alone typed
        t.apply(Action.Reset)
        val (_, z) = t.type("z")
        assertEquals(z.copy(handled = false), t.apply(Action.Cursor(0)))
    }
}
