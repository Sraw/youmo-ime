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

    // an older build's record, its tables' saved phrases converted into it once
    private fun log(vararg saved: Pair<String, String>) = savedLog(*saved, im = WUBI)

    private fun phrases(log: LearnLog, table: TableDictionary = wubi) = table.savedIn(log, WUBI)

    @Test
    fun aPhraseSavedIsOfferedUnderItsCode() {
        val saved = phrases(log("wqwu" to "你们"))
        assertTrue(saved.isSaved("wqwu", "你们"))
        val session = { TableSession(wubi, TableOptions.WUBI.copy(autoSelect = false), saved = saved) }
        assertEquals(listOf("你们"), session().type("wqwu").second.candidates)
        // not on its code's start: what a prefix showed before stays as it was
        assertEquals(emptyList<String>(), session().type("wqw").second.candidates)
    }

    @Test
    fun aPhraseALaterTableHasIsTheTablesAloneAndAnotherTablesIsNotThisOnes() {
        val log = log("aaaa" to "恭恭敬敬")
        log.absorb(listOf(TableWordRow("engine-cangjie", "wqwu", "你们", 1)))
        val saved = phrases(log)
        assertFalse(saved.isSaved("aaaa", "恭恭敬敬"))
        assertEquals(emptyList<Pair<String, String>>(), saved.all())
        // a later table without it, left out as rare: the user picked it, it stays theirs
        assertTrue(phrases(log, dictionary("aaaa 工", "aaaa 敬", "wqiy 你")).isSaved("aaaa", "恭恭敬敬"))
    }

    @Test
    fun savedPhrasesAreFoundByTheirWholeCodeInCodeOrderAndLeadOnFromItsStart() {
        val saved = phrases(log("wqwu" to "你们", "wuwq" to "们你", "wqvb" to "你好"))
        assertEquals(emptyList<Pair<String, String>>(), saved.saved("wq"))
        assertEquals(listOf("wqwu" to "你们"), saved.saved("wqwu"))
        assertEquals(listOf("wqvb" to "你好", "wqwu" to "你们", "wuwq" to "们你"), saved.all())
        assertTrue(saved.hasText("们你"))
        assertFalse(saved.hasText("你"))
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
        val records = log.state.tableWords.size
        saved.forget("aaaa", "工")
        assertEquals(records, log.state.tableWords.size)
        assertFalse(saved.isSaved("wqwu", "你们"))
        assertEquals(setOf("你好"), log.state.tableWords.keys.map { it.third }.toSet())
        assertEquals(listOf("wqvb" to "你好"), phrases(log).all())
    }

    @Test
    fun withHabitsOffNoneIsSaved() {
        val removed = ArrayList<List<TableWordRow>>()
        val saved = SavedPhrases(wubi, WUBI, { null }, { removed += it })
        assertFalse(saved.isSaved("wqwu", "你们"))
        assertFalse(saved.leadsAnywhere(""))
        saved.forget("wqwu", "你们")
        assertTrue(removed.isEmpty())
        assertFalse(saved.remember("wqwu", "你们"))
    }

    @Test
    fun aWordRememberedIsSavedUnderItsCodeInTheRecord() {
        val log = log()
        val saved = phrases(log)
        assertTrue(saved.remember("iije", "汪小明"))
        assertTrue(saved.isSaved("iije", "汪小明"))
        assertEquals(setOf(Triple(WUBI, "iije", "汪小明")), log.state.tableWords.keys)
    }
}
