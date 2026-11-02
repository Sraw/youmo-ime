/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.SpelledForm
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.RecordingHabits
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinyin typed in zhuyin (design 051 §3.3): 大千's and 注音九键's sessions on the session's test data. */
class PinyinSessionZhuyinTest {

    private val data = sessionTestData()

    private fun session(
        nine: Boolean = false,
        habits: RecordingHabits? = null,
        toneAllows: ((Int, Int, Int) -> Boolean)? = null,
    ) = PinyinSession(
        data, T9Segmenter(abbreviations = false, letters = if (nine) ZhuyinKeys.NINE else ZhuyinKeys.DACHEN, toneAllows = toneAllows), spell = true, prediction = false,
        learnTo = LearnTo(habits ?: RecordingHabits(), if (nine) Kinds.ZHUYIN_NINE else Kinds.ZHUYIN_DACHEN),
    )

    // 大千's codes as the keyboard sends them, a mark a tone
    private fun dachen(typed: String) = typed.map { c -> ZhuyinKeys.MARKS.indexOf(c).let { if (it >= 0) Keyboard.zhuyinTone(it + 1) else Keyboard.zhuyinKey(c) } }

    private fun nine(typed: String) = typed.map { c -> Keyboard.zhuyinNineKey(ZhuyinKeys.GROUP_SYMBOLS.indexOfFirst { c in it }) }

    private fun Keyboard.type(codes: List<Int>) = codes.map { onEvent(EngineEvent.CHAR, it) }.last()

    @Test
    fun dachenTypesAWordByItsSymbolsShownInZhuyin() {
        val keyboard = Keyboard(session(), nineKeys = true)
        val out = keyboard.type(dachen("ㄋㄧˇㄏㄠˇ"))
        assertEquals("你好", out.candidates[0])
        assertEquals("ㄋㄧˇ ㄏㄠˇ", out.preedit)
    }

    @Test
    fun theNineKeysTypeAWordInFourKeys() {
        val keyboard = Keyboard(session(nine = true), nineKeys = true)
        val out = keyboard.type(nine("ㄋㄧㄏㄠ"))
        assertEquals("你好", out.candidates[0])
        assertEquals("ㄋㄧ ㄏㄠ", out.preedit)
    }

    // design 051 Q3 A: a space gives a syllable with no tone its first, then picks the first candidate
    @Test
    fun dachensSpaceIsTheFirstToneThenThePick() {
        val keyboard = Keyboard(session(), nineKeys = true)
        keyboard.type(dachen("ㄓㄨㄥ"))
        val toned = keyboard.onEvent(EngineEvent.CHAR, ' '.code)
        assertEquals("ㄓㄨㄥˉ", toned.preedit)
        assertEquals("中", toned.candidates[0])
        val picked = keyboard.onEvent(EngineEvent.CHAR, ' '.code)
        assertEquals("中", picked.commit)
    }

    // text not read as a word goes in as the zhuyin shown (注音文), never the codes
    @Test
    fun enterCommitsTheZhuyinShown() {
        val keyboard = Keyboard(session(), nineKeys = true)
        keyboard.type(dachen("ㄅˇ"))
        val out = keyboard.onEvent(EngineEvent.ENTER, 0)
        assertEquals("ㄅˇ", out.commit)
        assertTrue(out.commit.none { it in ZhuyinKeys.BLOCK })
    }

    // design 051 Q4 A: a tone the reading has not puts the word after, never out
    @Test
    fun aToneTheReadingHasNotPutsTheWordAfter() {
        val ni = Syllables.id("ni")
        // 你 is ni3 alone here; 拟 any
        val allows = { cp: Int, syllable: Int, tone: Int -> !(cp == '你'.code && syllable == ni && tone != 3) }
        val plain = session(toneAllows = allows).let { s -> dachen("ㄋㄧ").map { s.apply(Key(Keyboard.keyedChar(it)!!)) }.last() }
        assertEquals("你", plain.candidates[0])
        val third = session(toneAllows = allows).let { s -> dachen("ㄋㄧˇ").map { s.apply(Key(Keyboard.keyedChar(it)!!)) }.last() }
        assertEquals("你", third.candidates[0])
        val second = session(toneAllows = allows).let { s -> dachen("ㄋㄧˊ").map { s.apply(Key(Keyboard.keyedChar(it)!!)) }.last() }
        assertEquals("拟", second.candidates[0])
        assertTrue(second.candidates.toString(), "你" in second.candidates)
    }

    @Test
    fun aPinIsFiledUnderTheZhuyinKind() {
        for (nine in listOf(false, true)) {
            val habits = RecordingHabits()
            val keyboard = Keyboard(session(nine, habits), nineKeys = true)
            keyboard.type(if (nine) nine("ㄋㄧ") else dachen("ㄋㄧ"))
            keyboard.onEvent(EngineEvent.PIN, 0)
            val kind = if (nine) Kinds.ZHUYIN_NINE else Kinds.ZHUYIN_DACHEN
            assertTrue(habits.pins.toString(), habits.pins.single().startsWith("$kind:"))
        }
    }

    // zhuyin's keys spell no pinyin letters: its kinds link no spellings (U-20), their keys filed by the code
    @Test
    fun theZhuyinKindsLinkNoSpellings() {
        assertTrue(Kinds.isZhuyin(Kinds.ZHUYIN_NINE) && Kinds.isZhuyin(Kinds.ZHUYIN_DACHEN))
        assertNull(SpelledForm.of(Kinds.ZHUYIN_DACHEN, "abcd", "ni hao"))
        assertEquals('a', SpelledForm.filedUnder(Kinds.ZHUYIN_NINE, "ab", "ni hao"))
    }
}
