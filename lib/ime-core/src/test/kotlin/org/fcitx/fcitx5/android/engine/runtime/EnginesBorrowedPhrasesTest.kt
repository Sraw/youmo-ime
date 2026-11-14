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

/**
 * A table's word of the built-in phrases (日期) while the user's habits are off (U-93's 借手机): the
 * built-in dates, as pinyin's fresh phrase book has them, not the user's file (audit-061 engine-09).
 */
class EnginesBorrowedPhrasesTest : EnginesFixture() {

    private val date = Regex("\\d{4}-\\d{2}-\\d{2}")

    // PR-17b's own steps, on one Engines (R25 F7930): a session keeps the book it was made with (F7910: chosen
    // once, not for every candidate), so each switch of the habits, and the phrases read anew, must make the
    // sessions anew or the book before would go on deciding
    @Uat("PR-17b")
    @Test
    fun aSwitchOfTheHabitsOrThePhrasesReadAnewChangesTheBookThatDecides() {
        // the user's own phrase under rq: the built-in dates under it no longer offered while it is theirs
        var phrases = "rq,1=我的日期\n"
        val engines = Engines(::load, null, additions = { Engines.Additions(phrases, "", { }, dictionary = { null }) }, warmUps = inPlace)
        engines.settings = wubiShown
        // their habits used: their phrases decide
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
