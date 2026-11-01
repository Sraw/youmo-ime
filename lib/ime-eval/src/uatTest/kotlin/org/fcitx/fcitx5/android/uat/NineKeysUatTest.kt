/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.keyboard.MultiTap
import org.fcitx.fcitx5.android.input.keyboard.PendingInputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.fcitx.fcitx5.android.host.KeyStates as HostKeyStates

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
    fun backspaceAfterASyllableGivesTheDigitsBack() = with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
        type(EngineReplay.nineKeys("64"))
        val before = candidates
        syllable("mi")
        backspace()
        assertTrue("column ${panel.syllables}", "ni" in panel.syllables && "mi" in panel.syllables)
        assertEquals(before.take(3), candidates.take(3))
    }

    @Test
    @Uat("T9-05")
    fun oneSeparatesSyllables() = with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
        type(EngineReplay.nineKeys("94126"))
        assertTrue("「$preedit」", "'" in preedit)
        expectAmong("西安")
    }

    @Test
    @Uat("T9-12")
    fun keysThatMakeASyllableAreRead() = with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
        type(EngineReplay.nineKeys("94"))
        assertTrue("94 「$preedit」: $threePages", "中国" !in threePages)
        backspace(2)
        type(EngineReplay.nineKeys("94664486"))
        expectFirst("中国")
        backspace(8)
        type(EngineReplay.nineKeys("25"))
        assertTrue("25 「$preedit」: $threePages", "北京" in threePages)
    }

    @Test
    @Uat("T9-20")
    fun emojiOnTheNineKeys() = with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
        type(EngineReplay.nineKeys("4242"))
        expectFirst("哈哈")
        assertTrue("😃 at ${candidates.indexOf("😃")}: ${candidates.take(6)}", candidates.indexOf("😃") >= 2)
        pick("😃")
        expectText("😃")
    }

    @Test
    @Uat("T9-24")
    fun twoQuickPressesAreTwoKeys() = with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
        type(EngineReplay.nineKeys("44"))
        assertTrue("「$preedit」", preedit.replace("'", "").replace(" ", "") != "h")
        assertTrue("44: ${candidates.take(8)}", listOf("哈哈", "广告", "个个", "好好").any { it in candidates })
    }

    @Test
    @Uat("T9-25")
    fun aCharacterErasedIsNoWordBefore() = with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
        type(EngineReplay.nineKeys("64"))
        pick("你")
        backspace()
        expectText("")
        type(EngineReplay.nineKeys("7474"))
        expectFirst("日期")
    }

    @Test
    @Uat("T9-32")
    fun oneInAnEnglishWordIsAnApostrophe() = with(EngineReplay(im = InputMethodCatalog.ENGLISH)) {
        nine("366")
        type("'")
        nine("8")
        space()
        expectText("don't ")
    }

    @Test
    @Uat("T9-40")
    fun lettersPinnedSpellAWordNotInTheDictionaryAndItIsKept() = with(EngineReplay(im = InputMethodCatalog.ENGLISH)) {
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
        with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
            type(EngineReplay.nineKeys("64"))
            ime(InputMethodCatalog.ENGLISH)
            expectText("ni")
            expectPreedit("")
        }
        with(EngineReplay(im = InputMethodCatalog.ENGLISH)) {
            nine("4663")
            val first = candidates.first()
            ime(InputMethodCatalog.PINYIN)
            expectText(first)
            expectPreedit("")
        }
    }

    // U-20 (D32 B) on the nine keys: their full digits lift a word under the digits of a part (they read no 简拼)
    @Test
    @Uat("T9-56b")
    fun aWordTypedTwiceInFullDigitsGoesThirdUnderAPart() = with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
        fun shown(keys: String, pick: String?): List<String> {
            focus("他")
            type(EngineReplay.nineKeys(keys))
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
        type(EngineReplay.nineKeys(digit.toString()))
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
    fun multiTapInFullLiftsAWordUnderPartDigits() = with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
        assertTrue("fresh", "奉化" !in shownAfterTa({ type(EngineReplay.nineKeys("33644")) }, null).take(5))
        repeat(2) { shownAfterTa({ fenghua() }, "奉化") }
        val part = shownAfterTa({ type(EngineReplay.nineKeys("33644")) }, null)
        assertTrue("33644 $part", "奉化" in part.take(3))
    }

    // and the other way: the digits typed in full, 连按's part lifts it
    @Test
    @Uat("T9-56d")
    fun digitsInFullLiftAWordUnderPartMultiTap() = with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
        assertTrue("fresh", "奉化" !in shownAfterTa({ fengh() }, null).take(5))
        repeat(2) { shownAfterTa({ type(EngineReplay.nineKeys("3364482")) }, "奉化") }
        val part = shownAfterTa({ fengh() }, null)
        assertTrue("fengh $part", "奉化" in part.take(3))
    }

    // ------------------------------------------------------------------ 连按, on the replay's clock

    /**
     * The digit keys under 连按 at [timeout] ms (连按选字母 中 is T9MultiTap.Medium's 600), as
     * T9Keyboard.resolve reads a press at its time: the digit, or the same key pressed again soon its
     * next letter (Keyboard.letterKey, again). The time is the replay's [HostReplay.clock].
     */
    private class Presses(val replay: HostReplay, timeout: Long = 600) {
        private val tap = MultiTap(timeout)

        private fun press(digit: Char) {
            val letter = tap.press(digit, NineKeyLetters.STANDARD.letters(digit), replay.clock.uptimeMillis())
            if (letter == null) {
                replay.key(EngineReplay.symOf(Keyboard.pinyinNineKey(digit)))
            } else {
                replay.key(EngineReplay.symOf(Keyboard.letterKey(letter, again = true)), HostKeyStates.VIRTUAL)
            }
        }

        /** [digits] pressed [gapMillis] apart, then the user pauses. */
        fun presses(digits: String, gapMillis: Long) {
            digits.forEachIndexed { i, d ->
                if (i > 0) replay.away(gapMillis)
                press(d)
            }
            replay.pause()
        }

        // ⌫ is no digit: the next press a key of its own
        fun backspace() {
            tap.reset()
            replay.backspace()
        }
    }

    @Test
    @Uat("T9-19")
    fun withMultiTapAQuickSecondPressIsTheLetterLearnedWordOrNot() = with(HostReplay(im = InputMethodCatalog.PINYIN)) {
        val keys = Presses(this)
        // slowly: two keys, 广告 among them, picked and so learned
        keys.presses("44", SLOW)
        assertTrue("4 4 slowly: ${candidates.take(8)}", "广告" in candidates)
        pick("广告")
        expectText("广告")
        focus("")
        // quickly: h, the h characters, not two keys' 广告 (learned) or 哈哈 (the user's 2026-10-19 gg)
        keys.presses("44", QUICK)
        assertEquals("4 4 quickly 「$preedit」", "h", preedit.replace("'", "").replace(" ", ""))
        val hWords = candidates.take(8)
        assertTrue("4 4 quickly: $hWords", "广告" !in hWords && "哈哈" !in hWords && listOf("和", "会", "还", "好", "很").any { it in hWords })
        keys.presses("26", QUICK)
        val han = candidates.take(12)
        assertTrue("h 2 6: $han", listOf("还", "汉", "含", "寒", "喊", "韩", "憨", "函", "汗").any { it in han })
        focus("")
        // Backspace takes the 连按 letter whole: back to 7 alone
        keys.presses("7", 0)
        val seven = preedit to candidates.take(6)
        keys.presses("44", QUICK)
        assertTrue("7 4 4 「$preedit」", preedit != seven.first)
        keys.backspace()
        assertEquals(seven, preedit to candidates.take(6))
    }

    // typed a letter at a time (九键英文 连按, and always where English suggests nothing), each
    // letter is the keyboard's own commit (resolveEnglish's CommitAction, NineKeysTapUatTest): the
    // engine only told of the field's text, which it offers nothing for
    @Test
    @Uat("T9-33", "T9-44")
    fun lettersTypedAtATimeLeaveEnglishNothingToOffer() {
        with(EngineReplay(im = InputMethodCatalog.ENGLISH)) {
            paste("hello")
            assertTrue("offered $page", page.isEmpty())
        }
        with(EngineReplay(im = InputMethodCatalog.ENGLISH, field = EngineReplay.Field.NO_SUGGESTIONS)) {
            paste("h")
            assertTrue("offered $page", page.isEmpty())
        }
    }

    private fun HostReplay.nine(digits: String) {
        digits.forEach { key(EngineReplay.symOf(Keyboard.nineKey(it, upper = false)), HostKeyStates.VIRTUAL) }
        pause()
    }

    // a T9-36 row: how the word is ended, and what the field has after it
    private data class Ending(val name: String, val act: HostReplay.() -> Unit, val suffix: String)

    @Test
    @Uat("T9-36")
    fun anEnglishWordGoesInBeforeEnterAMarkZeroOrAHeldDigit() {
        // each ending, and what it leaves behind the word (English's space after a word put behind the mark)
        val ends = listOf(
            Ending("enter", { enter() }, "\n"),
            Ending("the column's ,", { type(",") }, ", "),
            Ending("0", { type("0") }, "0"),
            // the keyboard's CommitAction (a long press's digit): English keeps what is composing, the word shown
            Ending("4 held, 4 picked", { assertEquals(PendingInputPolicy.Resolution.FinishComposing, commitOverEngine("4")) }, "4"),
        )
        assertRows(ends, { it.name }) { (end, act, suffix) ->
            with(HostReplay(im = InputMethodCatalog.ENGLISH)) {
                nine("4663")
                val first = candidates.first()
                act()
                assertEquals(end, first + suffix, text)
                assertEquals(end, "", preedit)
            }
        }
    }

    // each key in Shift's case as T9Keyboard sends it: U a capital, . not
    private fun HostReplay.nineCased(digits: String, cases: String) {
        digits.indices.forEach { key(EngineReplay.symOf(Keyboard.nineKey(digits[it], upper = cases[it] == 'U')), HostKeyStates.VIRTUAL) }
        pause()
    }

    // the keyboard half (Shift lit at a sentence's start, used up by a letter, locked by two taps): T9UatTest
    @Test
    @Uat("T9-31")
    fun nineKeysEnglishTakesEachKeysCase() = with(HostReplay(im = InputMethodCatalog.ENGLISH)) {
        nineCased("43556", "U....")
        space()
        expectText("Hello ")
        // 4 6's first word whatever it is (Go, In, I'm): its first letter alone a capital, then all of them
        nineCased("46", "U.")
        space()
        val second = text.removePrefix("Hello ").trim()
        assertTrue(text, second.first().isUpperCase() && second.drop(1).none { it.isUpperCase() })
        nineCased("46", "UU")
        space()
        val third = text.removePrefix("Hello $second ").trim()
        assertTrue(text, third.length >= 2 && third.none { it.isLowerCase() })
    }

    // 0 while typing: the first candidate in, then 0, as 0.4 had it (not the tenth candidate); 0 alone a 0
    @Test
    @Uat("T9-59")
    fun zeroWhileTypingCommitsTheFirstThenZero() = with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
        type(EngineReplay.nineKeys("64"))
        val first = candidates.first()
        type(EngineReplay.nineKeys("0"))
        assertEquals(first + "0", text)
        assertEquals("", preedit)
        type(EngineReplay.nineKeys("0"))
        assertEquals(first + "00", text)
    }

    private companion object {
        // the case's 0.3 s and 1 s and more between presses
        const val QUICK = 300L
        const val SLOW = 1_200L
    }
}
