/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uat/03-t9.md: each case from B0-九键. Its English (中/英) is English fed the nine keys' own
 * characters ([Keyboard.nineKey], [Keyboard.nineLetter]), as T9Keyboard sends them.
 */
class NineKeysUatTest {

    private fun EngineReplay.nine(digits: String) {
        digits.forEach { key(EngineReplay.symOf(Keyboard.nineKey(it, upper = false))) }
        pause()
    }

    private fun EngineReplay.pinned(letters: String) {
        letters.forEach { key(EngineReplay.symOf(Keyboard.nineLetter(it))) }
        pause()
    }

    // the first three pages, as many as the bar shows by default each
    private val EngineReplay.threePages get() = candidates.take(3 * EngineSettings.DEFAULT_PAGE_SIZE)

    @Test
    @Uat("T9-04")
    fun backspaceAfterASyllableGivesTheDigitsBack() = with(EngineReplay(im = Engines.T9)) {
        type("64")
        val before = candidates
        syllable("mi")
        backspace()
        assertTrue("column ${panel.syllables}", "ni" in panel.syllables && "mi" in panel.syllables)
        assertEquals(before.take(3), candidates.take(3))
    }

    @Test
    @Uat("T9-05")
    fun oneSeparatesSyllables() = with(EngineReplay(im = Engines.T9)) {
        type("94'26")
        assertTrue("「$preedit」", "'" in preedit)
        expectAmong("西安")
    }

    @Test
    @Uat("T9-12")
    fun keysThatMakeASyllableAreRead() = with(EngineReplay(im = Engines.T9)) {
        type("94")
        assertTrue("94 「$preedit」: $threePages", "中国" !in threePages)
        backspace(2)
        type("94664486")
        expectFirst("中国")
        backspace(8)
        type("25")
        assertTrue("25 「$preedit」: $threePages", "北京" in threePages)
    }

    @Test
    @Uat("T9-20")
    fun emojiOnTheNineKeys() = with(EngineReplay(im = Engines.T9)) {
        type("4242")
        expectFirst("哈哈")
        assertTrue("😃 at ${candidates.indexOf("😃")}: ${candidates.take(6)}", candidates.indexOf("😃") >= 2)
        pick("😃")
        expectText("😃")
    }

    @Test
    @Uat("T9-24")
    fun twoQuickPressesAreTwoKeys() = with(EngineReplay(im = Engines.T9)) {
        type("44")
        assertTrue("「$preedit」", preedit.replace("'", "").replace(" ", "") != "h")
        assertTrue("44: ${candidates.take(8)}", listOf("哈哈", "广告", "个个", "好好").any { it in candidates })
    }

    @Test
    @Uat("T9-25")
    fun aCharacterErasedIsNoWordBefore() = with(EngineReplay(im = Engines.T9)) {
        type("64")
        pick("你")
        backspace()
        expectText("")
        type("7474")
        expectFirst("日期")
    }

    @Test
    @Uat("T9-32")
    fun oneInAnEnglishWordIsAnApostrophe() = with(EngineReplay(im = Engines.ENGLISH)) {
        nine("366")
        type("'")
        nine("8")
        space()
        expectText("don't ")
    }

    @Test
    @Uat("T9-40")
    fun lettersPinnedSpellAWordNotInTheDictionaryAndItIsKept() = with(EngineReplay(im = Engines.ENGLISH)) {
        pinned("zorbx")
        assertEquals("zorbx", text + preedit)
        space()
        expectText("zorbx ")
        focus("")
        nine("96729")
        assertTrue("96729: ${candidates.take(8)}", "zorbx" in candidates.take(8))
        focus("")
        pinned("hel")
        space()
        expectText("hel ")
    }

    @Test
    @Uat("T9-46")
    fun switchingWhileTypingCommitsWhatIsTyped() {
        with(EngineReplay(im = Engines.T9)) {
            type("64")
            ime(Engines.ENGLISH)
            expectText("ni")
            expectPreedit("")
        }
        with(EngineReplay(im = Engines.ENGLISH)) {
            nine("4663")
            val first = candidates.first()
            ime(Engines.T9)
            expectText(first)
            expectPreedit("")
        }
    }

    // U-20 (D32 B) on the nine keys: their full digits lift a word under the digits of a part (they read no 简拼)
    @Test
    @Uat("T9-56b")
    fun aWordTypedTwiceInFullDigitsGoesThirdUnderAPart() = with(EngineReplay(im = Engines.T9)) {
        fun shown(keys: String, pick: String?): List<String> {
            focus("他")
            type(keys)
            return candidates.also { if (pick != null) pick(pick) else backspace(keys.length) }
        }
        assertTrue("fresh", "奉化" !in shown("33644", null).take(5))
        repeat(2) { shown("3364482", "奉化") }
        val part = shown("33644", null)
        assertTrue("33644 $part", "奉化" in part.take(3))
        assertTrue("34 reads no 奉化", "奉化" !in shown("34", null))
    }

    // [digit] pressed [times] fast with 连按 on, as the keyboard sends it: the digit, then each press again its next letter
    private fun EngineReplay.press(digit: Char, times: Int) {
        val letters = "abc,def,ghi,jkl,mno,pqrs,tuv,wxyz".split(',')[digit - '2']
        type(digit.toString())
        for (n in 1 until times) key(EngineReplay.symOf(Keyboard.letterKey(letters[n % letters.length], again = true)))
        pause()
    }

    private fun EngineReplay.fengh() {
        press('3', 3); press('3', 2); press('6', 2); press('4', 1); press('4', 2)
    }

    private fun EngineReplay.fenghua() {
        fengh(); press('8', 2); press('2', 1)
    }

    private fun EngineReplay.shownAfterTa(input: EngineReplay.() -> Unit, pick: String?): List<String> {
        focus("他")
        input()
        return candidates.also { if (pick != null) pick(pick) else backspace(preedit.length + 8) }
    }

    // 连按 is the nine keys' (Kinds.keyboard): typed in full by it, a part of the digits lifts the word
    @Test
    @Uat("T9-56c")
    fun multiTapInFullLiftsAWordUnderPartDigits() = with(EngineReplay(im = Engines.T9)) {
        assertTrue("fresh", "奉化" !in shownAfterTa({ type("33644") }, null).take(5))
        repeat(2) { shownAfterTa({ fenghua() }, "奉化") }
        val part = shownAfterTa({ type("33644") }, null)
        assertTrue("33644 $part", "奉化" in part.take(3))
    }

    // and the other way: the digits typed in full, 连按's part lifts it
    @Test
    @Uat("T9-56d")
    fun digitsInFullLiftAWordUnderPartMultiTap() = with(EngineReplay(im = Engines.T9)) {
        assertTrue("fresh", "奉化" !in shownAfterTa({ fengh() }, null).take(5))
        repeat(2) { shownAfterTa({ type("3364482") }, "奉化") }
        val part = shownAfterTa({ fengh() }, null)
        assertTrue("fengh $part", "奉化" in part.take(3))
    }
}
