/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.KeyedRows
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.learn.Words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Handwriting's retype (U-03, U-29, F2655): a character written again in what the deleted one's ink read as. */
class RetypeTest {

    private var seq = 0L

    // a handwriting commit of [chars], each [Pair] what its ink read as and what was put in, the last picked over [first]
    private fun commit(vararg chars: Pair<List<String>, String>, first: String = chars.last().second): Commit {
        val picks = chars.mapIndexed { i, (read, text) ->
            val last = i == chars.size - 1
            KeyedRows.Pick(KeyedRows.handwriting(read), text, if (last && first != text) 'B' else 'A', if (last) first else text)
        }
        val rows = KeyedRows.of(Kinds.HANDWRITING, picks, listOf(Word(chars.joinToString("") { it.second }, "")), "")
        return Commit(++seq, 0, 0, 0, 0, rows)
    }

    // what each commit's characters were read as, by its rows
    private val candidates = HashMap<Commit, List<Set<String>>>()

    private fun keep(c: Commit, vararg read: List<String>) = c.also { candidates[it] = read.map { r -> r.toSet() } }

    private fun inCandidates(c: Commit, k: Int, text: String) = candidates[c]?.getOrNull(k)?.contains(text) == true

    private fun code(vararg read: String) = Code(Kinds.HANDWRITING, KeyedRows.handwriting(read.toList()))

    private val renRead = listOf("入", "人", "八")

    @Test
    fun aCharacterInTheDeletedOnesCandidatesIsAPickUnderItsCode() {
        val deleted = keep(commit(renRead to "入"), renRead)
        val m = Retype.matchKeyed(listOf(deleted), 0, listOf("人"), emptyList(), ::inCandidates).match
        assertEquals(1, m.typedAgain)
        val row = m.rows.single()
        assertEquals(code("入", "人", "八"), row.code)
        // no word before: keyed rows have none
        assertEquals("", row.prev)
        assertEquals("人", row.words.text)
        // corrected as against the first shown then
        assertEquals(Row.PICK or Row.CORRECTED, row.flags)
        assertEquals("入", row.shown)
        assertFalse(row.whole)
    }

    @Test
    fun theFirstShownThenWrittenAgainIsNoCorrection() {
        val deleted = keep(commit(renRead to "八", first = "入"), renRead)
        val row = Retype.matchKeyed(listOf(deleted), 0, listOf("入"), emptyList(), ::inCandidates).match.rows.single()
        assertEquals(Row.PICK, row.flags)
    }

    @Test
    fun theRunEndsAtACharacterOutsideThem() {
        val a = keep(commit(renRead to "入"), renRead)
        val b = keep(commit(listOf("天", "夫") to "天"), listOf("天", "夫"))
        val m = Retype.matchKeyed(listOf(a, b), 0, listOf("人", "山"), emptyList(), ::inCandidates).match
        assertEquals(1, m.typedAgain)
        assertEquals(listOf("人"), m.rows.map { it.words.text })
        assertFalse(m.goesOn)
    }

    @Test
    fun theCommitsOwnPickUnderThatCodeAndTextIsAllThereIs() {
        val deleted = keep(commit(renRead to "入"), renRead)
        val own = listOf(Row(Row.PICK, code("入", "人", "八"), "", Words.of("人"), 'B', Row.DICTIONARY, "入"))
        val m = Retype.matchKeyed(listOf(deleted), 0, listOf("人"), own, ::inCandidates).match
        assertEquals(1, m.typedAgain)
        assertTrue(m.rows.isEmpty())
    }

    @Test
    fun twoDeletedOneWrittenAgainTheSecondWaits() {
        val a = keep(commit(renRead to "入"), renRead)
        val b = keep(commit(listOf("天", "夫") to "夫"), listOf("天", "夫"))
        val first = Retype.matchKeyed(listOf(a, b), 0, listOf("人"), emptyList(), ::inCandidates)
        assertEquals(1, first.match.typedAgain)
        assertTrue(first.match.goesOn)
        assertEquals(0, first.skip)
        val second = Retype.matchKeyed(listOf(b), first.skip, listOf("天"), emptyList(), ::inCandidates).match
        assertEquals(code("天", "夫"), second.rows.single().code)
    }

    // review F2655 S: 你好 deleted, written again as 你 and then 好: a row each, the rest waiting between
    @Test
    fun aCommitWrittenAgainACharacterAtATimeGoesOnWhereItStopped() {
        val ni = listOf("你", "妳")
        val hao = listOf("好", "奴")
        val deleted = keep(commit(ni to "妳", hao to "奴", first = "好"), ni, hao)
        val first = Retype.matchKeyed(listOf(deleted), 0, listOf("你"), emptyList(), ::inCandidates)
        assertEquals(0, first.match.typedAgain)
        assertTrue(first.match.goesOn)
        assertEquals(1, first.skip)
        assertEquals(code("你", "妳"), first.match.rows.single().code)
        val second = Retype.matchKeyed(listOf(deleted), first.skip, listOf("好"), emptyList(), ::inCandidates)
        assertEquals(1, second.match.typedAgain)
        assertEquals(0, second.skip)
        val row = second.match.rows.single()
        assertEquals(code("好", "奴"), row.code)
        assertEquals(Row.PICK, row.flags)
    }

    @Test
    fun aPredictionPickedEndsTheRun() {
        val prediction = Commit(++seq, 0, 0, 0, 0, listOf(Row(Row.PICK or Row.WHOLE, Code(Kinds.PREDICTION, "高"), "高", Words.of("山"), 'D', Row.DICTIONARY, "")))
        val m = Retype.matchKeyed(listOf(prediction), 0, listOf("山"), emptyList(), ::inCandidates).match
        assertEquals(0, m.typedAgain)
        assertTrue(m.rows.isEmpty())
    }
}
