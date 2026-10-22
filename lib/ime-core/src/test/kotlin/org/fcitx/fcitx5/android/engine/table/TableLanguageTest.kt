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
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.syl
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
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
        block: ((Entry) -> Unit)? = null,
        forgotten: (String) -> Unit = {},
        habits: Habits = Habits.OFF,
        simplify: (String) -> String = { it },
    ): TableLanguage {
        val readings = Predictor(d.model, d.vocabulary, d.dictionary, user)
        return TableLanguage(
            TextWords(d.model, d.wordIndex, UserScorer(d.model, user)),
            user,
            reading = { text -> d.wordIndex.find(text).takeIf { it != NO_WORD }?.let(readings::reading) },
            predictor = if (predicts) readings else null,
            reranker = reranker,
            refiner = refiner,
            block = block,
            forgotten = forgotten,
            habits = habits,
            kind = Kinds.table("wubi"),
            simplify = simplify,
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
        val learner = Learner(d)
        val user = learner.model
        val s = session(language(user, habits = learner.habits))
        s.type("g")
        // 人 picked after 高, where 山 comes first
        assertEquals("人", s.apply(Action.Select(s.type("abcd").second.candidates.indexOf("人"))).commit)
        assertEquals(setOf("高", "人", "高 人"), learner.used())
        // under the code typed, no word before it (U-28), corrected where 山 was first; never for pinyin's order (D28)
        val picked = learner.log.state.stats.keys.single { it.code.kind == Kinds.table("wubi") && it.code.keys.isNotEmpty() && it.text == "人" }
        assertEquals("abcd", picked.code.keys)
        assertEquals(0f, user.count(d.wordIndex.find("人")), 0f)
        // nothing where nothing is learned (a password)
        val noneLearner = Learner(d)
        val none = noneLearner.model
        val password = session(language(none, habits = noneLearner.habits)).apply { learning = false }
        password.type("g")
        assertTrue(noneLearner.used().isEmpty())
    }

    @Test
    fun `what may follow is offered once something is put in, until the next key`() {
        val learner = Learner(d)
        val user = learner.model
        val s = session(language(user, habits = learner.habits, predicts = true))
        val (commit, put) = s.type("g")
        assertEquals("高", commit)
        assertTrue(put.predicting)
        assertEquals("", put.preedit)
        assertEquals("山", put.candidates.first())
        // a pick puts it in, learned after the word before it, and offers on
        val picked = s.apply(Action.Pick(0))
        assertEquals("山", picked.commit)
        assertTrue("高 山" in learner.used())
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

    // PLAN §2.1, H review N9: a traditional table's words are learned simplified, as 联想 reads them,
    // so what is put in after one 3 times is offered after it (髙 standing for a traditional 高 here)
    @Test
    fun `what a traditional table puts in after a word is offered after it`() {
        val learner = Learner(d)
        val s = TableSession(
            dictionary("g 髙", "abcd 人", "abcd 山"), TableOptions.WUBI,
            language = language(learner.model, habits = learner.habits, predicts = true, simplify = { it.replace("髙", "高") }),
        )
        repeat(Predictor.PAIRS) {
            assertEquals("髙", s.type("g").first)
            val code = s.type("abcd").second
            assertEquals("人", s.apply(Action.Pick(code.candidates.indexOf("人"))).commit)
            s.apply(Action.Reset)
        }
        assertTrue("高 人" in learner.used())
        val after = s.type("g").second
        assertTrue(after.predicting)
        assertTrue("人" in after.candidates)
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
        val learner = Learner(d)
        val user = learner.model
        val s = session(language(user, habits = learner.habits))
        s.type("g")
        // the comma is the app's
        assertFalse(s.apply(Action.Key('，')).handled)
        assertEquals(listOf("人", "山"), s.type("abcd").second.candidates)
        s.apply(Action.Select(1))
        assertFalse("高 山" in learner.used())
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
    fun `the user's words of the code come after the table's own, which a block in pinyin leaves, a wildcard keeps the order`() {
        val shared = object : SharedWords by SharedWords.NONE {
            override fun words(prefix: String) = if ("abcd".startsWith(prefix)) listOf("abcd" to "人山") else emptyList()
            override fun leadsAnywhere(prefix: String) = "abcd".startsWith(prefix)
            override fun blocked(text: String) = text == "天"
        }
        val s = TableSession(dictionary("g 高", "abcd 人", "abcd 山", "abcd 天"), TableOptions.WUBI, language = language(), shared = shared)
        s.apply(Action.Context("高"))
        // a word of pinyin's: after the table's own (U-28); 天, blocked in pinyin, the table's own still (C12)
        assertEquals(listOf("山", "人", "天", "人山"), s.type("abcd").second.candidates)
        s.apply(Action.Reset)
        // 五笔's z stands for any key: the table's order
        s.apply(Action.Context("高"))
        assertEquals(listOf("人", "山"), s.type("abcz").second.candidates.take(2))
    }

    @Test
    fun `forgetting a table entry forgets it everywhere, as every input method learned it, once learned while ordering by use`() {
        val forgotten = ArrayList<String>()
        val shared = object : SharedWords by SharedWords.NONE {
            override fun forget(text: String) {
                forgotten += text
            }
        }
        val learner = Learner(d)
        val s = session(language(learner.model, habits = learner.habits), shared)
        val at = s.type("abcd").second.candidates.indexOf("山")
        // nothing learned of it yet (U-28)
        assertFalse(Offer.FORGET in s.offers(at))
        s.apply(Action.Forget(at))
        assertEquals(emptyList<String>(), forgotten)
        s.apply(Action.Select(at))
        val again = s.type("abcd").second.candidates.indexOf("山")
        assertTrue(Offer.FORGET in s.offers(again))
        s.apply(Action.Forget(again))
        assertEquals(listOf("山"), forgotten)
        // 调频 off: the table's order is all there is, nothing of its own to forget
        val fixed = TableSession(table, TableOptions.WUBI.copy(orderByUse = false), language = language(learner.model, habits = learner.habits))
        assertTrue(fixed.type("abcd").second.candidates.indices.none { Offer.FORGET in fixed.offers(it) })
    }

    @Test
    fun `a phrase an older build saved comes after the table's own of its code, which the word model places`() {
        val s = TableSession(table, TableOptions.WUBI, user = table.savedBefore("abcd" to "人山"), language = language())
        val shown = s.type("abcd").second.candidates
        assertEquals("人山", shown.last())
        assertEquals(setOf("人", "山"), shown.dropLast(1).toSet())
    }

    @Test
    fun `a phrase saved here is learned as the user made it in pinyin, a table entry the word model lacks is not`() {
        val modelLearner = Learner(d)
        val model = modelLearner.model
        // the word model lacks it: the user's own, read ren shan
        model.own(Entry("人山", intArrayOf(Syllables.id("ren"), Syllables.id("shan"))))
        val s = TableSession(table, TableOptions.WUBI, user = table.savedBefore("abcd" to "人山"), language = language(model, habits = modelLearner.habits))
        assertEquals("人山", s.apply(Action.Select(s.type("abcd").second.candidates.indexOf("人山"))).commit)
        assertEquals("高", s.type("g").first)
        assertTrue("人山 高" in modelLearner.used())
        // the table's own entry: learned by its text after the word before it (for 联想), never made a word of the user's
        val entryLearner = Learner(d)
        val entry = TableSession(dictionary("g 高", "abcd 人山"), TableOptions.WUBI, language = language(entryLearner.model, habits = entryLearner.habits))
        entry.type("g")
        assertEquals("人山", entry.type("abcd").first)
        assertTrue("高 人山" in entryLearner.used())
        assertFalse(entryLearner.habits.isWord("人山"))
    }

    @Test
    fun `a word the user made in pinyin, picked here, is learned as they made it, after the word before it`() {
        val learner = Learner(d)
        val user = learner.model
        // the word model lacks it: the user's own, read ren shan
        val made = Entry("人山", intArrayOf(Syllables.id("ren"), Syllables.id("shan")))
        user.own(made)
        val shared = object : SharedWords by SharedWords.NONE {
            override fun words(prefix: String) = if ("abcd".startsWith(prefix)) listOf("abcd" to "人山") else emptyList()
            override fun leadsAnywhere(prefix: String) = "abcd".startsWith(prefix)
        }
        val s = session(language(user, habits = learner.habits), shared)
        assertEquals("人山", s.apply(Action.Select(s.type("abcd").second.candidates.indexOf("人山"))).commit)
        assertEquals("高", s.type("g").first)
        assertTrue("人山 高" in learner.used())
    }

    @Test
    fun `a pinyin entry of the table's is learned too, after the word before it`() {
        val learner = Learner(d)
        val user = learner.model
        val wubiPinyin = dictionary("g 高", "@ren 人", "@shan 山", header = mapOf("拼音" to "@"))
        val s = TableSession(wubiPinyin, TableOptions.WUBI_PINYIN, language = language(user, habits = learner.habits))
        assertEquals(listOf("人"), s.type("ren").second.candidates)
        assertEquals("人", s.apply(Action.Select(0)).commit)
        assertTrue("人" in learner.used())
        assertEquals("高", s.type("g").first)
        assertTrue("人 高" in learner.used())
    }

    @Test
    fun `a code that commits itself leaves nothing to weigh, a row twice in the table too`() {
        val refiner = SentenceRefiner { _, _, _, _ -> 0 }
        val s = TableSession(dictionary("abcd 人", "abcd 人"), TableOptions.WUBI, language = language(refiner = refiner))
        val (commit, shown) = s.type("abcd")
        assertEquals("人", commit)
        assertFalse(shown.refines)
        // nothing typed: the code's 重码 are not shown again, to be picked
        assertTrue(s.apply(Action.Refine).candidates.isEmpty())
    }

    @Test
    fun `the 重码 are placed with longer codes after them, sorted by length or not`() {
        val longer = dictionary("g 高", "abc 人", "abc 山", "abcd 天")
        val sorted = TableSession(longer, TableOptions.WUBI, language = language())
        sorted.apply(Action.Context("高"))
        assertEquals(listOf("山", "人", "天"), sorted.type("abc").second.candidates)
        // not sorted by length: the 重码 are placed all the same
        val unsorted = TableSession(longer, TableOptions.WUBI.copy(sortByCodeLength = false), language = language())
        unsorted.apply(Action.Context("高"))
        assertEquals(listOf("山", "人", "天"), unsorted.type("abc").second.candidates)
    }

    @Test
    fun `a key that ends what may follow shows the first page again`() {
        // two may follow 高, one a page: 山 of the model, 人 learned after it
        val user = UserModel(d.dictionary, d.vocabulary)
        user.setPredicted(user.id(Entry("高", syl("gao"))), user.id(Entry("人", syl("ren"))), Predictor.PAIRS.toFloat())
        val s = TableSession(table, TableOptions.WUBI.copy(pageSize = 1), language = language(user, predicts = true))
        assertTrue(s.type("g").second.predicting)
        assertTrue(s.apply(Action.NextPage).hasPreviousPage)
        val back = s.apply(Action.Backspace)
        assertFalse(back.predicting)
        assertEquals(0, back.page)
        assertFalse(back.hasPreviousPage)
    }

    @Test
    fun `a long press on what may follow forgets what was learned of it or blocks it, as handwriting's`() {
        val learner = Learner(d)
        val user = learner.model
        // a prediction forgotten goes at its reading and from the tables, not at every reading (shared.forget)
        val forgotten = ArrayList<String>()
        val everywhere = ArrayList<String>()
        val shared = object : SharedWords by SharedWords.NONE {
            override fun blockable(text: String) = text == "山"
            override fun forget(text: String) {
                everywhere += text
            }
        }
        val s = session(language(user, habits = learner.habits, predicts = true, block = { user.block(it) }, forgotten = { forgotten += it; learner.habits.forget(it) }), shared)
        val offered = s.type("g").second
        assertTrue(offered.actionable)
        assertEquals("山", offered.candidates.first())
        // never picked: nothing learned of it to forget
        assertEquals(setOf(Offer.BLOCK), s.offers(0))
        s.apply(Action.Pick(0))
        s.type("g")
        assertEquals(setOf(Offer.FORGET, Offer.BLOCK), s.offers(0))
        assertTrue(s.apply(Action.Forget(0)).predicting)
        assertFalse(learner.used().any { "山" in it })
        assertEquals(listOf("山"), forgotten)
        assertEquals(emptyList<String>(), everywhere)
        assertFalse("山" in s.apply(Action.Block(0)).candidates)
        // where nothing is to be kept (a password), nothing offered
        s.learning = false
        assertTrue(s.offers(0).isEmpty())
    }

    @Test
    fun `what may follow is blocked only where the table could block it as a candidate`() {
        val user = UserModel(d.dictionary, d.vocabulary)
        // a table hides a blocked text however read: a character it could not offer as a candidate
        val s = session(language(user, predicts = true, block = { user.block(it) }))
        assertEquals("山", s.type("g").second.candidates.first())
        assertEquals(emptySet<Offer>(), s.offers(0))
        assertFalse(s.apply(Action.Block(0)).candidates.isEmpty())
    }

    @Test
    fun `Enter with nothing typed is the app's, what is before goes when it is passed on, as handwriting's`() {
        val learner = Learner(d)
        val user = learner.model
        val s = session(language(user, habits = learner.habits))
        s.type("g")
        assertFalse(s.apply(Action.CommitRaw).handled)
        // the word before still the one a pick is learned after, as handwriting has it
        assertEquals(listOf("山", "人"), s.type("abcd").second.candidates)
        s.apply(Action.Select(0))
        assertTrue("高 山" in learner.used())
        // the app took the Enter: past
        val passedLearner = Learner(d)
        val passed = passedLearner.model
        val t = session(language(passed, habits = passedLearner.habits))
        t.type("g")
        t.apply(Action.CommitRaw)
        t.passedOn()
        assertEquals(listOf("人", "山"), t.type("abcd").second.candidates)
        t.apply(Action.Select(1))
        assertFalse("高 山" in passedLearner.used())
    }
}
