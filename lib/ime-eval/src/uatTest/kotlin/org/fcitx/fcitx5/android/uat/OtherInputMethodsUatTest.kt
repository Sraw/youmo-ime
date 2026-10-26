/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.host.Engines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uat/03-t9.md, 07-shuangpin.md, 08-english.md and 10-tables.md: the cases of the other input
 * methods whose 预期 is what the engine types, each from its B0 (B0-九键, B0-双拼, B0-英文, B0-五笔…).
 */
class OtherInputMethodsUatTest {

    @Test
    @Uat("T9-02")
    fun nineKeysDigitsGiveASentence() = with(EngineReplay(im = Engines.T9)) {
        type("64426")
        expectFirst("你好")
        space()
        expectText("你好")
    }

    @Test
    @Uat("T9-03")
    fun nineKeysTheSyllableColumnSettlesASyllable() = with(EngineReplay(im = Engines.T9)) {
        type("64")
        val column = panel.syllables
        assertTrue("column $column", listOf("ni", "mi", "o").all { it in column })
        syllable("mi")
        expectPreedit("mi")
        assertTrue("candidates ${candidates.take(8)}", "米" in candidates || "密" in candidates)
        expectNotAmong("你")
    }

    @Test
    @Uat("SP-01")
    fun shuangpinZiranmaByDefault() = with(EngineReplay(im = Engines.SHUANGPIN)) {
        type("nihk")
        space()
        expectText("你好")
    }

    @Test
    @Uat("EN-01")
    fun englishTheWordTypedIsFirst() = with(EngineReplay(im = Engines.ENGLISH)) {
        type("hello")
        expectFirst("hello")
        assertTrue("nothing beside hello: $candidates", candidates.size > 1)
    }

    @Test
    @Uat("EN-02")
    fun englishSpaceCorrectsAMisspellingASpaceAfterIt() = with(EngineReplay(im = Engines.ENGLISH)) {
        type("teh")
        expectStart("the", "teh")
        space()
        expectText("the ")
    }

    @Test
    @Uat("EN-04")
    fun englishBackspaceRightAfterACorrectionUndoesItForGoodUntilRestored() = with(EngineReplay(im = Engines.ENGLISH)) {
        type("teh")
        space()
        expectText("the ")
        backspace()
        // English composes in the field: the word given back is typed again, shown in the field
        assertEquals("teh", text + preedit)
        space()
        type("teh")
        space()
        assertTrue("the field: ${text + preedit}", (text + preedit).endsWith("teh "))
        // the English words page lists it never corrected; 恢复自动改 there
        val word = engines.englishWords().single { it.text == "teh" }
        assertTrue("teh not marked never corrected", word.neverCorrected)
        engines.restoreEnglishCorrection(listOf("teh"))
        focus("")
        type("teh")
        space()
        expectText("the ")
    }

    @Test
    @Uat("TBL-01")
    fun wubiFirstLevelShortCodes() = with(EngineReplay(im = Engines.WUBI)) {
        type("q")
        space()
        type("w")
        space()
        expectText("我人")
    }

    @Test
    @Uat("TBL-02")
    fun wubiFourCodesWithOneCharacterCommitIt() = with(EngineReplay(im = Engines.WUBI)) {
        type("ftjb")
        expectText("都")
        expectPreedit("")
    }

    @Test
    @Uat("TBL-10")
    fun wubiAWord() = with(EngineReplay(im = Engines.WUBI)) {
        type("khlg")
        // a word's four codes are not committed at the fourth (other words share them): first, the code shown
        expectText("")
        expectFirst("中国")
    }

    @Test
    @Uat("TBL-19")
    fun cangjie() = with(EngineReplay(im = "engine-cangjie")) {
        type("onf")
        space()
        expectText("你")
    }

    @Test
    @Uat("TBL-22")
    fun wubiAndPinyinMixed() = with(EngineReplay(im = "engine-wubipinyin")) {
        type("wqiy")
        // not committed at the fourth code (pinyin's readings share it): offered first
        expectText("")
        expectFirst("你")
        backspace(4)
        focus("")
        type("hao")
        assertTrue("hao: ${candidates.take(10)}", ("好" in candidates || "号" in candidates) && "虚" in candidates)
    }
}
