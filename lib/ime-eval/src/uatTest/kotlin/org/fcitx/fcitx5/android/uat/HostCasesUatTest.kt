/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uat/ cases moved off the device whole (devflow's C class, held up by the native host until it
 * became Kotlin): each case's 操作 on [HostReplay], its 预期 asserted as the .md has it.
 */
class HostCasesUatTest {

    // ------------------------------------------------------------------ 02 keyboard

    @Test
    @Uat("KB-14")
    fun commaAndFullStopAfterChineseAreChinese() = with(HostReplay()) {
        type("nihao")
        space()
        type(",")
        type(".")
        expectText("你好，。")
    }

    @Test
    @Uat("KB-15")
    fun aCommaAfterADigitIsChinese() = with(HostReplay()) {
        type("1")
        type(",")
        expectText("1，")
    }

    @Test
    @Uat("KB-25")
    fun anAddressFieldTypesDigitsAndMarksAsTheyAre() = with(HostReplay()) {
        focus("", EngineReplay.Field.URL)
        type("2")
        type("/")
        expectText("2/")
    }

    @Test
    @Uat("KB-33")
    fun aGlideGoesInWithItsSpaceItsWordChangedOrErasedWholeAMarkTakesTheSpace() = with(HostReplay(im = InputMethodCatalog.ENGLISH)) {
        // ok typed, then the glide: ok in first, as a space would put it
        type("ok")
        glide("hello")
        expectText("ok hello ")
        assertTrue("other words offered: $page", page.size > 1 && page.any { it != "hello" })
        glide("world")
        expectText("ok hello world ")
        val other = page.firstOrNull { it != "world" } ?: error("nothing but world offered: $page")
        pickShown(page.indexOf(other))
        val changed = "ok hello $other "
        expectText(changed)
        glide("hello")
        backspace()
        expectText(changed)
        glide("hello")
        type(",")
        expectText("${changed}hello, ")
    }

    // ------------------------------------------------------------------ 03 九键

    @Test
    @Uat("T9-15")
    fun nineKeysAKeyMissedIsTypedWhereTheRowIsTapped() = with(HostReplay(im = InputMethodCatalog.PINYIN)) {
        pinyinNine("6426")
        tapRow(1)
        assertEquals("|after the first letter in 「$row」", '|', row[1])
        pinyinNine("4")
        expectPreedit("ni hao")
        expectFirst("你好")
        space()
        expectText("你好")
    }

    // ------------------------------------------------------------------ 06 pinyin

    @Test
    @Uat("PY-39")
    fun aLetterMissedIsTypedWhereTheRowIsTapped() = with(HostReplay()) {
        type("nishiwozuixihunde")
        tapRowAfter("hu")
        assertTrue("the cursor between u and n in 「$row」", "hu|n" in row)
        type("a")
        assertTrue("xi huan de in 「$preedit」", preedit.endsWith("xi huan de"))
        expectFirst("你是我最喜欢的")
        space()
        expectText("你是我最喜欢的")
    }

    @Test
    @Uat("PY-40")
    fun backspaceInTheMiddleOfThePinyin() = with(HostReplay()) {
        type("nishiwozuixihuande")
        tapRowAfter("shi")
        backspace(3)
        expectPreedit("ni wo zui xi huan de")
        assertTrue("the cursor after ni in 「$row」", row.startsWith("ni|"))
        space()
        expectText("你我最喜欢的")
    }

    @Test
    @Uat("PY-42")
    fun aCharacterTakenHasNoCursorBeforeItAndBackspaceGivesItBack() = with(HostReplay()) {
        type("nihao")
        pick("你")
        assertTrue("你hao in 「$row」", row.replace(" ", "").startsWith("你hao"))
        tapRow(0)
        assertTrue("the cursor after 你 in 「$row」", row.startsWith("你|"))
        backspace()
        expectPreedit("n hao")
    }

    @Test
    @Uat("PY-43")
    fun aStrokeMissedIsTypedWhereTheRowIsTapped() = with(HostReplay()) {
        type("uhp")
        tapRowAfter("一")
        assertTrue("the cursor after 一 in 「$row」", "一|" in row)
        type("s")
        assertEquals("u一丨丿", row.replace("|", ""))
        assertTrue("才 本 村 among ${candidates.take(SHOWN)}", candidates.containsAll(listOf("才", "本", "村")))
    }

    // the whole of PY-75 as uat.py runs it (page="chat"): the 前提's 带来 typed in the chat box and
    // sent with the first 他; 他 and 点了 sent from there twice are learned with nothing before them,
    // so in the text field (another page of the same app, empty) 他 dl still has 带来 first
    @Test
    @Uat("PY-75")
    fun wordsSentFromAChatBoxAreLearnedWithNothingBefore() = with(HostReplay(firstField = "chat")) {
        repeat(3) {
            type("dl")
            expectFirst("带来")
            space()
            type(".")
        }
        repeat(2) {
            type("ta")
            pick("他")
            send()
            type("dl")
            pick("点了")
            send()
        }
        focus("", name = "text", app = uid)
        type("ta")
        pick("他")
        type("dl")
        expectFirst("带来")
    }

    // ------------------------------------------------------------------ 07 双拼

    @Test
    @Uat("SP-03")
    fun microsoftsSemicolonIsAFinal() = with(HostReplay(engines = settings(EngineSettings(shuangpin = "MS")), im = InputMethodCatalog.SHUANGPIN)) {
        type("x;")
        assertTrue("性 星 among ${candidates.take(SHOWN)}", candidates.containsAll(listOf("性", "星")))
        expectText("")
        backspace(2)
        type(";")
        expectText("；")
        backspace()
        type("ni")
        val first = candidates.first()
        type(";")
        expectText("$first；")
    }

    @Test
    @Uat("HW-02")
    fun aCharacterWrittenIsComposingInTheFieldWithItsReadings() = with(HostReplay(im = InputMethodCatalog.HANDWRITING)) {
        write("人", "入", "八")
        // composing (underlined), not committed
        assertEquals("人", clientPreedit)
        assertEquals(0 to 1, editor.composingStart to editor.composingEnd)
        assertTrue(commits.isEmpty())
        assertTrue("人 among $page", "人" in page)
    }

    @Test
    @Uat("HW-09")
    fun aCharacterNotCommittedStaysInTheFieldWhenTheKeyboardGoes() = with(HostReplay(im = InputMethodCatalog.HANDWRITING)) {
        write("人", "入", "八")
        keyboardHidden()
        expectText("人")
        assertEquals(-1 to -1, editor.composingStart to editor.composingEnd)
    }

    // where the cursor goes after the commit is EditingSession's (ComposingTapUatTest, :lib:ime-core)
    @Test
    @Uat("HW-25")
    fun aTapOnCharactersNotInPutsThemInAsTheyAre() = with(HostReplay(im = InputMethodCatalog.HANDWRITING)) {
        write("人", "入", "八")
        write("大", "太", "犬")
        assertEquals("人大", clientPreedit)
        // a tap before 人: the editor's cursor there, which the service passes on as the preedit's (MovePreeditCursor)
        editor.setSelection(0, 0)
        core.moveCursor(0)
        pause()
        expectText("人大")
        assertEquals(listOf("人大"), commits)
        assertEquals(-1 to -1, editor.composingStart to editor.composingEnd)
        assertEquals("", clientPreedit)
        assertTrue("candidates left: $page", page.isEmpty())
        // 十 a sentence of its own after 人大
        assertEquals(2, editor.selectionStart)
        write("十", "千", "干")
        expectText("人大十")
        assertEquals(2 to 3, editor.composingStart to editor.composingEnd)
    }

    // ------------------------------------------------------------------ 10 tables

    @Test
    @Uat("TBL-24")
    fun aCodeMistypedInTheMiddleIsFixedWhereTheRowIsTapped() = with(HostReplay(im = InputMethodCatalog.WUBI)) {
        type("wvi")
        assertTrue("candidates for wvi", candidates.isNotEmpty())
        expectText("")
        tapRowAfter("v")
        assertTrue("the cursor after v in 「$row」", "wv|i" in row)
        backspace()
        type("q")
        assertTrue("wq|i in 「$row」", "wq|i" in row)
        expectText("")
        tapRowEnd()
        type("y")
        assertTrue("wqiy in 「$row」", row.endsWith("wqiy"))
        expectFirst("你")
        space()
        expectText("你")
    }

    // ------------------------------------------------------------------ 11 quick input, Unicode

    @Test
    @Uat("QU-02")
    fun aMoodWordGivesKaomoji() = with(HostReplay()) {
        quick()
        type("yawn")
        val first = page.first()
        // the faces quickphrase.d/emoji.mb lists under yawn, in its order
        assertEquals("a kaomoji first: $page", listOf("(￣□￣)", "(๑´ڡ`๑)"), page.take(2))
        space()
        expectText(first)
    }

    @Test
    @Uat("QU-03")
    fun anEmojiByItsName() = with(HostReplay()) {
        quick()
        type(":smile:")
        space()
        expectText("😄")
    }

    @Test
    @Uat("QU-04")
    fun aLatexNameGivesItsSymbol() = with(HostReplay()) {
        quick()
        type("\\alpha")
        assertTrue("α among $page", "α" in page)
    }

    @Test
    @Uat("QU-05")
    fun backspacingQuickInputEmptyLeavesIt() = with(HostReplay()) {
        quick()
        type("ya")
        backspace(3)
        assertEquals("", row)
        type("ni")
        expectPreedit("ni")
        expectFirst("你")
    }

    @Test
    @Uat("QU-06")
    fun unicodeByName() = with(HostReplay()) {
        unicode()
        assertTrue("Unicode: on the bar 「$row」", row.startsWith("Unicode"))
        type("degree")
        assertTrue("° among $page", "°" in page)
        pickShown(page.indexOf("°"))
        expectText("°")
    }

    @Test
    @Uat("QU-07")
    fun unicodeByHexCode() = with(HostReplay()) {
        unicode()
        type("U+4F60")
        assertTrue("你 among $page", "你" in page)
    }

    // ------------------------------------------------------------------ 15 privacy

    @Test
    @Uat("PR-04")
    fun leavingAPasswordFieldGoesBackToTheInputMethodBefore() = with(HostReplay(im = InputMethodCatalog.WUBI)) {
        focus("", EngineReplay.Field.PASSWORD)
        assertEquals(InputMethodCatalog.ENGLISH, im)
        focus("", EngineReplay.Field.TEXT)
        assertEquals(InputMethodCatalog.WUBI, im)
        type("q")
        expectFirst("我")
    }

    // ------------------------------------------------------------------ 18 lifecycle

    @Test
    @Uat("LC-04")
    fun anotherFieldTappedWhilePinyinIsTypedLeavesNothing() = with(HostReplay()) {
        type("ni")
        focus("", name = "sentence")
        assertEquals("", left)
        expectText("")
        assertEquals("", row)
        type("nihao")
        space()
        expectText("你好")
    }

    private companion object {
        const val SHOWN = 12
    }
}
