/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.RecordingHabits
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The 14 keys' session reading where each key was tapped (design k14-tap-position §2): BN UI GH
 * AS OP is 不好 at the keys' centres, 你好 tapped on n, i, h, a and o; the places follow the input
 * through its edits, and learning files the keys alone.
 */
class PinyinSessionTapsTest {

    // 不好 0.7 above 你好, as the model has them (−4.45 to −5.15)
    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2.5f, 0f)
                .unigram("不", -2.5f, 0f)
                .unigram("好", -2.5f, 0f)
                .unigram("不好", -3f, 0f)
                .unigram("你好", -3.75f, 0f)
                .entry("你", syl("ni"))
                .entry("不", syl("bu"))
                .entry("好", syl("hao"))
                .entry("不好", syl("bu", "hao"))
                .entry("你好", syl("ni", "hao"))
                .build().toByteArray(),
        ),
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
        // GH left out, then typed before AS
        val t = session()
        t.tap(nihao.removeRange(2, 3), floatArrayOf(0.75f, 0.75f, 0.25f, 0.25f))
        repeat(2) { t.apply(Action.Step(-1)) }
        assertEquals("你好", t.apply(Key(nihao[2], at = 0.75f)).candidates[0])
    }

    @Test
    fun aKeyTypedBeforeTheDecidingOnesMovesTheirPlacesAlong() {
        // code review N2: BN typed last, at its centre, before UI tapped at its right edge (i: a whole
        // α against bu, over the 0.75 the model has 不好 by); only UI's place, moved one along, makes 你好 first
        val s = session()
        s.tap(nihao.drop(1), floatArrayOf(1f, 0.75f, 0.25f, 0.25f))
        repeat(4) { s.apply(Action.Step(-1)) }
        assertEquals("你好", s.apply(Key(nihao[0], at = 0.5f)).candidates[0])
    }

    @Test
    fun afterAPartCommittedTheRestKeepItsPlaces() {
        // code review N3: BN UI twice, bu then ni; 不 picked, the second BN UI reads by its own places
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
        // bu given back: the keys as tapped, 你好 again
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
        // the code the same where the first shown was another
        val code = { rows: String -> rows.substringBefore('|') }
        assertEquals(code(right), code(learned(null)))
        assertEquals(code(right), code(learned(onBuhao)))
    }

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()
}
