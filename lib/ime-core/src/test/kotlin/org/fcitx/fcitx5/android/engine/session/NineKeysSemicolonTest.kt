/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters.Companion.NINE_SP
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters.Companion.NINE_SP_SEMICOLON
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** 九键双拼's 1 as 微软's `;` (ing) where a final goes, the separator where an initial does. */
class NineKeysSemicolonTest {

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("应", -2f, 0f)
                .unigram("一", -2f, 0f)
                .unigram("你", -2f, 0f)
                .entry("应", syl("ying"))
                .entry("一", syl("yi"))
                .entry("你", syl("ni"))
                .build().toByteArray(),
        ),
    )

    private fun session(scheme: ShuangpinScheme = ShuangpinScheme.MICROSOFT) =
        PinyinSession(data, GroupedShuangpinSegmenter(scheme, NINE_SP), spell = true, prediction = false)

    // letters as their keys' codes, 1 as T9Keyboard sends it on 微软's keys while typing
    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(if (it == '1') NINE_SP_SEMICOLON else NINE_SP.key(it))) }.last()

    @Test
    fun oneAfterAnInitialIsIng() {
        val s = session()
        val typed = s.type("y1")
        assertEquals("应", typed.candidates.first())
        assertTrue(typed.syllables.toString(), "ying" in typed.syllables)
        assertFalse(typed.preedit, '\'' in typed.preedit)
    }

    @Test
    fun oneWhereAnInitialGoesSeparates() {
        val s = session()
        assertEquals("一", s.type("yi1").candidates.first())
        // the key after the separator an initial again: y, then 1 its ing
        val both = s.type("y1")
        assertEquals("一应", both.candidates.first())
    }

    @Test
    fun backspaceTakesTheIngBackToItsInitial() {
        val s = session()
        s.type("y1")
        val back = s.apply(Backspace)
        assertFalse(back.syllables.toString(), "ying" in back.syllables)
        assertEquals("应", s.type("1").candidates.first())
    }

    @Test
    fun anInitialTakenBindsOneAsItsFinal() {
        val s = session()
        val typed = s.type("y")
        val y = s.apply(Action.Syllable(typed.syllables.indexOf("y")))
        assertEquals("y", y.preedit)
        assertEquals("应", s.type("1").candidates.first())
    }

    @Test
    fun otherSchemesHaveNoIngOnOne() {
        // 自然码's ing is y: its keyboard sends 1 as the separator, and the code reads as nothing
        val s = session(ShuangpinScheme.ZIRANMA)
        val typed = s.type("yy")
        assertEquals("应", typed.candidates.first())
        s.apply(Backspace)
        val one = s.type("1")
        assertFalse(one.syllables.toString(), "ying" in one.syllables)
    }

    // the record files the nine keys' codes under the tag they had with ; on 5: what was learned is still theirs
    @Test
    fun theTagIsWhatItWas() {
        val old = "abc,def,ghi,jkl;,mno,pqrs,tuv,wxyz"
        assertEquals(old, NINE_SP.tag())
        assertEquals(NINE_SP, NineKeyLetters.grouped(old))
        assertEquals(NINE_SP.tag(), Kinds.keysTag(Kinds.shuangpinKeys("ms", NINE_SP.tag())))
        // 2 to 9's codes as they were; 5 now jkl alone, ; a code of its own
        assertEquals("", NINE_SP.codes)
        assertEquals("jkl", NINE_SP.letters(''))
        assertEquals('', NINE_SP.key(';'))
        assertEquals("jkl ;", NINE_SP.shown(""))
        assertNull(NineKeyLetters.ofCode(0xF8FD))
    }
}
