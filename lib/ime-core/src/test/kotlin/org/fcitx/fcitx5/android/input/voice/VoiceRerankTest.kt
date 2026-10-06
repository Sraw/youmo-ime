/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.VoiceRerank.Hypothesis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VoiceRerankTest {

    @Test
    fun readAsItIsReadAloud() {
        assertEquals("你好world2", VoiceRerank.bare("你好， world 2。"))
        assertEquals("说好", VoiceRerank.bare("说：“好”！"))
        // a symbol is read, as Unicode has them: + is one, % punctuation
        assertEquals("1+15", VoiceRerank.bare("1+1，5%。"))
    }

    @Test
    fun punctuatedOtherwiseIsTheSame() {
        val nbest = listOf(Hypothesis("他现在恨佛系", -0.1f), Hypothesis("他现在很佛系", -0.2f), Hypothesis("他现在恨佛系。", -0.3f))
        assertEquals(nbest.take(2), VoiceRerank.distinct(nbest))
    }

    @Test
    fun onlyChineseCharactersHeardOtherwiseAreCandidates() {
        val top = Hypothesis("我会看 olympic games 吗？", -0.1f)
        val homophone = Hypothesis("我会看 olympic games 嘛", -0.2f)
        val dropped = Hypothesis("我会看吗", -0.3f)
        val shorter = Hypothesis("我看 olympic games 吗", -0.4f)
        val longer = Hypothesis("我会看 olympic games 吗啊", -0.5f)
        val english = Hypothesis("我会看 olympic game 吗", -0.6f)
        assertEquals(listOf(top, homophone, shorter, longer), VoiceRerank.candidates(listOf(top, homophone, dropped, shorter, longer, english)))
        assertEquals(emptyList<Hypothesis>(), VoiceRerank.candidates(emptyList()))
    }

    @Test
    fun theModelOutweighsALittleOfTheRecognizers() {
        val heard = Hypothesis("他现在恨佛系", -0.15f)
        val written = Hypothesis("他现在很佛系", -0.26f)
        // log10 P of each, per char ×0.7: -16.1 → -1.88, -12.0 → -1.4
        assertSame(written, VoiceRerank.best(listOf(heard, written), floatArrayOf(-16.1f, -12.0f)))
        // the recognizer far surer
        assertSame(heard, VoiceRerank.best(listOf(heard, written.copy(score = -2f)), floatArrayOf(-16.1f, -12.0f)))
    }

    @Test
    fun noScoresTheRecognizersBest() {
        val a = Hypothesis("甲乙", -0.1f)
        val b = Hypothesis("丙丁", -0.2f)
        assertSame(a, VoiceRerank.best(listOf(a, b), null))
        assertSame(a, VoiceRerank.best(listOf(a, b), floatArrayOf(0f)))
        assertSame(a, VoiceRerank.best(listOf(a), floatArrayOf(0f)))
    }
}
