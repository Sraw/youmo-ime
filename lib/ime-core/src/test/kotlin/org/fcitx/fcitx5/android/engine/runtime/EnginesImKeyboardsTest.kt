/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboards I1 makes pinyin's and 双拼's (design §3): pinyin's nine keys a keyed session of
 * pinyin's pair, pinyin's 18 keys and 双拼's 14 keys new ones, each sending codes of its own.
 */
class EnginesImKeyboardsTest : EnginesFixture() {

    private val root = MemoryFiles()

    private fun engines(im: String) = Engines(::load, root, warmUps = inPlace).also {
        it.onEvent(im, EngineEvent.RESET, 0)
        it.context(im, "")
    }

    private fun Engines.typeCodes(im: String, codes: List<Int>) = codes.map { onEvent(im, EngineEvent.CHAR, it) }.last()

    private fun Engines.texts(im: String) = candidates(im, 0, 10).map { it.text }

    private fun pinyin18(letters: String) = letters.map { Keyboard.pinyin18Key(NineKeyLetters.G18.key(it)) }

    private fun shuangpin14(keys: String) = keys.map { Keyboard.shuangpin14Key(NineKeyLetters.G14.key(it)) }

    private fun nine(digits: String) = digits.map(Keyboard::pinyinNineKey)

    @Test
    fun eachKeyboardsCodesAreItsInputMethodsAlone() {
        val scheme = EngineSettings().scheme
        for (code in listOf(pinyin18("n")[0], nine("6")[0], Keyboard.pinyinNineKey('1'))) {
            assertTrue(InputMethodCatalog.readsAsKey(PINYIN, scheme, code))
            assertFalse(InputMethodCatalog.readsAsKey(SHUANGPIN, scheme, code))
            assertTrue(InputMethodCatalog.isCode(code))
        }
        val sp14 = shuangpin14("n")[0]
        assertTrue(InputMethodCatalog.readsAsKey(SHUANGPIN, scheme, sp14))
        assertFalse(InputMethodCatalog.readsAsKey(PINYIN, scheme, sp14))
        assertTrue(InputMethodCatalog.isCode(sp14))
        // a digit as it is is no code: the app's, or a pick
        assertFalse(InputMethodCatalog.isCode('6'.code))
        assertFalse(InputMethodCatalog.readsAsKey(PINYIN, scheme, '6'.code))
        // none of them reaches a table
        assertFalse(InputMethodCatalog.readsCode(InputMethodCatalog.WUBI, sp14))
    }

    // the screen's 0 while typing (its code): the first candidate, then 0, as 九键拼音's; never the tenth picked
    @Test
    fun theNineKeysZeroWhileTypingCommitsTheFirstThenZero() {
        val engines = engines(PINYIN)
        engines.typeCodes(PINYIN, nine("64"))
        val first = engines.texts(PINYIN)[0]
        val out = engines.onEvent(PINYIN, EngineEvent.CHAR, Keyboard.pinyinNineKey('0'))
        assertTrue(out.handled)
        assertEquals(first + "0", out.commit)
        assertEquals("", out.preedit)
        // with nothing typed (a keyboard behind the engine) the code still types its digit
        assertEquals("0", engines.onEvent(PINYIN, EngineEvent.CHAR, Keyboard.pinyinNineKey('0')).commit)
    }

    @Test
    fun pinyinsNineKeysTypeInPinyinAndHandBackToFullPinyin() {
        val engines = engines(PINYIN)
        val shown = engines.typeCodes(PINYIN, nine("64426"))
        assertTrue(shown.candidates.toString(), "你好" in shown.candidates.take(3))
        assertTrue(shown.syllables.toString(), "ni" in shown.syllables)
        // 1 while typing: the separator, read by the nine keys' session
        assertTrue(engines.onEvent(PINYIN, EngineEvent.CHAR, Keyboard.pinyinNineKey('1')).handled)
        // a digit as it is picks, as on the 14 keys
        engines.onEvent(PINYIN, EngineEvent.RESET, 0)
        engines.typeCodes(PINYIN, nine("64"))
        val first = engines.texts(PINYIN)[0]
        assertEquals(first, engines.onEvent(PINYIN, EngineEvent.CHAR, '1'.code).commit)
        // a hardware letter: full pinyin's
        engines.onEvent(PINYIN, EngineEvent.RESET, 0)
        engines.typeCodes(PINYIN, nine("64"))
        val n = engines.onEvent(PINYIN, EngineEvent.CHAR, 'n'.code)
        assertTrue(n.commit, n.commit.isNotEmpty())
        assertEquals("你", engines.type(PINYIN, "i").candidates[0])
        engines.close()
    }

    @Test
    fun pinyinsEighteenKeysTypeAndLearnUnderTheirOwnKind() {
        val engines = engines(PINYIN)
        val shown = engines.typeCodes(PINYIN, pinyin18("nihao"))
        assertTrue(shown.candidates.toString(), "你好" in shown.candidates.take(3))
        assertTrue(shown.syllables.toString(), "ni" in shown.syllables)
        val ni = pinyin18("ni")
        repeat(2) {
            engines.onEvent(PINYIN, EngineEvent.RESET, 0)
            engines.context(PINYIN, "")
            engines.typeCodes(PINYIN, ni)
            engines.onEvent(PINYIN, EngineEvent.PICK, engines.texts(PINYIN).indexOf("拟"))
        }
        engines.onEvent(PINYIN, EngineEvent.RESET, 0)
        engines.context(PINYIN, "")
        engines.typeCodes(PINYIN, ni)
        val at = engines.texts(PINYIN).indexOf("拟")
        val menu = engines.learning(PINYIN, LearningOp.Menu(at, "拟")) as LearningResult.Menu
        assertEquals(Kinds.keys(NineKeyLetters.G18.tag()), menu.why!!.code!!.kind)
        // full pinyin learned nothing of it
        engines.onEvent(PINYIN, EngineEvent.RESET, 0)
        engines.context(PINYIN, "")
        assertEquals(0, engines.type(PINYIN, "ni").candidates.indexOf("你"))
        engines.close()
    }

    @Test
    fun shuangpinsFourteenKeysTypeAndLearnUnderTheirOwnKind() {
        // 自然码 (EngineSettings' own default; the app's is 小鹤): nihk is ni hao
        val engines = engines(SHUANGPIN)
        val shown = engines.typeCodes(SHUANGPIN, shuangpin14("nihk"))
        assertTrue(shown.candidates.toString(), "你好" in shown.candidates.take(3))
        val ni = shuangpin14("ni")
        repeat(2) {
            engines.onEvent(SHUANGPIN, EngineEvent.RESET, 0)
            engines.context(SHUANGPIN, "")
            engines.typeCodes(SHUANGPIN, ni)
            engines.onEvent(SHUANGPIN, EngineEvent.PICK, engines.texts(SHUANGPIN).indexOf("拟"))
        }
        engines.onEvent(SHUANGPIN, EngineEvent.RESET, 0)
        engines.context(SHUANGPIN, "")
        engines.typeCodes(SHUANGPIN, ni)
        val at = engines.texts(SHUANGPIN).indexOf("拟")
        val menu = engines.learning(SHUANGPIN, LearningOp.Menu(at, "拟")) as LearningResult.Menu
        assertEquals(Kinds.shuangpinKeys("Ziranma", NineKeyLetters.G14.tag()), menu.why!!.code!!.kind)
        engines.close()
    }

    @Test
    fun aGlideGoesToTheNineKeysOnlyWhereItsLettersShareKeys() {
        assertTrue(GlideLayout.nineKeys().sharesKeys)
        assertFalse(GlideLayout.QWERTY.sharesKeys)
        // a split keyboard: letters on two keys each, none sharing one
        val split = GlideLayout(GlideLayout.rows().let { qwerty -> "abcdefghijklmnopqrstuvwxyz".map { it to qwerty.centre(it) } } + listOf('g' to (12f to 2.1f)))
        assertFalse(split.sharesKeys)
        // which session, asked before any is made
        val pair = KeyboardPair.pinyin { error("no session made to tell") }
        assertEquals(InputMethodCatalog.T9, pair.glidedBy(GlideLayout.nineKeys()))
        assertNull(pair.glidedBy(GlideLayout.QWERTY))
        assertNull(pair.glidedBy(null))
        val sp = KeyboardPair.shuangpin({ error("no session made to tell") }) { EngineSettings().scheme }
        assertNull(sp.glidedBy(GlideLayout.nineKeys()))
    }

    @Test
    fun theNewCodesStandForTheirInternalOnes() {
        assertEquals('6', Keyboard.keyedChar(Keyboard.pinyinNineKey('6')))
        assertEquals(NineKeyLetters.G18.key('n'), Keyboard.keyedChar(pinyin18("n")[0]))
        assertEquals(NineKeyLetters.G14.key('n'), Keyboard.keyedChar(shuangpin14("n")[0]))
        assertNull(Keyboard.keyedChar('n'.code))
        // a temporary mode reads them as the letters they stand for
        assertEquals('6', Keyboard.typedLetter(Keyboard.pinyinNineKey('6')))
        assertEquals(NineKeyLetters.G18.letters(NineKeyLetters.G18.key('n')).first(), Keyboard.typedLetter(pinyin18("n")[0]))
        assertEquals(NineKeyLetters.G14.letters(NineKeyLetters.G14.key('n')).first(), Keyboard.typedLetter(shuangpin14("n")[0]))
    }
}
