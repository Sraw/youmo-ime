/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** uat/07-shuangpin.md: each case from B0-双拼 (自然码 unless the case sets another 方案). */
class ShuangpinUatTest {

    @Test
    @Uat("SP-02")
    fun xiaohe() = with(EngineReplay(settings(EngineSettings(shuangpin = "Xiaohe")), im = InputMethodCatalog.SHUANGPIN)) {
        type("nihc")
        space()
        expectText("你好")
    }

    @Test
    @Uat("SP-04")
    fun aSyllableWithNoInitial() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        type("aj")
        expectAmong("安")
    }

    @Test
    @Uat("SP-05")
    fun twoKeysNoSyllableAreInitials() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        type("fx")
        expectPreedit("f x")
        assertTrue("fx: $page", "发现" in page || "方向" in page)
        backspace(2)
        type("vg")
        expectPreedit("zheng")
    }

    @Test
    @Uat("SP-07")
    fun aWordMadeInFullPinyinTypesInShuangpinItsOrderItsOwn() = with(EngineReplay()) {
        learnWangXiaoming()
        ime(InputMethodCatalog.SHUANGPIN)
        type("whxcmy")
        expectStart("王小明", "汪小明")
    }

    @Test
    @Uat("SP-09")
    fun everyKeyboardGlides() {
        with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
            glide("nihk")
            assertEquals("ni hao", preedit.replace("'", " "))
            expectAmong("你好")
        }
        with(EngineReplay(im = InputMethodCatalog.WUBI)) {
            glide("wqiy")
            assertTrue("「$preedit」 ${candidates.take(3)} 「$text」", (preedit == "wqiy" && candidates.firstOrNull() == "你") || text == "你")
        }
        with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
            glide("nihao", GlideLayout.nineKeys())
            assertTrue("「$preedit」", preedit.isNotEmpty() && preedit.none(Char::isDigit))
            expectAmong("你好")
        }
    }

    // U-20 (D32 B) in 自然码: fghw twice lifts 奉化 under the part fgh (two keys are a syllable there: fh is fang); full pinyin's uses do not
    @Test
    @Uat("SP-13b", "SP-13c")
    fun aWordTypedTwiceInFullGoesThirdUnderAPartWithinItsKeyboard() {
        with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
            fun shown(keys: String, pick: String?): List<String> {
                focus("他")
                type(keys)
                return candidates.also { if (pick != null) pick(pick) else backspace(keys.length) }
            }
            assertTrue("fresh", "奉化" !in shown("fgh", null).take(5))
            repeat(2) { shown("fghw", "奉化") }
            val part = shown("fgh", null)
            assertTrue("fgh $part", "奉化" in part.take(3))
        }
        with(EngineReplay()) {
            repeat(2) {
                focus("他")
                type("fenghua")
                pick("奉化")
            }
            ime(InputMethodCatalog.SHUANGPIN)
            focus("他")
            type("fgh")
            assertTrue("full pinyin's: $candidates", "奉化" !in candidates.take(5))
        }
    }

    // the scheme's letters as the 18 keys or the nine keys send them: each its key's code (spaces apart for reading)
    private fun on(letters: NineKeyLetters, keys: String) = keys.filter { it != ' ' }.map { letters.key(it) }.joinToString("")

    @Test
    @Uat("SP-14")
    fun theEighteenKeysInXiaoheTypeASentenceAndOfferTheSyllables() =
        with(EngineReplay(settings(EngineSettings(shuangpin = "Xiaohe")), im = InputMethodCatalog.SHUANGPIN)) {
            type(on(NineKeyLetters.G18, "wo"))
            assertTrue("${panel.syllables}", "wo" in panel.syllables)
            type(on(NineKeyLetters.G18, "mf jb tm qu ii fj"))
            assertTrue("$page", SENTENCE in page)
            pick(SENTENCE)
            expectText(SENTENCE)
            assertTrue(EngineReplay.noPrivateUse(text))
        }

    @Test
    @Uat("SP-15")
    fun theNineKeysInZiranmaTypeASentenceAndOfferTheSyllables() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        type(on(NineKeyLetters.NINE_SP, "wo"))
        assertTrue("${panel.syllables}", "wo" in panel.syllables)
        type(on(NineKeyLetters.NINE_SP, "mf jn tm qu ii fj"))
        assertTrue("$page", SENTENCE in page)
        pick(SENTENCE)
        expectText(SENTENCE)
        assertTrue(EngineReplay.noPrivateUse(text))
    }

    @Test
    @Uat("SP-16")
    fun microsoftsIngIsTheLSemicolonKey() =
        with(EngineReplay(settings(EngineSettings(shuangpin = "MS")), im = InputMethodCatalog.SHUANGPIN)) {
            type(on(NineKeyLetters.G18, "m;"))
            assertTrue("$page", "明" in page || "名" in page)
            backspace(2)
            type(on(NineKeyLetters.G18, "le"))
            assertTrue("$page", "了" in page)
        }

    @Test
    @Uat("SP-17")
    fun theEighteenKeysLearnApartFromTheFullKeyboard() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        val shi = on(NineKeyLetters.G18, "ui")
        val keys = typed(shi)
        val full = typed("ui").take(5)
        assertEquals(full.first(), keys.first())
        val second = keys[1]
        repeat(2) {
            typed(shi)
            pick(second)
            commit("。")
        }
        assertEquals(second, typed(shi).first())
        // learned under the 18 keys' own kind, not the full keyboard's
        val record = String(UatEngines.learningRecord(), Charsets.UTF_8)
        assertTrue(Kinds.shuangpinKeys("Ziranma", NineKeyLetters.G18.tag()) in record)
        // the full keyboard's order (sp:) as it was: nothing of the 18 keys' (spk:) learning
        assertEquals(full, typed("ui").take(5))
    }

    @Test
    @Uat("SP-18")
    fun enterOrASwitchCommitsTheReadingShownInLetters() = with(HostReplay(im = InputMethodCatalog.SHUANGPIN)) {
        keys(on(NineKeyLetters.G18, "xi"))
        val xi = preedit
        enter()
        assertEquals(xi.filter { it != ' ' && it != '\'' }, text)
        focus("")
        keys(on(NineKeyLetters.G18, "nih"))
        val nih = preedit
        ime(InputMethodCatalog.ENGLISH)
        assertEquals(nih.filter { it != ' ' && it != '\'' }, text)
        assertTrue(text, text.isNotEmpty() && text.all { it in 'a'..'z' })
    }

    @Test
    @Uat("SP-22")
    fun onTheNineKeysOneSeparatesTheSyllablesWhileTyping() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        // 6 4, 1 (the keyboard's ' while typing: T9Keyboard.resolveShuangpin), 4 3
        type(on(NineKeyLetters.NINE_SP, "ni") + "'" + on(NineKeyLetters.NINE_SP, "hd"))
        assertTrue(preedit, '\'' in preedit)
    }

    @Test
    @Uat("SP-23")
    fun microsoftsScreenSemicolonIsAMarkAndAnInitialTakenKeepsItsSyllables() {
        with(HostReplay(engines = settings(EngineSettings(shuangpin = "MS")), im = InputMethodCatalog.SHUANGPIN)) {
            keys(on(NineKeyLetters.G18, "ni"))
            val first = candidates.first()
            // the long press's ; is the screen's character, not the L; key's code
            keys(";")
            expectText("$first；")
        }
        with(EngineReplay(settings(EngineSettings(shuangpin = "MS")), im = InputMethodCatalog.SHUANGPIN)) {
            type(on(NineKeyLetters.G18, "xi"))
            syllable("c")
            assertFalse("$candidates", "西" in candidates)
            assertTrue("${panel.syllables}", panel.syllables.isNotEmpty() && panel.syllables.all { it.startsWith("c") })
            assertTrue("${panel.syllables}", "ci" in panel.syllables || "cuo" in panel.syllables)
            // and the words are c-'s: the first few read ci or cuo, as 词, 次, 错
            assertTrue("$candidates", candidates.take(5).any { it in setOf("词", "次", "此", "错", "刺", "瓷") })
        }
    }

    private companion object {
        const val SENTENCE = "我们今天去吃饭"
    }
}
