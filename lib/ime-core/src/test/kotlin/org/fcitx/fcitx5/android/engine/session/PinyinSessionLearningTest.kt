/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.CommitRaw
import org.fcitx.fcitx5.android.engine.session.Action.Forget
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.NextPage
import org.fcitx.fcitx5.android.engine.session.Action.Pick
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.user.UserFixtures.inTrie
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PinyinSession] with a [UserModel]: what it learns from what is picked, and what it forgets or blocks. */
class PinyinSessionLearningTest {

    private val data = sessionTestData()

    private fun session(pageSize: Int = PinyinSession.DEFAULT_PAGE_SIZE) = PinyinSession(data, PinyinSegmenter(), pageSize = pageSize)

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    @Test
    fun nothingIsLearnedWhileLearningIsOff() {
        val (session, user) = learning()
        session.learning = false
        val s = session.type("nizai")
        session.apply(Select(s.candidates.indexOf("拟")))
        val done = session.apply(Select(0))
        assertEquals("拟再", done.commit)
        assertFalse(done.predicting)
        assertEquals(0f, user.total)
        assertEquals(0, user.size)
    }

    private fun learning(pageSize: Int = 20): Pair<Session, UserModel> {
        val user = UserModel(data.dictionary, data.vocabulary)
        return PinyinSession(data, PinyinSegmenter(), pageSize = pageSize, user = user) to user
    }

    @Test
    fun aWordPickedOftenComesFirst() {
        val (session, user) = learning()
        repeat(3) {
            val s = session.type("ni")
            // 拟 is far less likely than 你 until picked three times
            assertEquals("你", s.candidates.first())
            assertEquals("拟", session.apply(Select(s.candidates.indexOf("拟"))).commit)
            session.apply(Reset)
        }
        assertEquals("拟", session.type("ni").candidates.first())
        assertEquals(3f, user.total)
    }

    @Test
    fun piecesPutTogetherAreLearnedAsOneWord() {
        val (session, user) = learning()
        val s = session.type("nizai")
        session.apply(Select(s.candidates.indexOf("拟")))
        val committed = session.apply(Select(0))
        assertEquals("拟再", committed.commit)
        assertEquals(1, user.size)
        // the model never saw the user's word: what follows is what follows its end, 再
        assertTrue(committed.predicting)
        assertEquals(listOf("见"), committed.candidates)
        session.apply(Reset)
        // found whole, and read as its syllables
        val again = session.type("nizai")
        assertEquals("拟再", again.candidates.first())
        assertEquals("ni zai", again.preedit)
        // and picked whole: learned again, not anew
        assertEquals("拟再", session.apply(Select(0)).commit)
        assertEquals(1, user.size)
        assertEquals(2f, user.total)
    }

    @Test
    fun aLongRunOfPiecesIsLearnedAsItsWords() {
        val (session, user) = learning()
        var s = session.type("ni".repeat(UserModel.MAX_PHRASE + 1))
        repeat(UserModel.MAX_PHRASE + 1) { s = session.apply(Select(s.candidates.indexOf("拟"))) }
        assertEquals("拟".repeat(UserModel.MAX_PHRASE + 1), s.commit)
        assertEquals(0, user.size)
        assertEquals(UserModel.MAX_PHRASE + 1f, user.total)
    }

    @Test
    fun aPredictionPickedIsLearnedTextAsTypedIsNot() {
        val (session, user) = learning()
        session.type("wo")
        session.apply(Select(0))
        // 再, predicted after 我: learned as the dictionary reads it, after 我
        assertEquals("再", session.apply(Select(0)).commit)
        assertEquals(2f, user.total)
        assertTrue(user.probability(user.id(UserModel.Entry("我", syl("wo"))), user.id(UserModel.Entry("再", syl("zai")))) > 0.3f)
        session.type("vvv")
        session.apply(CommitRaw)
        // a sentence with text kept as typed
        val s = session.type("nivvv")
        assertEquals("你vvv", session.apply(Select(s.candidates.indexOf("你vvv"))).commit)
        assertEquals(2f, user.total)
    }

    @Test
    fun whatIsOftenTypedAfterAWordIsPredictedAfterIt() {
        val (session, _) = learning()
        // 我 then 你好 (你 and 好, as the model splits it), as 用 then 中文: the model has 再 after 我
        repeat(5) {
            session.type("wo")
            assertEquals(if (it == 0) listOf("再") else listOf("再", "你"), session.apply(Select(0)).candidates.take(2))
            assertEquals("你好", session.type("nihao").let { session.apply(Select(0)) }.commit)
            session.apply(Reset)
        }
        session.type("wo")
        assertEquals("你", session.apply(Select(0)).candidates.first())
        // picked, what follows it is offered in turn
        val next = session.apply(Select(0))
        assertEquals("你", next.commit)
        assertEquals("好", next.candidates.first())
    }

    // PY-33: a long press on a word never chosen offered to forget what was never learned
    @Test
    fun onlyAWordLearnedOffersToBeForgotten() {
        val (session, _) = learning()
        session.type("ni")
        val never = session.candidates(0, 20).indexOfFirst { it.text == "拟" }
        assertFalse(Offer.FORGET in session.offers(never))
        session.apply(Pick(never))
        session.apply(Reset)
        session.type("ni")
        assertTrue(Offer.FORGET in session.offers(session.candidates(0, 20).indexOfFirst { it.text == "拟" }))
    }

    @Test
    fun aCandidateForgottenIsReadAsBeforeItWasLearned() {
        val (session, user) = learning(pageSize = 1)
        session.type("nizai")
        val fresh = session.candidates(0, 20)
        session.apply(Reset)
        // 拟 then 再: learned as the user's word 拟再
        session.type("nizai")
        session.apply(Pick(session.candidates(0, 20).indexOfFirst { it.text == "拟" }))
        session.apply(Select(0))
        session.apply(Reset)
        val before = session.type("nizai")
        assertEquals(listOf("拟再"), before.candidates)
        assertTrue(before.actionable)
        assertEquals(setOf(Offer.FORGET, Offer.PIN), session.offers(0))
        assertEquals(1, user.size)
        // forgotten from the second page, which stays shown
        session.apply(NextPage)
        val after = session.apply(Forget(session.candidates(0, 20).indexOfFirst { it.text == "拟再" }))
        // read as before: 拟再 as the sentence 拟 再, not the user's word
        assertEquals(fresh, session.candidates(0, 20))
        assertEquals(1, after.page)
        assertEquals("ni zai", after.preedit)
        assertEquals(0f, user.total)
        session.apply(Reset)
        val again = session.type("nizai")
        assertEquals(fresh.take(1).map { it.text }, again.candidates)
        // past the candidates: nothing to forget
        assertEquals(again, session.apply(Forget(99)))
    }

    @Test
    fun aWordCommittedThenForgottenIsNotLearnedAgainAsTheWordBefore() {
        val (session, user) = learning()
        session.type("nizai")
        session.apply(Pick(session.candidates(0, 20).indexOfFirst { it.text == "拟" }))
        session.apply(Select(0))
        // deleted in the app, typed again and forgotten: the context is still 拟再's
        session.apply(Backspace)
        session.type("nizai")
        session.apply(Forget(session.candidates(0, 20).indexOfFirst { it.text == "拟再" }))
        session.apply(Pick(session.candidates(0, 20).indexOfFirst { it.text == "你在" }))
        assertFalse(inTrie(user, "拟再", "ni", "zai"))
        val counted = ArrayList<String>()
        user.forEachCount({ e, _ -> counted += e.toString() }, { a, b, _ -> counted += "$a $b" })
        // 你 在 and their pair, none with 拟再
        assertEquals(listOf("你(ni)", "你(ni) 在(zai)", "在(zai)"), counted.sorted())
    }

    @Test
    fun onlyWhatAUserModelLearnedIsForgotten() {
        // pinned as a phrase, but nothing learned to forget
        assertEquals(setOf(Offer.PIN), session().also { it.type("ni") }.offers(0))
        assertEquals(session().type("ni"), session().also { it.type("ni") }.apply(Forget(0)))
        val (session, user) = learning()
        session.type("wo")
        val predicted = session.apply(Select(0))
        assertTrue(predicted.predicting)
        // a long press on a prediction may offer something; on this one nothing, none of it learned
        assertTrue(predicted.actionable)
        assertEquals(emptySet<Offer>(), session.offers(0))
        // a prediction offered: nothing forgotten
        assertEquals(predicted.copy(commit = ""), session.apply(Forget(0)))
        assertEquals(1f, user.total)
    }

    private fun word(text: String) = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == text }

    @Test
    fun aPredictionLearnedMayBeForgottenAndAnyBlocked() {
        val user = UserModel(data.dictionary, data.vocabulary)
        val blocked = ArrayList<String>()
        val session = PinyinSession(data, PinyinSegmenter(), user = user, block = { blocked += it.text; user.block(it) })
        session.type("wo")
        // the host asks what a long press offers only where the snapshot is actionable
        assertTrue(session.apply(Select(0)).actionable)
        assertEquals(setOf(Offer.BLOCK), session.offers(0))
        // 再 picked after 我: learned, so it may be forgotten
        session.apply(Select(0))
        session.apply(Reset)
        session.type("wo")
        assertEquals("再", session.apply(Select(0)).candidates.first())
        assertEquals(setOf(Offer.FORGET, Offer.BLOCK), session.offers(0))
        val forgotten = session.apply(Forget(0))
        assertTrue(forgotten.predicting)
        assertEquals(0f, user.probability(word("我"), word("再")))
        assertEquals(setOf(Offer.BLOCK), session.offers(0))
        // blocked, nothing is left to offer after 我
        val after = session.apply(Action.Block(0))
        assertEquals(listOf("再"), blocked)
        assertFalse(after.predicting)
        assertTrue(after.candidates.isEmpty())
    }

    @Test
    fun whileLearningIsOffNothingIsForgottenOrBlocked() {
        val user = UserModel(data.dictionary, data.vocabulary)
        val blocked = ArrayList<String>()
        val session = PinyinSession(data, PinyinSegmenter(), user = user, block = { blocked += it.text; user.block(it) })
        session.type("wo")
        session.apply(Select(0))
        session.apply(Reset)
        assertEquals(1f, user.total)
        // a password: what is forgotten or blocked is kept, and it is near what is typed there
        session.learning = false
        session.type("wo")
        assertEquals(emptySet<Offer>(), session.offers(0))
        session.apply(Forget(0))
        session.apply(Action.Block(0))
        assertEquals(1f, user.total)
        assertEquals(emptyList<String>(), blocked)
        session.learning = true
        assertEquals(setOf(Offer.FORGET, Offer.PIN, Offer.BLOCK), session.offers(0))
    }

    @Test
    fun aPredictionPickedAfterTheAppsTextIsLearnedAfterItsLastWord() {
        val (session, user) = learning()
        session.apply(Action.Context("我"))
        session.type("zai")
        session.apply(Select(session.candidates(0, 20).indexOfFirst { it.text == "再" }))
        assertEquals("见", session.apply(Select(0)).commit)
        assertTrue(user.probability(word("再"), word("见")) > 0.3f)
    }

    @Test
    fun theWordCommittedBeforeIsLearnedWithTheNext() {
        val (session, user) = learning()
        fun commit(keys: String, text: String) {
            val s = session.type(keys)
            assertEquals(text, session.apply(Select(s.candidates.indexOf(text))).commit)
        }
        val alone = { user.probability(word("我"), word("在")) }
        commit("wo", "我")
        commit("zai", "在")
        val paired = alone()
        assertTrue(paired > user.probability(word("你"), word("在")))
        // after a reset, text as typed, or text not learned, 在 follows nothing learned
        for (breaks in listOf<() -> Unit>(
            { session.apply(Reset) },
            { session.type("vvv"); session.apply(CommitRaw) },
            { commit("vvvni", "vvv你") },
        )) {
            commit("wo", "我")
            breaks()
            commit("zai", "在")
        }
        // 我 and 在 counted four times each, the pair still once: its share fell
        assertTrue(alone() < paired)
        assertEquals((1f + 2f * user.probability(NO_WORD, word("在"))) / (4f + 2f), alone(), 1e-6f)
    }

    @Test
    fun aReadingPickedOverABetterOneIsLearnedAsOneWord() {
        val (session, user) = learning()
        val s = session.type("nihaoma")
        assertEquals("你好吗", s.candidates.first())
        // the first reading, picked whole: its words are learned, and no new one
        session.apply(Select(0))
        assertEquals(0, user.size)
        val other = session.type("nizai")
        val index = other.candidates.indexOf("拟再")
        assertTrue(index > 0)
        assertEquals("拟再", session.apply(Select(index)).commit)
        assertEquals(1, user.size)
        session.apply(Reset)
        assertEquals("拟再", session.type("nizai").candidates.first())
    }
}
