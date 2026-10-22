/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.handwriting.handwritingData
import org.fcitx.fcitx5.android.engine.handwriting.learned
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Predictions]: 联想 and the text before the cursor, as handwriting and the tables share them. */
class PredictionsTest {

    private val d = handwritingData
    private val learner = Learner(d)
    private val user = learner.model
    private var learning = true
    private val predictor = Predictor(d.model, d.vocabulary, d.dictionary, user)
    private val p = Predictions(predictor, TextWords(d.model, d.wordIndex), user, habits = learner.habits) { learning }

    @Test
    fun aPickIsLearnedAfterTheWordBeforeAndWhatFollowsItOffered() {
        p.wrote("高", null)
        p.predict()
        assertEquals("山", p.offered.first().text)
        assertEquals("山", p.pick(0))
        assertEquals("山", p.lastEntry?.text)
        assertEquals("高山", p.before)
        // after 高, by its text: 联想's alone, never the decoder's (review-C S1)
        assertTrue(learner.used().containsAll(setOf("山", "高 山")))
        assertEquals(emptySet<String>(), user.learned())
    }

    // review F3183: why is the offer's, after 高, though the predictor went on after another word since
    @Test
    fun whyIsTheOffersCodeNotThePredictorsLast() {
        p.wrote("高", null)
        p.predict()
        predictor.predict(NO_WORD, d.wordIndex.find("山"))
        assertEquals("山", predictor.after)
        assertEquals(PredictionOrder.code("高"), p.why(0)?.code)
    }

    // USER-DECISIONS 2026-10-20: a message sent leaves no word before, though its text still scores
    @Test
    fun aClearedFieldHasNoWordBeforeUntilSomethingIsPutIn() {
        p.learnFrom(Action.Context("高山", cleared = true))
        p.goOnFrom("高山")
        assertEquals("", p.wordBefore())
        assertEquals("高山", p.before)
        p.deleted()
        assertEquals("高", p.before)
        assertEquals("", p.wordBefore())
        p.wrote("山", null)
        assertTrue(p.wordBefore().isNotEmpty())
        // a context not cleared: the field's text is a word before again
        p.learnFrom(Action.Context("高", cleared = true))
        p.learnFrom(Action.Context("高"))
        p.goOnFrom("高")
        assertEquals("高", p.wordBefore())
    }

    // review-1a-r2 S1: a context that says no flags (the engine's own) keeps the field's
    @Test
    fun aContextOfNoFlagsKeepsTheFieldsFlags() {
        p.learnFrom(Action.Context("", learnFlags = Commit.NO_PREDICT))
        p.learnFrom(Action.Context("高"))
        assertEquals(Commit.NO_PREDICT, p.learnFlags)
        p.learnFrom(Action.Context("高", learnFlags = 0))
        assertEquals(0, p.learnFlags)
    }

    // U-70: filed under the word the predictions follow, though nothing learned says how it reads
    // (handwriting's 高, put in by its text): what an elder writes on with, a character at a time
    @Test
    fun aPickIsFiledUnderTheWordItFollowsByItsText() {
        p.wrote("高", null)
        p.predict()
        p.pick(0)
        val state = learner.log.state
        assertEquals(1, learner.habits.uses(PredictionOrder.code("高"), PredictionOrder.words("山")))
        assertTrue(state.pairs.keys.any { it.prev == "高" && it.next == "山" && it.nextReading.isEmpty() })
    }

    // U-70, as pinyin's: picked once before those never picked, twice running first
    @Test
    fun whatIsOfferedIsInTheUsersOrder() {
        // 人, picked after 高 once when it was offered
        learner.habits.record(listOf(PredictionOrder.row("人", "高", "山")))
        p.wrote("高", null)
        p.predict()
        assertEquals(listOf("山", "人"), p.offered.map { it.text })
        assertEquals("人", p.pick(1))
        p.wrote("高", null)
        p.predict()
        assertEquals(listOf("人", "山"), p.offered.map { it.text })
    }

    @Test
    fun aPickPastTheOfferPutsInNothing() {
        p.wrote("高", null)
        p.predict()
        val offered = p.offered
        assertNull(p.pick(offered.size))
        assertEquals(offered, p.offered)
        assertEquals("高", p.before)
    }

    @Test
    fun theOfferTakenAwayKeepsTheWordBefore() {
        p.wrote("高", null)
        p.predict()
        p.pick(0)
        p.unoffer()
        assertTrue(p.offered.isEmpty())
        assertEquals("山", p.lastEntry?.text)
    }

    @Test
    fun theTextBeforeNeverStartsInTheMiddleOfAPair() {
        p.goOnFrom("x😀" + "a".repeat(127))
        assertFalse(Character.isLowSurrogate(p.before[0]))
        learning = false
        p.goOnFrom("x😀" + "a".repeat(Predictions.WORDS_KEPT - 1))
        assertFalse(Character.isLowSurrogate(p.before[0]))
        assertTrue(p.before.length < Predictions.WORDS_KEPT)
        // nothing learned: nothing offered, nor told a sentence model
        p.predict()
        assertTrue(p.offered.isEmpty())
        assertEquals("", p.context)
    }

    @Test
    fun noPredictorOffersNothing() {
        val none = Predictions(null, TextWords(d.model, d.wordIndex), user) { true }
        none.wrote("高", null)
        none.predict()
        assertTrue(none.offered.isEmpty())
        none.forgetBefore()
        assertEquals("", none.before)
    }

    @Test
    fun whatTheAppDeletedIsNoLongerBeforeNorTheWordBeforeLearnedAfter() {
        p.wrote("😀高", null)
        p.predict()
        assertEquals("山", p.pick(0))
        p.deleted()
        assertNull(p.lastEntry)
        assertEquals("😀高", p.before)
        p.deleted()
        // a character past the BMP goes whole
        p.deleted()
        assertEquals("", p.before)
        p.deleted()
        assertEquals("", p.before)
    }

    @Test
    fun aLongPressForgetsWhatWasLearnedOfAPredictionOrBlocksItNotWhereNothingIsLearned() {
        val blocked = ArrayList<UserModel.Entry>()
        val forgotten = ArrayList<String>()
        // the host forgets it in the learning record (Engines.forgottenEverywhere)
        val q = Predictions(
            Predictor(d.model, d.vocabulary, d.dictionary, user), TextWords(d.model, d.wordIndex), user, { blocked += it },
            { forgotten += it; learner.habits.forget(it) }, habits = learner.habits,
        ) { learning }
        q.wrote("高", null)
        q.predict()
        // never picked: nothing learned of it to forget
        assertEquals(setOf(Offer.BLOCK), q.offers(0))
        assertFalse(q.press(0, Offer.FORGET))
        q.pick(0)
        q.wrote("高", null)
        q.predict()
        assertEquals(setOf(Offer.FORGET, Offer.BLOCK), q.offers(0))
        assertTrue(q.press(0, Offer.FORGET))
        assertEquals(listOf("山"), forgotten)
        assertFalse(user.learned().any { "山" in it })
        assertTrue(q.press(0, Offer.BLOCK))
        assertEquals("山", blocked.single().text)
        // where nothing is to be kept (a password): forgetting and blocking record the word
        learning = false
        assertEquals(emptySet<Offer>(), q.offers(0))
        assertFalse(q.press(0, Offer.BLOCK))
        // none without a way to block
        learning = true
        assertFalse(Offer.BLOCK in p.apply { wrote("高", null); predict() }.offers(0))
    }

    @Test
    fun aPredictionIsBlockedOnlyWhereTheInputMethodCouldBlockItsText() {
        val blocked = ArrayList<UserModel.Entry>()
        val q = Predictions(
            Predictor(d.model, d.vocabulary, d.dictionary, user), TextWords(d.model, d.wordIndex), user, { blocked += it },
            blockable = { it.length >= 2 },
        ) { learning }
        q.wrote("高", null)
        q.predict()
        assertEquals("山", q.offered.first().text)
        assertEquals(emptySet<Offer>(), q.offers(0))
        assertFalse(q.press(0, Offer.BLOCK))
        assertTrue(blocked.isEmpty())
    }
}
