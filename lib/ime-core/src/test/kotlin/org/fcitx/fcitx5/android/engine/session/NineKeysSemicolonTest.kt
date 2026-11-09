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
        // 自然码's ing is y: its keyboard sends 1 while typing as the separator ' (T9Keyboard.resolveShuangpin)
        val s = session(ShuangpinScheme.ZIRANMA)
        s.type("y")
        val cut = s.apply(Key('\''))
        assertFalse(cut.syllables.toString(), "ying" in cut.syllables)
        assertEquals("y", cut.preedit)
        val typed = s.type("yi")
        assertEquals("一一", typed.candidates.first())
        // nothing read as ;: y, then yi, committed as typed
        assertEquals("yyi", s.apply(Action.CommitRaw).commit)
    }

    @Test
    fun oneAfterAKeyMakingNoSyllableWithItSeparates() {
        // 4 (GHI) then 1: g; h; i; (ch;) are no syllables, so the separator, never a ; no reading takes
        val s = session()
        s.type("g1")
        val raw = s.apply(Action.CommitRaw).commit
        assertFalse(raw, ';' in raw)
    }

    @Test
    fun oneAfterAnInitialTakenThatMakesNoSyllableWithItSeparates() {
        val s = session()
        val typed = s.type("g")
        s.apply(Action.Syllable(typed.syllables.indexOf("g")))
        s.type("1")
        val raw = s.apply(Action.CommitRaw).commit
        assertFalse(raw, ';' in raw)
    }

    // the input as the session has it, each case read where 1 is pressed
    private val ms = GroupedShuangpinSegmenter(ShuangpinScheme.MICROSOFT, NINE_SP)
    private fun read(input: String, from: Int = 0, cursor: Int = input.length) =
        semicolonRead(NINE_SP_SEMICOLON, ms, input, from, cursor)
    private fun k(letters: String) = letters.map { NINE_SP.key(it) }.joinToString("")

    @Test
    fun whereOneIsReadAsIngOrTheSeparator() {
        val sep = '\''
        // y: ing; y; then 1: the separator (y;')
        assertEquals(NINE_SP_SEMICOLON, read(k("y")))
        assertEquals(sep, read(k("y;")))
        // after what is committed already (from > 0): counted from there
        assertEquals(NINE_SP_SEMICOLON, read(k("yi") + k("y"), from = 2))
        assertEquals(sep, read(k("yi") + k("y"), from = 1))
        // the cursor inside the input: the keys before it
        assertEquals(NINE_SP_SEMICOLON, read(k("yyi"), cursor = 1))
        assertEquals(sep, read(k("yyi"), cursor = 2))
        // a zero-initial syllable, o h (ang): whole, so 1 after it separates
        assertEquals(sep, read(k("oh")))
        // a whole syllable taken off the bar: the separator; an initial taken: its final, if it makes one
        assertEquals(sep, read("ying'"))
        assertEquals(NINE_SP_SEMICOLON, read("y'"))
        assertEquals(sep, read("g'"))
        // 4 then 1: no syllable, the separator
        assertEquals(sep, read(k("g")))
        // nothing before it: swallowed
        assertNull(read(""))
        assertNull(read(k("yi"), from = 2))
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
