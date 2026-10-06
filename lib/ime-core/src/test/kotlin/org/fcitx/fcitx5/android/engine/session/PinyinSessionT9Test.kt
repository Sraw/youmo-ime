/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PinyinSessionT9Test {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("米", -3f, 0f)
                .unigram("哦", -4f, 0f)
                .unigram("好", -2.5f, 0f)
                .unigram("高", -3f, 0f)
                .unigram("你好", -2.5f, 0f)
                .unigram("米糕", -5f, 0f)
                .entry("你", syl("ni"))
                .entry("米", syl("mi"))
                .entry("哦", syl("o"))
                .entry("好", syl("hao"))
                .entry("高", syl("gao"))
                .entry("你好", syl("ni", "hao"))
                .entry("米糕", syl("mi", "gao"))
                .build().toByteArray(),
        ),
    )

    private fun session() = PinyinSession(data, T9Segmenter(abbreviations = false), spell = true, prediction = false)

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    @Test
    fun digitsShowAsThePinyinOfTheBestReading() {
        val s = session().type("64426")
        assertEquals("你好", s.candidates[0])
        assertEquals("ni hao", s.preedit)
        // the first digits' syllables, the best reading's first, the letters of 6 last
        assertEquals(listOf("ni", "mi", "o", "m", "n"), s.syllables)
    }

    @Test
    fun aSyllableTakenNarrowsTheReadingsAndBackspaceGivesItBack() {
        val session = session()
        session.type("64426")
        val taken = session.apply(Action.Syllable(1))
        assertEquals("米糕", taken.candidates[0])
        // a syllable taken stands apart
        assertEquals("mi'gao", taken.preedit)
        // offered next: the digits after it
        assertTrue("gao" in taken.syllables && "hao" in taken.syllables)
        val back = session.apply(Backspace)
        assertEquals("你好", back.candidates[0])
        assertEquals(listOf("ni", "mi", "o", "m", "n"), back.syllables)
        // then a digit goes
        assertEquals("ni ha", session.apply(Backspace).preedit)
    }

    @Test
    fun twoTakenThenBackspaceGivesBackTheLast() {
        val session = session()
        session.type("64426")
        val first = session.apply(Action.Syllable(0))
        val both = session.apply(Action.Syllable(first.syllables.indexOf("hao")))
        assertEquals("你好", both.candidates[0])
        assertEquals(emptyList<String>(), both.syllables)
        val back = session.apply(Backspace)
        assertEquals("ni'hao", back.preedit)
        assertTrue("hao" in back.syllables)
    }

    @Test
    fun theKeyboardSendsASyllableAsACharacterOfThePrivateUseArea() {
        val keyboard = Keyboard(session())
        "64426".forEach { keyboard.onEvent(EngineEvent.CHAR, it.code) }
        val s = keyboard.onEvent(EngineEvent.CHAR, Keyboard.SYLLABLES.first.code + 1)
        assertEquals("米糕", s.candidates[0])
        // 0 is no key on the nine keys: it ends the input as typed
        val zero = keyboard.onEvent(EngineEvent.CHAR, '0'.code)
        assertEquals("米糕", zero.commit)
    }

    @Test
    fun aLetterTakenIsAnInitial() {
        val session = session()
        val s = session.type("64426")
        // m for the 6 alone: 米 its likeliest character, the rest read on
        val m = session.apply(Action.Syllable(s.syllables.indexOf("m")))
        assertTrue(m.preedit.startsWith("m'"))
        assertTrue(m.candidates[0].startsWith("米"))
    }

    @Test
    fun oneSeparatesWhileTypingAndIsTheAppsOtherwise() {
        val keyboard = Keyboard(session())
        assertEquals(false, keyboard.onEvent(EngineEvent.CHAR, '1'.code).handled)
        "64".forEach { keyboard.onEvent(EngineEvent.CHAR, it.code) }
        val separated = keyboard.onEvent(EngineEvent.CHAR, '1'.code)
        assertEquals(true, separated.handled)
        "42".forEach { keyboard.onEvent(EngineEvent.CHAR, it.code) }
        assertEquals("ni'hao", keyboard.onEvent(EngineEvent.CHAR, '6'.code).preedit)
    }

    @Test
    fun enterCommitsThePinyinShownNotTheDigits() {
        val session = session()
        session.type("64426")
        assertEquals("nihao", session.apply(Action.CommitRaw).commit)
        session.type("64426")
        session.apply(Action.Syllable(1))
        assertEquals("migao", session.apply(Action.CommitRaw).commit)
        // what no reading took is kept: digits, as typed
        session.type("64")
        session.apply(Action.Syllable(0))
        val shown = session.type("9999").preedit
        val commit = session.apply(Action.CommitRaw).commit
        assertEquals(shown.filter { it != ' ' && it != '\'' }, commit)
        assertTrue(commit.startsWith("ni") && commit.length == 6)
    }

    @Test
    fun startingOverWithNothingTypedIsNotTheApps() {
        val keyboard = Keyboard(session())
        assertEquals(true, keyboard.onEvent(EngineEvent.ESCAPE, 0).handled)
        // nor is a syllable for a session without the nine keys
        val full = Keyboard(PinyinSession(data, org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter(), prediction = false))
        assertEquals(false, full.onEvent(EngineEvent.CHAR, Keyboard.SYLLABLES.first.code).handled)
        assertEquals(false, full.onEvent(EngineEvent.ESCAPE, 0).handled)
    }

    @Test
    fun aSyllableTakenAfterASeparatorTypedKeepsItAndDigitsTypedAfterAreDeletedFirst() {
        val session = session()
        session.type("641")
        session.apply(Action.Syllable(0))
        session.type("426")
        repeat(2) { session.apply(Backspace) }
        assertEquals("ni", session.apply(Backspace).preedit)
        // all typed after it gone: backspace gives the syllable back, the separator typed stays
        val back = session.apply(Backspace)
        assertEquals(listOf("ni", "mi", "o", "m", "n"), back.syllables)
        assertEquals("ni", back.preedit)
    }

}
