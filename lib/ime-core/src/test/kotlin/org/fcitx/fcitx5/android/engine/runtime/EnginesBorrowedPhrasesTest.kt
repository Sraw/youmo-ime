/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A table's built-in phrases with habits off (借手机): the built-in dates, not the user's file. */
class EnginesBorrowedPhrasesTest : EnginesFixture() {

    private val date = Regex("\\d{4}-\\d{2}-\\d{2}")

    // a session keeps its phrase book, so each switch must make the sessions anew
    @Uat("PR-17b")
    @Test
    fun aSwitchOfTheHabitsOrThePhrasesReadAnewChangesTheBookThatDecides() {
        // the user's own phrase under rq: the built-in dates under it no longer offered while it is theirs
        var phrases = "rq,1=我的日期\n"
        val engines = Engines(::load, null, additions = { Engines.Additions(phrases, "", { }, dictionary = { null }) }, warmUps = inPlace)
        engines.settings = wubiShown
        assertEquals(listOf("日期"), engines.type(WUBI, "jjad").candidates)
        // their habits off: the built-in ones come, as pinyin's fresh phrase book has them
        engines.useHabits = false
        val borrowed = engines.type(WUBI, "jjad").candidates
        assertEquals("日期", borrowed.first())
        assertTrue(borrowed.toString(), borrowed.any(date::matches))
        engines.useHabits = true
        assertEquals(listOf("日期"), engines.type(WUBI, "jjad").candidates)
        // their phrase under rq deleted: the built-in dates are theirs again
        phrases = ""
        engines.reload()
        val own = engines.type(WUBI, "jjad").candidates
        assertEquals("日期", own.first())
        assertTrue(own.toString(), own.any(date::matches))
    }
}
