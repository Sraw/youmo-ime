/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** uat/07-shuangpin.md: each case from B0-双拼 (自然码 unless the case sets another 方案). */
class ShuangpinUatTest {

    @Test
    @Uat("SP-02")
    fun xiaohe() = with(EngineReplay(settings(EngineSettings(shuangpin = "Xiaohe")), im = Engines.SHUANGPIN)) {
        type("nihc")
        space()
        expectText("你好")
    }

    @Test
    @Uat("SP-04")
    fun aSyllableWithNoInitial() = with(EngineReplay(im = Engines.SHUANGPIN)) {
        type("aj")
        expectAmong("安")
    }

    @Test
    @Uat("SP-05")
    fun twoKeysNoSyllableAreInitials() = with(EngineReplay(im = Engines.SHUANGPIN)) {
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
        ime(Engines.SHUANGPIN)
        type("whxcmy")
        expectStart("王小明", "汪小明")
    }

    @Test
    @Uat("SP-09")
    fun everyKeyboardGlides() {
        with(EngineReplay(im = Engines.SHUANGPIN)) {
            glide("nihk")
            assertEquals("ni hao", preedit.replace("'", " "))
            expectAmong("你好")
        }
        with(EngineReplay(im = Engines.WUBI)) {
            glide("wqiy")
            assertTrue("「$preedit」 ${candidates.take(3)} 「$text」", (preedit == "wqiy" && candidates.firstOrNull() == "你") || text == "你")
        }
        with(EngineReplay(im = Engines.T9)) {
            glide("nihao", GlideLayout.nineKeys())
            assertTrue("「$preedit」", preedit.isNotEmpty() && preedit.none(Char::isDigit))
            expectAmong("你好")
        }
    }

    // U-20 (D32 B) in 自然码: fghw twice lifts 奉化 under the part fgh (two keys are a syllable there: fh is fang); full pinyin's uses do not
    @Test
    @Uat("SP-13b", "SP-13c")
    fun aWordTypedTwiceInFullGoesThirdUnderAPartWithinItsKeyboard() {
        with(EngineReplay(im = Engines.SHUANGPIN)) {
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
            ime(Engines.SHUANGPIN)
            focus("他")
            type("fgh")
            assertTrue("full pinyin's: $candidates", "奉化" !in candidates.take(5))
        }
    }
}
