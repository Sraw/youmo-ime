/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.pinyinDataOf
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.RecordingHabits
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 14 keys' session reads where each key was tapped: 不好 at the centres, 你好 tapped on n, i, h, a, o. */
class PinyinSessionTapsTest {

    // 不好 0.7 above 你好, as the model has them (−4.45 to −5.15)
    private val data = pinyinDataOf(
        Triple("你", "ni", -2.5f),
        Triple("不", "bu", -2.5f),
        Triple("好", "hao", -2.5f),
        Triple("不好", "bu hao", -3f),
        Triple("你好", "ni hao", -3.75f),
    )

    private fun session(habits: RecordingHabits? = null) = PinyinSession(
        data, T9Segmenter(abbreviations = false, letters = NineKeyLetters.G14), spell = true, prediction = false,
        learnTo = habits?.let { LearnTo(it) } ?: LearnTo(),
    )

    private val nihao = NineKeyLetters.G14.typings("nihao").single()
    private val onNihao = floatArrayOf(0.75f, 0.75f, 0.75f, 0.25f, 0.25f)
    private val onBuhao = floatArrayOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f)

    private fun Session.tap(keys: String, at: FloatArray): Snapshot = keys.indices.map { apply(Key(keys[it], at = at[it])) }.last()

    @Test
    fun tapsOnTheLettersReadAsTheyFell() {
        assertEquals("不好", session().tap(nihao, FloatArray(5) { 0.5f }).candidates[0])
        assertEquals("不好", session().type(nihao).candidates[0])
        val ni = session().tap(nihao, onNihao)
        assertEquals("你好", ni.candidates[0])
        assertEquals("ni", ni.syllables.first())
        val bu = session().tap(nihao, onBuhao)
        assertEquals("不好", bu.candidates[0])
        assertEquals("bu", bu.syllables.first())
    }

    @Test
    fun thePlacesFollowBackspaceAndAKeyTypedInTheMiddle() {
        val s = session()
        s.tap(nihao, onNihao)
        repeat(3) { s.apply(Backspace) }
        assertEquals("你好", s.tap(nihao.drop(2), onNihao.copyOfRange(2, 5)).candidates[0])
        val t = session()
        t.tap(nihao.removeRange(2, 3), floatArrayOf(0.75f, 0.75f, 0.25f, 0.25f))
        repeat(2) { t.apply(Action.Step(-1)) }
        assertEquals("你好", t.apply(Key(nihao[2], at = 0.75f)).candidates[0])
    }

    @Test
    fun aKeyTypedBeforeTheDecidingOnesMovesTheirPlacesAlong() {
        val s = session()
        s.tap(nihao.drop(1), floatArrayOf(1f, 0.75f, 0.25f, 0.25f))
        repeat(4) { s.apply(Action.Step(-1)) }
        assertEquals("你好", s.apply(Key(nihao[0], at = 0.5f)).candidates[0])
    }

    @Test
    fun afterAPartCommittedTheRestKeepItsPlaces() {
        // BN UI twice, bu then ni; 不 picked, the second BN UI reads by its own places
        val s = session()
        val both = s.tap(nihao.take(2).repeat(2), floatArrayOf(0.25f, 0.25f, 0.75f, 0.75f))
        val rest = s.apply(Action.Pick(both.candidates.indexOf("不")))
        assertEquals("你", rest.candidates[0])
    }

    @Test
    fun thePlacesComeBackWithASyllableGivenBack() {
        val s = session()
        val shown = s.tap(nihao, onNihao)
        val hao = s.apply(Action.Syllable(shown.syllables.indexOf("bu")))
        assertEquals("不好", hao.candidates[0])
        assertEquals("你好", s.apply(Backspace).candidates[0])
    }

    @Test
    fun learningFilesTheKeysWhereverTheyWereTapped() {
        fun learned(at: FloatArray?): String {
            val habits = RecordingHabits()
            val s = session(habits)
            val shown = if (at == null) s.type(nihao) else s.tap(nihao, at)
            s.apply(Action.Pick(shown.candidates.indexOf("你好")))
            return habits.commits.toString() + habits.takes.toString()
        }
        val right = learned(onNihao)
        assertTrue(right, right.contains(NineKeyLetters.G14.tag()))
        assertEquals(right, learned(floatArrayOf(1f, 1f, 0.9f, 0f, 1f)))
        val code = { rows: String -> rows.substringBefore('|') }
        assertEquals(code(right), code(learned(null)))
        assertEquals(code(right), code(learned(onBuhao)))
    }

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()
}
