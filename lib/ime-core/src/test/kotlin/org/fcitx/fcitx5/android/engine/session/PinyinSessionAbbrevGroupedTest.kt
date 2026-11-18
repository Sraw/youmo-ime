/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双拼's 简拼 in the middle on the 18, 14 and nine keys (自然码, 061-sp-abbrev §3.1): a key read alone where it and the
 * next make a syllable reads the words the user typed, as on 26 keys, placed as there (§3.3).
 */
class PinyinSessionAbbrevGroupedTest {

    private val data = pinyinDataOf(
        Triple("什么", "shen me", -2f), Triple("时候", "shi hou", -2f), Triple("什么时候", "shen me shi hou", -3f),
        Triple("快", "kuai", -2f), Triple("可以", "ke yi", -3f), Triple("下面", "xia mian", -3f), Triple("小米", "xiao mi", -3.5f),
        Triple("先", "xian", -2f), Triple("下", "xia", -2.5f), Triple("面", "mian", -2.5f),
    )

    private val kind = Kinds.shuangpin("Ziranma")

    private val keyboards = listOf(NineKeyLetters.G18, NineKeyLetters.G14, NineKeyLetters.NINE_SP)

    private fun session(letters: NineKeyLetters, learner: Learner?) = PinyinSession(
        data, GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, letters), pageSize = 20, prediction = false,
        user = learner?.model, learnTo = learner?.learnTo(kind) ?: LearnTo(),
    )

    // 自然码's keys as [letters]' codes; ' as the nine keys' 1 (which sends ; and reads it so where no final goes)
    private fun codes(keys: String, letters: NineKeyLetters) =
        keys.map { if (it == '\'' && letters == NineKeyLetters.NINE_SP) NineKeyLetters.NINE_SP_SEMICOLON else letters.key(it) }.joinToString("")

    private fun Session.shown(keys: String, letters: NineKeyLetters): List<String> = codes(keys, letters).map { apply(Key(it)) }.last().candidates.also { apply(Reset) }

    private fun Session.pick(keys: String, letters: NineKeyLetters, text: String) {
        val shown = codes(keys, letters).map { apply(Key(it)) }.last().candidates
        assertTrue("$letters $keys: $text in $shown", text in shown)
        apply(Select(shown.indexOf(text)))
        apply(Reset)
    }

    @Test
    @Uat("SP-52", "SP-53", "SP-54")
    fun aWordTypedIsReadByItsInitialsOnEveryKeyboard() {
        for (letters in keyboards) {
            val learner = Learner(data)
            val s = session(letters, learner)
            // never typed: not by its initials
            assertTrue("$letters", "什么时候" !in s.shown("ufmuh", letters))
            s.pick("ufmeuihb", letters, "什么时候")
            // uf, then m, u, h alone: no whole syllables to the end, so first
            assertEquals("$letters", "什么时候", s.shown("ufmuh", letters).first())
            // a pair: 可以 by k and y alone, after kuai and what else the pair reads whole
            s.pick("keyi", letters, "可以")
            val ky = s.shown("ky", letters)
            assertEquals("$letters $ky", 1, ky.indexOf("可以"))
        }
    }

    // habits off the session has no word typed (SessionFactory): no key is read alone
    @Test
    @Uat("SP-46")
    fun withNoHabitsAKeyIsNotReadAlone() {
        for (letters in keyboards) {
            assertTrue("$letters", "什么时候" !in session(letters, null).shown("ufmuh", letters))
            assertEquals("$letters", "什么时候", session(letters, null).shown("ufmeuihb", letters).first())
        }
    }

    // the nine keys' 1 is the separator: x then m begun reads the whole dictionary, the word typed first (§3.1)
    @Test
    @Uat("SP-58")
    fun theNineKeysOneKeyReadsAsTheSeparator() {
        val letters = NineKeyLetters.NINE_SP
        val learner = Learner(data)
        val s = session(letters, learner)
        s.pick("xwmm", letters, "下面")
        val shown = s.shown("x'm", letters)
        assertEquals("$shown", "下面", shown.first())
        assertTrue("小米, never typed: $shown", "小米" in shown)
    }
}
