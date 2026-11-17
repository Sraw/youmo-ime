/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uat/06-pinyin.md's and 07-shuangpin.md's cases of a grouped layout of the user's (061-custom-layout §6, L4) whose
 * 预期 is the engine's, with the app's data: the 17 keys of uat/fixtures/layouts/uat-k17-1.0.layout typed by their own
 * codes ([EngineSettings.ownKeys], as GroupedKeyboard tells them), the 18 keys rearranged (uat-k18-swapped-1.0) typed
 * on 双拼 by the 18 keys' codes. The device's cases test the keyboard drawn and the import.
 */
class CustomGroupsUatTest {

    @After
    fun noneInUse() {
        NineKeyLetters.clearInUse()
    }

    // the fixture's groups, row by row: the 18 keys with H and JK one key
    private val k17 = listOf("q", "we", "rt", "y", "u", "io", "p", "a", "sd", "fg", "hjk", "l;", "z", "xc", "v", "bn", "m")

    @Test
    @Uat("PY-153")
    fun theSeventeenKeysTypeNihaoByTheirOwnCodes() =
        with(EngineReplay(settings(EngineSettings(ownKeys = k17)), im = InputMethodCatalog.PINYIN)) {
            val letters = NineKeyLetters.custom(k17)
            assertTrue(letters.codes.all { it.code in NineKeyLetters.CUSTOM_BLOCK })
            // each key tapped where its letter sits, as the device's case taps (uat/06-pinyin.md): BN's right, IO's left,
            // HJK's left third, A's middle, IO's right; their places lean the reading (按落点加权): 你好 first
            val at = listOf(0.75f, 0.25f, 1 / 6f, 0.5f, 0.75f)
            val codes = letters.typings("nihao").single()
            type(buildString { codes.forEachIndexed { i, c -> appendCodePoint(Keyboard.tapKey(c, at[i], 0.5f)) } })
            assertEquals("$candidates", "你好", candidates.first())
            pick("你好")
            expectText("你好")
            assertTrue(EngineReplay.noPrivateUse(text))
        }

    @Test
    @Uat("SP-51")
    fun theEighteenKeysRearrangedTypeZiranmaAsTheEighteenKeys() =
        with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
            val g18 = NineKeyLetters.G18.tag().split(',')
            val swapped = NineKeyLetters.custom(listOf(g18[1], g18[0]) + g18.drop(2))
            assertSame(NineKeyLetters.G18, swapped)
            // 自然码 ni hk: 你好
            type("nihk".map { swapped.key(it) }.joinToString(""))
            assertTrue("$candidates", "你好" in candidates.take(5))
            pick("你好")
            expectText("你好")
        }
}
