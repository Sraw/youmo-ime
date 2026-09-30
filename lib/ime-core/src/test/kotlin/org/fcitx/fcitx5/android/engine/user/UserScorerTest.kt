/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.user.UserModelTest.Companion.data
import org.fcitx.fcitx5.android.engine.user.UserModelTest.Companion.entry
import org.fcitx.fcitx5.android.engine.user.UserModelTest.Companion.model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
        val expected = log10(Math.pow(10.0, plain.score(NO_WORD, NO_WORD, ni).toDouble()) + 0.5 / 21).toFloat()
        assertEquals(expected, scorer.score(NO_WORD, NO_WORD, ni), 1e-6f)
        // through a context made once, as the decoder asks
        assertEquals(expected, scorer.scoreAfter(scorer.context(NO_WORD, NO_WORD), ni), 1e-6f)
        // a word of the user's own scores as the model's unknown word plus its share
        val nihao = m.learn(null, listOf(entry("拟好", "ni", "hao")))[0]
        assertTrue(scorer.score(NO_WORD, NO_WORD, nihao) > plain.score(NO_WORD, NO_WORD, nihao))
    }

    @Test
    fun scoresStayLogProbabilities() {
        val m = model()
        val ni = m.learn(null, listOf(entry("你", "ni")))[0]
        assertEquals(0f, UserScorer(data.model, m, weight = 100f).score(NO_WORD, NO_WORD, ni))
    }
}
