/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.fcitx.fcitx5.android.engine.pinyin.nineSpCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 双拼 on the 18 keys and the nine keys through [Engines] (design §1.4): a session each, the hardware keyboard full 双拼's. */
class EnginesShuangpinKeysTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    private fun engines() = Engines(::load, folder.root, warmUps = inPlace).also {
        it.onEvent(Engines.SHUANGPIN, EngineEvent.RESET, 0)
        it.context(Engines.SHUANGPIN, "")
    }

    private fun Engines.texts() = candidates(Engines.SHUANGPIN, 0, 10).map { it.text }

    // 自然码's keys (the default scheme) as [letters]' codes: nihk is ni hao
    private fun codes(keys: String, letters: NineKeyLetters) = keys.map(letters::key).joinToString("")

    @Test
    fun theCodesOfBothKeyboardsAreShuangpinsAndNoOthers() {
        val scheme = EngineSettings().scheme
        assertTrue(Engines.readsAsKey(Engines.SHUANGPIN, scheme, NineKeyLetters.G18.key('n').code))
        assertTrue(Engines.readsAsKey(Engines.SHUANGPIN, scheme, nineSpCode('5').code))
        assertFalse(Engines.readsAsKey(Engines.SHUANGPIN, scheme, g14Code('q').code))
        assertFalse(Engines.readsAsKey(Engines.PINYIN, scheme, NineKeyLetters.G18.key('n').code))
    }

    @Test
    fun theEighteenKeysReadEachGroupAndOfferTheSyllables() {
        val engines = engines()
        val nihao = codes("nihk", NineKeyLetters.G18)
        val shown = engines.type(Engines.SHUANGPIN, nihao)
        assertTrue(shown.candidates.toString(), "你好" in shown.candidates.take(3))
        assertEquals(shown.candidates, engines.texts().take(shown.candidates.size))
        // a syllable bar, the nine keys' way
        assertTrue(shown.syllables.toString(), "ni" in shown.syllables)
        engines.close()
    }

    @Test
    fun aPickOnTheEighteenKeysIsLearnedUnderSpk() {
        val engines = engines()
        val ni = codes("ni", NineKeyLetters.G18)
        repeat(2) {
            engines.onEvent(Engines.SHUANGPIN, EngineEvent.RESET, 0)
            engines.context(Engines.SHUANGPIN, "")
            engines.type(Engines.SHUANGPIN, ni)
            engines.onEvent(Engines.SHUANGPIN, EngineEvent.PICK, engines.texts().indexOf("拟"))
        }
        engines.onEvent(Engines.SHUANGPIN, EngineEvent.RESET, 0)
        engines.context(Engines.SHUANGPIN, "")
        engines.type(Engines.SHUANGPIN, ni)
        val at = engines.texts().indexOf("拟")
        val menu = engines.learning(Engines.SHUANGPIN, LearningOp.Menu(at, "拟")) as LearningResult.Menu
        assertEquals(Kinds.shuangpinKeys("Ziranma", NineKeyLetters.G18.tag()), menu.why!!.code!!.kind)
        // full 双拼 learned nothing of it
        engines.onEvent(Engines.SHUANGPIN, EngineEvent.RESET, 0)
        engines.context(Engines.SHUANGPIN, "")
        assertEquals(0, engines.type(Engines.SHUANGPIN, "ni").candidates.indexOf("你"))
        engines.close()
    }

    @Test
    fun aHardwareLetterOrAnotherKeyboardsCodeTurnsToItsSession() {
        val engines = engines()
        engines.type(Engines.SHUANGPIN, codes("ni", NineKeyLetters.G18))
        // the hardware keyboard's: what the 18 keys typed kept as typed, no code reaching the field
        val n = engines.onEvent(Engines.SHUANGPIN, EngineEvent.CHAR, 'n'.code)
        assertTrue(n.commit, n.commit.isNotEmpty() && n.commit.none { NineKeyLetters.ofCode(it.code) != null })
        assertTrue(engines.type(Engines.SHUANGPIN, "i").candidates.contains("你"))
        // the nine keys' after: full 双拼's typing kept, the nine keys' session made
        val nine = engines.onEvent(Engines.SHUANGPIN, EngineEvent.CHAR, NineKeyLetters.NINE_SP.key('n').code)
        assertEquals("ni", nine.commit)
        val nihao = engines.type(Engines.SHUANGPIN, codes("ihk", NineKeyLetters.NINE_SP))
        assertTrue(nihao.candidates.toString(), "你好" in engines.texts())
        engines.close()
    }

    // design §1.3, review N5: 微软's ; is a letter of full 双拼, the hardware keyboard's while the 18 keys type
    @Test
    fun aSemicolonTheSchemeTypesIsFullShuangpins() {
        val engines = engines()
        engines.settings = EngineSettings(shuangpin = "MS")
        engines.onEvent(Engines.SHUANGPIN, EngineEvent.RESET, 0)
        engines.type(Engines.SHUANGPIN, codes("ni", NineKeyLetters.G18))
        // d; is ding: the 18 keys' ni kept as typed, then d to full, ; after it full's too (KeyboardPairTest has ; alone)
        val d = engines.onEvent(Engines.SHUANGPIN, EngineEvent.CHAR, 'd'.code)
        assertTrue(d.toString(), d.commit.isNotEmpty())
        val ding = engines.onEvent(Engines.SHUANGPIN, EngineEvent.HARD_CHAR, ';'.code)
        // a key of full 双拼's, not the mark it is with nothing before it
        assertEquals("", ding.commit)
        assertEquals("d;", ding.preedit.replace(" ", ""))
        engines.close()
    }

    // review S1: the screen's ; (a long press of L;, the symbol page) is a mark after what the 18 keys type: 你；, never ni；
    @Test
    fun theScreensSemicolonIsAMarkAfterTheEighteenKeysTypingInMicrosoft() {
        val engines = engines()
        engines.settings = EngineSettings(shuangpin = "MS")
        engines.onEvent(Engines.SHUANGPIN, EngineEvent.RESET, 0)
        engines.context(Engines.SHUANGPIN, "")
        val ni = engines.type(Engines.SHUANGPIN, codes("ni", NineKeyLetters.G18))
        val semicolon = engines.onEvent(Engines.SHUANGPIN, EngineEvent.CHAR, ';'.code)
        assertTrue(semicolon.toString(), semicolon.commit.startsWith(ni.candidates.first()))
        assertFalse(semicolon.commit, semicolon.commit.startsWith("ni"))
        engines.close()
    }

    // review S9: where the scheme types no ; (自然码) a hardware one is the grouped session's mark too
    @Test
    fun aHardwareSemicolonTheSchemeDoesNotTypeStaysWithTheEighteenKeys() {
        val engines = engines()
        val ni = engines.type(Engines.SHUANGPIN, codes("ni", NineKeyLetters.G18))
        val semicolon = engines.onEvent(Engines.SHUANGPIN, EngineEvent.HARD_CHAR, ';'.code)
        assertTrue(semicolon.toString(), semicolon.commit.startsWith(ni.candidates.first()))
        engines.close()
    }
}
