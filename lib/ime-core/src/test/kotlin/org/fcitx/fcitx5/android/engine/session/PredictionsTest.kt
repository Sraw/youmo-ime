/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.handwriting.handwritingData
import org.fcitx.fcitx5.android.engine.handwriting.learned
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Predictions]: 联想 and the text before the cursor, as handwriting and the tables share them. */
class PredictionsTest {

    private val d = handwritingData
    private val user = UserModel(d.dictionary, d.vocabulary)
    private var learning = true
    private val p = Predictions(Predictor(d.model, d.vocabulary, d.dictionary, user), TextWords(d.model, d.wordIndex), user) { learning }

    @Test
    fun aPickIsLearnedAfterTheWordBeforeAndWhatFollowsItOffered() {
        p.wrote("高", null)
        p.predict()
        assertEquals("山", p.offered.first().text)
        assertEquals("山", p.pick(0))
        assertEquals("山", p.lastEntry?.text)
        assertEquals("高山", p.before)
        assertEquals(setOf("山(shan)"), user.learned())
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
        val q = Predictions(Predictor(d.model, d.vocabulary, d.dictionary, user), TextWords(d.model, d.wordIndex), user, { blocked += it }, { forgotten += it }) { learning }
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
