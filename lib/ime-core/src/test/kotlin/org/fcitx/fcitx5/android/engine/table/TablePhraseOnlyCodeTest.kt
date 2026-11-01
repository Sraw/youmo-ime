/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * A code the table has nothing under, reached by a phrase alone: the phrase is shown, first as
 * the only candidate, but a phrase never commits itself (A1): space, a selection key, a key no
 * code starts and a key past the longest code drop the code as one with no candidate; a tap takes the phrase.
 */
class TablePhraseOnlyCodeTest {
    private val day = { GregorianCalendar(2026, Calendar.OCTOBER, 31) }

    private fun session(key: String, options: TableOptions = TableOptions.WUBI) = TableSession(
        dictionary("w 人", "wq 你"), options, phrases = PhraseBook(CustomPhrases.parse("$key,1=张三\n")), clock = day,
    )

    private fun TableSession.shown() = candidates(0, 20).map { it.text }

    // each key that ends a code on its own: the phrase never committed by it; what else the row checks after
    private class End(val why: String, val options: TableOptions, val end: TableSession.() -> Pair<String, Snapshot>, val after: TableSession.(Snapshot) -> Unit)

    // a table never commits a phrase but by a tap: a digit or space drops such a code (the user's decision)
    @Test
    @Uat("WD-36")
    fun aKeyThatEndsTheCodeCommitsNoPhrase() = assertRows(
        listOf(
            End("space", TableOptions.WUBI, { apply(Action.Select(0)).let { it.commit to it } }, { assertEquals(emptyList<String>(), shown()) }),
            End("a selection key: the code dropped, the key the app's as any other (review F4650, F4903: the one row that fails without hasEntry())", TableOptions.WUBI.copy(selectionKeys = "123"), { apply(Action.Key('1')).let { it.commit to it } }, { assertEquals(emptyList<String>(), shown()); assertEquals(false, it.handled) }),
            End("a key no code starts: the app's", TableOptions.WUBI, { apply(Action.Key(',')).let { it.commit to it } }, { assertEquals(false, it.handled) }),
            // the table's codes are two keys long: a third ends the code and starts another
            End("a key past the longest code", TableOptions.WUBI, { type("w") }, { assertEquals("人", shown().first()) }),
        ),
        { it.why },
    ) { row ->
        val s = session("qm", row.options)
        s.type("qm")
        assertEquals(listOf("张三"), s.shown())
        val (commit, after) = row.end(s)
        assertEquals("", commit)
        row.after(s, after)
    }

    @Test
    @Uat("WD-36")
    fun aTapTakesThePhrase() {
        val s = session("qm")
        s.type("qm")
        assertEquals("张三", s.apply(Action.Pick(0)).commit)
    }
}
