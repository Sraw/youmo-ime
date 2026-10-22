/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.learn.KeyedRows
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.learn.Word
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

    // the user, as Engines has one: what a session learns goes to the learning record, and from it to the model
    private lateinit var learner: Learner

    private fun learning(pageSize: Int = 20): Pair<Session, UserModel> {
        learner = Learner(data)
        val user = learner.model
        return PinyinSession(data, PinyinSegmenter(), pageSize = pageSize, user = user, learnTo = learner.learnTo()) to user
    }

    @Test
    fun aWordPickedOverTheFirstTwiceComesFirst() {
        val (session, user) = learning()
        repeat(2) {
            val s = session.type("ni")
            // 拟 is far less likely than 你 until corrected to twice in a row (U-02 rule 2)
            assertEquals("你", s.candidates.first())
            assertEquals("拟", session.apply(Select(s.candidates.indexOf("拟"))).commit)
            session.apply(Reset)
        }
        assertEquals("拟", session.type("ni").candidates.first())
        assertEquals(2f, user.total)
    }

    @Test
    fun piecesPutTogetherTwiceAreTheUsersWord() {
        val (session, user) = learning()
        fun pieces(): Snapshot {
            val s = session.type("nizai")
            session.apply(Select(s.candidates.indexOf("拟")))
            return session.apply(Select(0))
        }
        assertEquals("拟再", pieces().commit)
        // once: no word yet (U-06)
        assertEquals(0, user.size)
        session.apply(Reset)
        val committed = pieces()
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
        // and picked whole: not made anew
        assertEquals("拟再", session.apply(Select(0)).commit)
        assertEquals(1, user.size)
        assertTrue(inTrie(user, "拟再", "ni", "zai"))
    }

    @Test
    fun aLongRunOfPiecesIsLearnedAsItsWords() {
        val (session, user) = learning()
        var s = session.type("ni".repeat(LearnState.MAX_FORMED + 1))
        repeat(LearnState.MAX_FORMED + 1) { s = session.apply(Select(s.candidates.indexOf("拟"))) }
        assertEquals("拟".repeat(LearnState.MAX_FORMED + 1), s.commit)
        assertEquals(0, user.size)
        assertEquals(LearnState.MAX_FORMED + 1f, user.total)
    }

    @Test
    fun aPredictionPickedIsLearnedTextAsTypedIsNot() {
        val (session, user) = learning()
        session.type("wo")
        session.apply(Select(0))
        // 再, predicted after 我: learned after 我 for 联想, by its text alone, no count of the decoder's (review-C S1)
        assertEquals("再", session.apply(Select(0)).commit)
        assertEquals(1f, user.total)
        assertEquals(1f, predicted(user, word("我"), word("再")))
        assertEquals(0f, user.probability(word("我"), word("再")))
        session.type("vvv")
        session.apply(CommitRaw)
        // a sentence with text kept as typed
        val s = session.type("nivvv")
        assertEquals("你vvv", session.apply(Select(s.candidates.indexOf("你vvv"))).commit)
        assertEquals(1f, user.total)
    }

    // U-71: put in after a word 3 times in a table, it is offered after that word in pinyin too
    @Test
    fun whatATableOftenPutInAfterAWordIsPredictedAfterItInPinyin() {
        val (session, _) = learning()
        repeat(Predictor.PAIRS) { learner.habits.record(KeyedRows.of(Kinds.table("wubi"), emptyList(), listOf(Word("你", "")), "我")) }
        session.type("wo")
        assertEquals(listOf("再", "你"), session.apply(Select(0)).candidates.take(2))
    }

    // a 联想 picked is learned with no user model too: it is ranked by the record (H review S5)
    @Test
    fun aPredictionPickedIsLearnedWithNoUserModel() {
        learner = Learner(data)
        val session = PinyinSession(data, PinyinSegmenter(), user = null, learnTo = learner.learnTo())
        session.type("wo")
        val after = session.apply(Select(0))
        assertEquals("再", after.candidates.first())
        assertEquals("再", session.apply(Select(0)).commit)
        assertEquals(1, learner.habits.uses(PredictionOrder.code("我"), PredictionOrder.words("再")))
    }

    // UAT PY-71 (好的谢谢): the pair is learned after the word the text ends with, 你好, though the
    // decoder read it 你 好; 联想 after it looked under 好 and never found it
    @Test
    fun whatIsOftenTypedAfterAWordReadInPiecesIsPredictedAfterIt() {
        val (session, _) = learning()
        repeat(Predictor.PAIRS) {
            assertEquals("你好", session.type("nihao").let { session.apply(Select(0)) }.commit)
            assertEquals("再", session.type("zai").let { session.apply(Select(it.candidates.indexOf("再"))) }.commit)
            session.apply(Reset)
        }
        assertTrue("再" in session.type("nihao").let { session.apply(Select(0)) }.candidates)
    }

    // review-uat-fixes S11: what is offered after a word read in pieces is filed under the word, 你好:
    // 为什么 says so, and 不再联想 is asked of it, not of the 好 it ends with
    @Test
    fun whatIsOfferedAfterAWordReadInPiecesIsFiledUnderIt() {
        val (session, user) = learning()
        repeat(Predictor.PAIRS) {
            session.type("nihao").let { session.apply(Select(0)) }
            session.type("zai").let { session.apply(Select(it.candidates.indexOf("再"))) }
            session.apply(Reset)
        }
        user.predictionsOff = { prev, next -> prev == "好" && next == "再" }
        val offered = session.type("nihao").let { session.apply(Select(0)) }.candidates
        assertEquals(PredictionOrder.code("你好"), session.why(offered.indexOf("再"))?.code)
        session.apply(Reset)
        user.predictionsOff = { prev, next -> prev == "你好" && next == "再" }
        assertFalse("再" in session.type("nihao").let { session.apply(Select(0)) }.candidates)
    }

    @Test
    fun whatIsOftenTypedAfterAWordIsPredictedAfterIt() {
        val (session, _) = learning()
        // 我 then 你好 (你 and 好, as the model splits it), as 用 then 中文: the model has 再 after 我;
        // 你 after it from the third time (U-71)
        repeat(5) {
            session.type("wo")
            assertEquals(if (it < Predictor.PAIRS) listOf("再") else listOf("再", "你"), session.apply(Select(0)).candidates.take(2))
            assertEquals("你好", session.type("nihao").let { session.apply(Select(0)) }.commit)
            session.apply(Reset)
        }
        session.type("wo")
        val after = session.apply(Select(0)).candidates
        assertEquals("你", after[1])
        // picked, what follows it is offered in turn
        val next = session.apply(Select(1))
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
        // 拟 then 再, twice: the user's word 拟再
        repeat(2) {
            session.type("nizai")
            session.apply(Pick(session.candidates(0, 20).indexOfFirst { it.text == "拟" }))
            session.apply(Select(0))
            session.apply(Reset)
        }
        val before = session.type("nizai")
        assertEquals(listOf("拟再"), before.candidates)
        assertTrue(before.actionable)
        assertEquals(setOf(Offer.FORGET, Offer.PIN), session.offers(0))
        assertEquals(1, user.size)
        // forgotten from the second page, which stays shown
        session.apply(NextPage)
        val after = session.apply(Forget(session.candidates(0, 20).indexOfFirst { it.text == "拟再" }))
        // read as before: 拟再 as the sentence 拟 再, not the user's word; only 拟, picked as a piece under
        // ni, is still used there, before 你 (the text forgotten is 拟再's alone)
        assertEquals(fresh.take(4), session.candidates(0, 20).take(4))
        assertEquals(listOf("拟", "你"), session.candidates(0, 20).drop(4).map { it.text })
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
        // deleted in the app, typed again and forgotten: the context is the 拟 Backspace left
        session.apply(Backspace)
        session.type("nizai")
        session.apply(Forget(session.candidates(0, 20).indexOfFirst { it.text == "拟再" }))
        session.apply(Pick(session.candidates(0, 20).indexOfFirst { it.text == "你在" }))
        val counted = ArrayList<String>()
        user.forEachCount({ e, _ -> counted += e.toString() }, { a, b, _ -> counted += "$a $b" })
        // 你 在 and their pair, none with 拟再; none of 你 after the 拟 Backspace left: a pair is held to
        // the count of the word before it, and 拟 has none, but in the 拟再 forgotten
        assertEquals(listOf("你(ni)", "你(ni) 在(zai)", "在(zai)"), counted.sorted())
    }

    @Test
    fun theAppsLastWordForgottenIsNotLearnedAgainAsTheWordBefore() {
        val (session, user) = learning()
        session.type("zai")
        session.apply(Pick(session.candidates(0, 20).indexOfFirst { it.text == "在" }))
        session.apply(Reset)
        // the field ends in 在, nothing committed here: forgetting it drops it as the word before too
        session.apply(Action.Context("我在"))
        session.type("zai")
        session.apply(Forget(session.candidates(0, 20).indexOfFirst { it.text == "在" }))
        assertEquals("再", session.apply(Pick(session.candidates(0, 20).indexOfFirst { it.text == "再" })).commit)
        val pairs = ArrayList<String>()
        user.forEachCount({ _, _ -> }, { a, b, _ -> pairs += "$a $b" })
        assertEquals(emptyList<String>(), pairs)
    }

    @Test
    fun onlyWhatAUserModelLearnedIsForgotten() {
        // no learning record: nothing to pin, nothing learned to forget
        assertEquals(emptySet<Offer>(), session().also { it.type("ni") }.offers(0))
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

    // how often [next] was put in after [prev], as 联想 counts it
    private fun predicted(user: UserModel, prev: Int, next: Int): Float {
        var count = 0f
        user.forEachPredicted(prev) { w, c -> if (w == next) count = c }
        return count
    }

    @Test
    fun aPredictionLearnedMayBeForgottenAndAnyBlocked() {
        val learner = Learner(data)
        val user = learner.model
        val blocked = ArrayList<String>()
        val session = PinyinSession(data, PinyinSegmenter(), user = user, block = { blocked += it.text; user.block(it) }, learnTo = learner.learnTo())
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
        val learner = Learner(data)
        val user = learner.model
        val blocked = ArrayList<String>()
        val session = PinyinSession(data, PinyinSegmenter(), user = user, block = { blocked += it.text; user.block(it) }, learnTo = learner.learnTo())
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
        assertEquals(1f, predicted(user, word("再"), word("见")))
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
    fun aReadingPickedOverABetterOneTwiceIsOneWord() {
        val (session, user) = learning()
        val s = session.type("nihaoma")
        assertEquals("你好吗", s.candidates.first())
        // the first reading, picked whole: its words are learned, and no new one
        session.apply(Select(0))
        assertEquals(0, user.size)
        repeat(2) {
            val other = session.type("nizai")
            val index = other.candidates.indexOf("拟再")
            assertTrue(index > 0)
            assertEquals("拟再", session.apply(Select(index)).commit)
            assertEquals(it, user.size)
            session.apply(Reset)
        }
        // corrected to twice, after two words before, and used more than 你在: first after any (U-02 rule 3)
        session.apply(Reset)
        assertEquals("拟再", session.type("nizai").candidates.first())
    }
}
