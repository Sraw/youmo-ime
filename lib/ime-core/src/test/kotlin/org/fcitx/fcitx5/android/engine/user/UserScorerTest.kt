/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.user.UserFixtures.data
import org.fcitx.fcitx5.android.engine.user.UserFixtures.entry
import org.fcitx.fcitx5.android.engine.user.UserFixtures.model
import org.fcitx.fcitx5.android.engine.user.UserFixtures.syl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.log10

class UserScorerTest {

    private val plain = WordScorer.of(data.model)

    @Test
    fun wordsNeverTypedScoreAsTheModelSays() {
        val m = model()
        val ni = m.id(entry("你", "ni"))
        val scorer = UserScorer(data.model, m)
        m.learn(null, listOf(entry("好", "hao")))
        assertEquals(plain.score(NO_WORD, NO_WORD, ni), scorer.score(NO_WORD, NO_WORD, ni))
        assertEquals(plain.score(NO_WORD, ni, ni), scorer.score(NO_WORD, ni, ni))
    }

    @Test
    fun wordsTypedGainTheirShare() {
        val m = model()
        val ni = m.learn(null, listOf(entry("拟", "ni")))[0]
        val scorer = UserScorer(data.model, m, weight = 0.5f)
        val expected = log10(Math.pow(10.0, plain.score(NO_WORD, NO_WORD, ni).toDouble()) + 0.5 / (1 + UserModel.UNSEEN)).toFloat()
        assertEquals(expected, scorer.score(NO_WORD, NO_WORD, ni), 1e-6f)
        // through a context made once, as the decoder asks
        assertEquals(expected, scorer.scoreAfter(scorer.context(NO_WORD, NO_WORD), ni), 1e-6f)
        // a word of the user's own scores as the model's unknown word plus its share
        val nihao = m.learn(null, listOf(entry("拟好", "ni", "hao")))[0]
        assertTrue(scorer.score(NO_WORD, NO_WORD, nihao) > plain.score(NO_WORD, NO_WORD, nihao))
    }

    @Test
    fun aPacksWordTakesItsScoreInPlaceOfTheUnknownWordsUnderTheSameBackoff() {
        val m = model()
        // as Engines lists a pack's words: the model's own keep their scores
        val modelWords = data.model.vocabularySize
        val ni = m.id(entry("你", "ni"))
        val nihao = m.list(entry("拟好", "ni", "hao"), -4f, 1, modelWords)
        val scorer = UserScorer(data.model, m)
        assertEquals(-4f, scorer.score(NO_WORD, NO_WORD, nihao), 1e-6f)
        // after 你: the unknown word's score there, moved by the same amount
        assertEquals(plain.score(NO_WORD, ni, nihao) + 3f, scorer.score(NO_WORD, ni, nihao), 1e-6f)
        // a model word listed by a pack scores as the model says
        assertEquals(-2f, m.list(entry("你", "ni"), -1f, 1, modelWords).let { scorer.score(NO_WORD, NO_WORD, it) }, 1e-6f)
        // and never above 0
        assertEquals(0f, UserScorer(data.model, m).score(NO_WORD, NO_WORD, m.list(entry("拟吗", "ni", "ma"), 0f, 1, modelWords)), 0f)
    }

    @Test
    fun anImportedWordAtANewReadingIsTheUnknownWordTillPicked() {
        val d = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f).unigram("你", -2f, -0.5f).unigram("好", -2.5f, 0f)
                    .bigram("你", "好", -0.5f, 0f)
                    .entry("你", syl("ni")).entry("好", syl("hao"))
                    .build().toByteArray(),
            ),
        )
        val m = UserModel(d.dictionary, d.vocabulary, index = d.wordIndex)
        val lm = WordScorer.of(d.model)
        val scorer = UserScorer(d.model, m, weight = 0f)
        val ni = m.id(entry("你", "ni"))
        val hao = m.id(entry("好", "hao"))
        // from a dictionary of the user's: not their choice yet
        val nin = m.list(entry("你", "nin"))
        // the unknown word's scores differ from 你's: what is asserted below tells them apart
        assertNotEquals(lm.score(NO_WORD, NO_WORD, ni), lm.score(NO_WORD, NO_WORD, nin))
        assertNotEquals(lm.score(NO_WORD, ni, hao), lm.score(NO_WORD, nin, hao))
        assertEquals(lm.score(NO_WORD, NO_WORD, nin), scorer.score(NO_WORD, NO_WORD, nin))
        assertEquals(lm.score(NO_WORD, nin, hao), scorer.score(NO_WORD, nin, hao))
        // the user's: the model's 你, scored and as context
        m.own(entry("你", "nin"))
        assertEquals(lm.score(NO_WORD, NO_WORD, ni), scorer.score(NO_WORD, NO_WORD, nin))
        assertEquals(lm.score(NO_WORD, ni, hao), scorer.score(NO_WORD, nin, hao))
        // the user's without being listed maps too
        val nian = m.own(entry("你", "nian"))
        assertEquals(lm.score(NO_WORD, ni, hao), scorer.score(NO_WORD, nian, hao))
        // added by hand then taken off, never typed, it no longer does
        val ning = m.listByHand(entry("你", "ning"))
        assertEquals(lm.score(NO_WORD, NO_WORD, ni), scorer.score(NO_WORD, NO_WORD, ning))
        assertEquals(lm.score(NO_WORD, ni, hao), scorer.score(NO_WORD, ning, hao))
        m.unlistByHand(entry("你", "ning"))
        assertEquals(lm.score(NO_WORD, NO_WORD, ning), scorer.score(NO_WORD, NO_WORD, ning))
        assertEquals(lm.score(NO_WORD, ning, hao), scorer.score(NO_WORD, ning, hao))
        // added by hand and the user's too, then taken off: theirs, it still does
        val nie = m.listByHand(entry("你", "nie"))
        m.own(entry("你", "nie"))
        m.unlistByHand(entry("你", "nie"))
        assertEquals(lm.score(NO_WORD, NO_WORD, ni), scorer.score(NO_WORD, NO_WORD, nie))
        assertEquals(lm.score(NO_WORD, ni, hao), scorer.score(NO_WORD, nie, hao))
    }

    @Test
    fun aWordAddedByHandPastTheFirstWordsArraysIsTheModelsWord() {
        val d = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f).unigram("你", -2f, -0.5f).unigram("好", -2.5f, 0f)
                    .bigram("你", "好", -0.5f, 0f)
                    .entry("你", syl("ni")).entry("好", syl("hao"))
                    .build().toByteArray(),
            ),
        )
        val m = UserModel(d.dictionary, d.vocabulary, index = d.wordIndex)
        val lm = WordScorer.of(d.model)
        val scorer = UserScorer(d.model, m, weight = 0f)
        val ni = m.id(entry("你", "ni"))
        // more new words than the arrays are made for at first (16): they grow
        val readings = listOf("a", "ai", "an", "ang", "ao", "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi", "bian", "biao", "bie")
        readings.forEach { m.list(entry("好", it)) }
        val nin = m.listByHand(entry("你", "nin"))
        assertEquals(d.vocabulary.size + readings.size, nin)
        assertEquals(lm.score(NO_WORD, NO_WORD, ni), scorer.score(NO_WORD, NO_WORD, nin))
        m.unlistByHand(entry("你", "nin"))
        assertEquals(lm.score(NO_WORD, NO_WORD, nin), scorer.score(NO_WORD, NO_WORD, nin))
    }

    @Test
    fun aWordOfTheUsersWhoseTextTheModelHasIsThatWordScoredAndAsContext() {
        // 你 before 好 and 你 好 before 吗 seen: the context a listed 你 must keep
        val d = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f).unigram("你", -2f, -0.5f).unigram("好", -2.5f, 0f).unigram("吗", -3.5f, 0f)
                    .bigram("你", "好", -0.5f, 0f).bigram("好", "吗", -1f, 0f).trigram("你", "好", "吗", -0.1f)
                    .entry("你", syl("ni")).entry("好", syl("hao")).entry("吗", syl("ma"))
                    .build().toByteArray(),
            ),
        )
        val m = UserModel(d.dictionary, d.vocabulary, index = d.wordIndex)
        // added by hand at a reading the dictionary lacks: an id of the user's
        val nin = m.listByHand(entry("你", "nin"))
        val ni = m.id(entry("你", "ni"))
        val hao = m.id(entry("好", "hao"))
        val ma = m.id(entry("吗", "ma"))
        assertTrue(nin >= d.vocabulary.size)
        val lm = WordScorer.of(d.model)
        val scorer = UserScorer(d.model, m)
        assertEquals(lm.score(NO_WORD, NO_WORD, ni), scorer.score(NO_WORD, NO_WORD, nin))
        // as context, the bigram and the trigram after it, where the unknown word has none
        assertNotEquals(lm.score(NO_WORD, ni, hao), lm.score(NO_WORD, nin, hao))
        assertEquals(lm.score(NO_WORD, ni, hao), scorer.score(NO_WORD, nin, hao))
        assertEquals(lm.score(NO_WORD, ni, hao), scorer.scoreAfter(scorer.context(NO_WORD, nin), hao))
        assertEquals(lm.score(ni, hao, ma), scorer.score(nin, hao, ma))
        // a word the model lacks is still the unknown word, its pack score in place of it
        val nihao = m.list(entry("你好", "ni", "hao"))
        assertEquals(lm.score(NO_WORD, NO_WORD, nihao), scorer.score(NO_WORD, NO_WORD, nihao))
        // even added by hand: no word of the model's has its text
        val nima = m.listByHand(entry("你吗", "ni", "ma"))
        assertEquals(lm.score(NO_WORD, NO_WORD, nima), scorer.score(NO_WORD, NO_WORD, nima))
        assertEquals(lm.score(NO_WORD, nima, hao), scorer.score(NO_WORD, nima, hao))
        assertEquals(-4f, scorer.score(NO_WORD, NO_WORD, m.list(entry("你吗", "ni", "ma"), -4f, 1, d.model.vocabularySize)), 1e-6f)
        // the user's counts stay the listed word's: typed so, 好 after it gains its share
        m.learn(null, listOf(entry("你", "nin"), entry("好", "hao")))
        assertTrue(scorer.score(NO_WORD, nin, hao) > lm.score(NO_WORD, ni, hao))
    }

    @Test
    fun scoresStayLogProbabilities() {
        val m = model()
        val ni = m.learn(null, listOf(entry("你", "ni")))[0]
        assertEquals(0f, UserScorer(data.model, m, weight = 100f).score(NO_WORD, NO_WORD, ni))
    }
}
