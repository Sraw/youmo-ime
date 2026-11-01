/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableWordRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A table's phrases an older build saved, as the learning record keeps them ([SavedPhrases]). */
class SavedPhrasesTest {

    private val wubi = dictionary("a 工", "aaaa 工", "aaaa 恭恭敬敬", "aaad 工期", "wqiy 你", "wun 们", "vbg 好")

    // the record of 0.4, its tables' saved phrases converted into it once
    private fun log(vararg saved: Pair<String, String>) = savedLog(*saved, im = WUBI)

    private fun phrases(log: LearnLog, table: TableDictionary = wubi) = table.savedIn(log, WUBI)

    @Test
    fun aPhraseSavedIsOfferedUnderItsCode() {
        val saved = phrases(log("wqwu" to "你们"))
        assertTrue(saved.isSaved("wqwu", "你们"))
        assertEquals(listOf("你们"), TableSession(wubi, TableOptions.WUBI.copy(autoSelect = false), saved = saved).type("wqw").second.candidates)
    }

    @Test
    fun aPhraseALaterTableHasIsTheTablesAloneAndAnotherTablesIsNotThisOnes() {
        val log = log("aaaa" to "恭恭敬敬")
        log.absorb(listOf(TableWordRow("engine-cangjie", "wqwu", "你们", 1)))
        val saved = phrases(log)
        assertFalse(saved.isSaved("aaaa", "恭恭敬敬"))
        assertEquals(emptyList<Pair<String, String>>(), saved.saved(""))
        // a later table without it, left out as rare: the user picked it, it stays theirs
        assertTrue(phrases(log, dictionary("aaaa 工", "aaaa 敬", "wqiy 你")).isSaved("aaaa", "恭恭敬敬"))
    }

    @Test
    fun savedPhrasesAreFoundByTheStartOfTheirCodeInCodeOrder() {
        val saved = phrases(log("wqwu" to "你们", "wuwq" to "们你", "wqvb" to "你好"))
        assertEquals(listOf("wqvb" to "你好", "wqwu" to "你们"), saved.saved("wq"))
        assertEquals(listOf("wqwu" to "你们"), saved.saved("wqw"))
        assertEquals(3, saved.saved("").size)
        assertTrue(saved.leadsAnywhere("wu"))
        assertFalse(saved.leadsAnywhere("wv"))
        assertFalse(saved.leadsAnywhere("wqwuw"))
    }

    @Test
    fun whatIsForgottenGoesFromTheRecordAndStaysForgotten() {
        val log = log("wqwu" to "你们", "wqvb" to "你好")
        val saved = phrases(log)
        assertTrue(saved.isSaved("wqwu", "你们"))
        saved.forget("wqwu", "你们")
        // nothing saved of it: nothing written
        val records = log.state.tableWords.size
        saved.forget("aaaa", "工")
        assertEquals(records, log.state.tableWords.size)
        assertFalse(saved.isSaved("wqwu", "你们"))
        assertEquals(setOf("你好"), log.state.tableWords.keys.map { it.third }.toSet())
        // the record read again, the same
        assertEquals(listOf("wqvb" to "你好"), phrases(log).saved(""))
    }

    @Test
    fun withHabitsOffNoneIsSaved() {
        val removed = ArrayList<List<TableWordRow>>()
        val saved = SavedPhrases(wubi, WUBI, { null }, { removed += it })
        assertFalse(saved.isSaved("wqwu", "你们"))
        assertFalse(saved.leadsAnywhere(""))
        saved.forget("wqwu", "你们")
        assertTrue(removed.isEmpty())
    }
}
