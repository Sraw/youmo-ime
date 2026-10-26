/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.RecordingHabits
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** A [PinyinSession] of 双拼 on the 18 keys and the nine keys ([GroupedShuangpinSegmenter]): the bar, Enter, learning. */
class PinyinSessionShuangpinKeysTest {

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("西", -2f, 0f)
                .unigram("词", -3f, 0f)
                .unigram("错", -3.5f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("好", -2.5f, 0f)
                .unigram("你好", -2.5f, 0f)
                .entry("西", syl("xi"))
                .entry("词", syl("ci"))
                .entry("错", syl("cuo"))
                .entry("你", syl("ni"))
                .entry("好", syl("hao"))
                .entry("你好", syl("ni", "hao"))
                .build().toByteArray(),
        ),
    )

    private fun session(letters: NineKeyLetters = NineKeyLetters.G18, kind: String = Kinds.shuangpin("Ziranma"), habits: RecordingHabits? = null) =
        PinyinSession(
            data, GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, letters), spell = true, prediction = false,
            learnTo = if (habits == null) LearnTo() else LearnTo(habits, kind),
        )

    // 自然码's keys as [letters]' codes
    private fun Session.type(keys: String, letters: NineKeyLetters = NineKeyLetters.G18): Snapshot = keys.map { apply(Key(letters.key(it))) }.last()

    // review S2, the user's decision (b): an initial taken binds the next key as its final, read by group
    @Test
    fun anInitialTakenKeepsTheSyllableOfThatInitial() {
        val session = session()
        val typed = session.type("xi")
        assertEquals("西", typed.candidates.first())
        assertTrue(typed.syllables.toString(), typed.syllables.containsAll(listOf("xi", "ci", "cuo", "x", "c")))
        val c = session.apply(Action.Syllable(typed.syllables.indexOf("c")))
        assertTrue(c.candidates.toString(), c.candidates.isNotEmpty() && c.candidates.all { it == "词" || it == "错" })
        assertFalse("西" in c.candidates)
        // the bar now offers that initial's syllables, the bound key no initial of its own
        assertEquals(listOf("ci", "cuo"), c.syllables.sorted())
        val ci = session.apply(Action.Syllable(c.syllables.indexOf("ci")))
        assertEquals("词", ci.candidates.first())
        // back last first: ci, then c
        assertTrue(session.apply(Backspace).candidates.all { it == "词" || it == "错" })
        assertEquals("西", session.apply(Backspace).candidates.first())
    }

    @Test
    fun syllablesTakenTwoKeysEachGoBackLastFirst() {
        val session = session()
        val typed = session.type("nihk")
        assertEquals("你好", typed.candidates.first())
        val ni = session.apply(Action.Syllable(typed.syllables.indexOf("ni")))
        assertTrue(ni.preedit, ni.preedit.startsWith("ni'"))
        val both = session.apply(Action.Syllable(ni.syllables.indexOf("hao")))
        assertEquals("你好", both.candidates.first())
        assertEquals("ni'hao", session.apply(Backspace).preedit)
        assertTrue(session.apply(Backspace).syllables.contains("ni"))
    }

    // Enter keeps the reading shown, in letters, never a code (SP-18)
    @Test
    fun enterCommitsTheReadingShown() {
        val session = session()
        val shown = session.type("nihk").preedit
        val commit = session.apply(Action.CommitRaw).commit
        assertEquals(shown.filter { it != ' ' && it != '\'' }, commit)
        assertTrue(commit, commit.none { NineKeyLetters.ofCode(it.code) != null })
    }

    @Test
    fun onTheNineKeysAnApostropheSeparatesWhileTypingAlone() {
        val session = session(NineKeyLetters.NINE_SP)
        assertFalse(session.reads('\''))
        session.type("ni", NineKeyLetters.NINE_SP)
        assertTrue(session.reads('\''))
        assertTrue(session.apply(Key('\'')).handled)
        val cut = session.type("hk", NineKeyLetters.NINE_SP)
        assertTrue(cut.preedit, cut.preedit.contains('\''))
    }

    // review S9: learned under spk:<scheme>:<the keys' tag>, the nine keys' apart from the 18 keys', each scheme apart
    @Test
    fun theNineKeysLearnUnderTheirOwnSpkKindPerScheme() {
        for (scheme in listOf("Ziranma", "Xiaohe")) {
            val habits = RecordingHabits()
            val session = session(NineKeyLetters.NINE_SP, Kinds.shuangpin(scheme), habits)
            session.type("ni", NineKeyLetters.NINE_SP)
            session.apply(Action.Pick(0))
            assertEquals(Kinds.shuangpinKeys(scheme, NineKeyLetters.NINE_SP.tag()), habits.last.first().code.kind)
        }
    }
}
