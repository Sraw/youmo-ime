/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.CodeRowHeight
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The keyboard chosen on 拼音's and 双拼's pages (uat/14 ST-40, ST-42), shown at the next focus with
 * no restart; the pages' own half is in [SettingsUatTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyboardChoiceUatTest {

    private val keyboard = AppPrefs.getInstance().keyboard

    @After
    fun close() {
        keyboard.pinyinKeyboard.let { it.setValue(it.defaultValue) }
        keyboard.shuangpinKeyboard.let { it.setValue(it.defaultValue) }
    }

    private fun KeyboardHarness.row() = box(inputView.findViewById(R.id.code_row_text)).height().toFloat()

    private fun KeyboardHarness.dp(v: Int) = inputView.resources.displayMetrics.density * v

    @Test
    @Uat("ST-40")
    fun theFourteenKeysChosenAreThereAtTheNextFocus() {
        KeyboardHarness(im = KeyboardHarness.PINYIN).use { h ->
            h.start()
            assertTrue("q" in h.texts())
            keyboard.pinyinKeyboard.setValue(PinyinKeyboard.Keys14)
            h.start()
            h.relayout()
            val labels = h.texts()
            assertTrue(labels.toString(), "QW" in labels && "BN" in labels)
            assertEquals(h.dp(CodeRowHeight.STRIP_DP), h.row(), 1f)
        }
    }

    @Test
    @Uat("ST-42")
    fun shuangpinsEighteenThenNineKeysChosenAreThereAtTheNextFocus() {
        val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)
        KeyboardHarness(im = shuangpin).use { h ->
            h.start()
            assertTrue("q" in h.texts())
            keyboard.shuangpinKeyboard.setValue(ShuangpinKeyboard.Keys18)
            h.start()
            h.relayout()
            assertTrue(h.texts().toString(), "WE" in h.texts() && "IO" in h.texts())
            assertEquals(h.dp(CodeRowHeight.STRIP_DP), h.row(), 1f)
            keyboard.shuangpinKeyboard.setValue(ShuangpinKeyboard.NineKeys)
            h.start()
            h.relayout()
            assertTrue(h.texts().toString(), "JKL;" in h.texts() && "WXYZ" in h.texts())
            assertEquals(h.dp(CodeRowHeight.STRIP_DP), h.row(), 1f)
        }
    }
}
