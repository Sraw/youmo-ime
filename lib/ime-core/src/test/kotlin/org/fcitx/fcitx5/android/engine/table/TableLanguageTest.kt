/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.handwriting.handwritingData
import org.fcitx.fcitx5.android.engine.handwriting.learned
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableLanguageTest {
    private val d = handwritingData

    // 人 and 山 share abcd (重码) and ab (简码, kept in order); 高 commits itself
    private val table = dictionary("g 高", "ab 人", "ab 山", "abcd 人", "abcd 山", "abce 天")

    private fun language(
        user: UserModel = UserModel(d.dictionary, d.vocabulary),
        predicts: Boolean = false,
        reranker: SentencePicker? = null,
        refiner: SentenceRefiner? = null,
    ): TableLanguage {
        val readings = Predictor(d.model, d.vocabulary, d.dictionary, user)
        return TableLanguage(
            TextWords(d.model, d.wordIndex, UserScorer(d.model, user)),
            user,
            reading = { text -> d.wordIndex.find(text).takeIf { it != NO_WORD }?.let(readings::reading) },
            predictor = if (predicts) readings else null,
            reranker = reranker,
            refiner = refiner,
        )
    }

    private fun session(language: TableLanguage?, shared: SharedWords = SharedWords.NONE) =
        TableSession(table, TableOptions.WUBI, language = language, shared = shared)

    @Test
    fun `a code's 重码 go by the word model after the text before, 简码 as they are`() {
        // the table's order without one
        assertEquals(listOf("人", "山"), session(null).apply { apply(Action.Context("高")) }.type("abcd").second.candidates)
        val s = session(language())
        // alone, the commoner first
        assertEquals(listOf("人", "山"), s.type("abcd").second.candidates)
        s.apply(Action.Reset)
        // after 高, 山
        s.apply(Action.Context("高"))
        assertEquals(listOf("山", "人"), s.type("abcd").second.candidates)
        s.apply(Action.Reset)
        // after 高 typed here too
        assertEquals("高", s.type("g").first)
        assertEquals(listOf("山", "人"), s.type("abcd").second.candidates)
        s.apply(Action.Reset)
        // a 简码's order is the table's
        s.apply(Action.Context("高"))
        assertEquals(listOf("人", "山"), s.type("ab").second.candidates.take(2))
        // and every code's, where the table does not order by use (for those who type by position)
        val fixed = TableSession(table, TableOptions.WUBI.copy(orderByUse = false), language = language())
        fixed.apply(Action.Context("高"))
        assertEquals(listOf("人", "山"), fixed.type("abcd").second.candidates)
    }

    @Test
    fun `what is put in is learned by the one user model, after the word before it`() {
        val user = UserModel(d.dictionary, d.vocabulary)
        val s = session(language(user))
        s.type("g")
        // 人 picked after 高, where 山 comes first
        assertEquals("人", s.apply(Action.Select(s.type("abcd").second.candidates.indexOf("人"))).commit)
        assertEquals(setOf("高(gao)", "人(ren)", "高(gao) 人(ren)"), user.learned())
        // and ranks by it: 山 picked comes first alone too
        repeat(3) {
            s.apply(Action.Reset)
            s.type("abcd")
            s.apply(Action.Select(s.apply(Action.Ink).candidates.indexOf("山")))
        }
        s.apply(Action.Reset)
        assertEquals("山", s.type("abcd").second.candidates.first())
        // nothing where nothing is learned (a password)
        val none = UserModel(d.dictionary, d.vocabulary)
        val password = session(language(none)).apply { learning = false }
        password.type("g")
        assertTrue(none.learned().isEmpty())
    }

    @Test
    fun `what may follow is offered once something is put in, until the next key`() {
        val user = UserModel(d.dictionary, d.vocabulary)
        val s = session(language(user, predicts = true))
        val (commit, put) = s.type("g")
        assertEquals("高", commit)
        assertTrue(put.predicting)
        assertEquals("", put.preedit)
        assertEquals("山", put.candidates.first())
        // a pick puts it in, learned after the word before it, and offers on
        val picked = s.apply(Action.Pick(0))
        assertEquals("山", picked.commit)
        assertTrue("高(gao) 山(shan)" in user.learned())
        // a code key ends the offer and starts a code
        s.type("g")
        val typed = s.apply(Action.Key('a'))
        assertFalse(typed.predicting)
        assertEquals("a", typed.preedit)
        s.apply(Action.Reset)
        // backspace and a key the app takes end it too
        s.type("g")
        val back = s.apply(Action.Backspace)
        assertFalse(back.handled)
        assertFalse(back.predicting)
        s.type("g")
        val key = s.apply(Action.Key('，'))
        assertFalse(key.handled)
        assertTrue(key.candidates.isEmpty())
        // enter puts in what is typed, nothing offered
        s.type("ab")
        assertFalse(s.apply(Action.CommitRaw).predicting)
        // where nothing is learned, nothing offered
        s.learning = false
        assertFalse(s.type("g").second.predicting)
    }

    @Test
    fun `the sentence models weigh the 重码 again, the large one while the user pauses`() {
        val seen = ArrayList<String>()
        val picker = SentencePicker { context, readings, _ -> seen += context; readings.indexOf("山").coerceAtLeast(0) }
        val medium = session(language(reranker = picker))
        medium.apply(Action.Context("我说"))
        assertEquals(listOf("山", "人"), medium.type("abcd").second.candidates)
        assertEquals("我说", seen.last())

        val refiner = SentenceRefiner { _, readings, _, _ -> readings.indexOf("山").takeIf { it >= 0 } ?: SentenceRefiner.NONE }
        val high = session(language(refiner = refiner))
        val shown = high.type("abcd").second
        assertEquals(listOf("人", "山"), shown.candidates)
        assertTrue(shown.refines)
        val refined = high.apply(Action.Refine)
        assertEquals(listOf("山", "人"), refined.candidates)
        assertFalse(refined.refines)
        // the one put in is the one shown
        assertEquals("山", high.apply(Action.Select(0)).commit)
        // a page turned meanwhile ends the weighing
        high.type("abcd")
        high.apply(Action.NextPage)
        assertEquals(listOf("人", "山"), high.apply(Action.Refine).candidates)
    }

    @Test
    fun `a key the app takes ends the text before, nothing ranked or learned across it`() {
        val user = UserModel(d.dictionary, d.vocabulary)
        val s = session(language(user))
        s.type("g")
        // the comma is the app's
        assertFalse(s.apply(Action.Key('，')).handled)
        assertEquals(listOf("人", "山"), s.type("abcd").second.candidates)
        s.apply(Action.Select(1))
        assertFalse("高(gao) 山(shan)" in user.learned())
        // a cursor that stays after the word keeps it; one that moves does not
        val stays = session(language())
        stays.type("g")
        stays.apply(Action.Context("……高"))
        assertEquals(listOf("山", "人"), stays.type("abcd").second.candidates)
        val moves = session(language())
        moves.type("g")
        moves.apply(Action.Context("天"))
        assertEquals(listOf("人", "山"), moves.type("abcd").second.candidates)
    }

    @Test
    fun `the user's words of the code come first once picked here, a blocked word is not weighed, a wildcard keeps the order`() {
        val shared = object : SharedWords by SharedWords.NONE {
            override fun words(prefix: String) = if ("abcd".startsWith(prefix)) listOf("abcd" to "人山") else emptyList()
            override fun leadsAnywhere(prefix: String) = "abcd".startsWith(prefix)
            override fun blocked(text: String) = text == "天"
        }
        val s = TableSession(dictionary("g 高", "abcd 人", "abcd 山", "abcd 天"), TableOptions.WUBI, language = language(), shared = shared)
        s.apply(Action.Context("高"))
        // a word of pinyin's, never picked here: after the table's own
        assertEquals(listOf("山", "人", "人山"), s.type("abcd").second.candidates)
        assertEquals("人山", s.apply(Action.Select(2)).commit)
        s.apply(Action.Context("高"))
        assertEquals(listOf("人山", "山", "人"), s.type("abcd").second.candidates)
        s.apply(Action.Reset)
        // 五笔's z stands for any key: the table's order
        s.apply(Action.Context("高"))
        assertEquals(listOf("人", "山"), s.type("abcz").second.candidates.take(2))
    }

    @Test
    fun `forgetting a table entry forgets it everywhere, as every input method learned it`() {
        val forgotten = ArrayList<String>()
        val shared = object : SharedWords by SharedWords.NONE {
            override fun forget(text: String) {
                forgotten += text
            }
        }
        val s = session(language(), shared)
        s.type("abcd")
        s.apply(Action.Forget(1))
        assertEquals(listOf("山"), forgotten)
    }
}
