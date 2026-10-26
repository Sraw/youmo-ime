/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 双拼's 18 keys and nine keys in the whole keyboard, as its 「键盘」 setting shows them, against the
 * golden images under src/test/screenshots. Record them again with `-Proborazzi.test.record=true`
 * when the look changes on purpose.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ShuangpinKeyboardScreenshotTest {

    private val shuangpin = InputMethodEntry(Engines.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)
    private val setting = AppPrefs.getInstance().keyboard.shuangpinKeyboard

    @After
    fun settingAtItsDefault() = setting.setValue(setting.defaultValue)

    @Test
    fun theEighteenKeys() {
        setting.setValue(ShuangpinKeyboard.Keys18)
        KeyboardHarness(ThemePreset.PixelLight, shuangpin).use { h ->
            h.start()
            val labels = h.texts()
            for (l in listOf("Q", "WE", "IO", "SD", "L;", "XC", "BN", "M", "分词")) assertTrue("$l in $labels", l in labels)
            // the code row a touch tall, for the syllables on it
            assertEquals(h.inputView.resources.displayMetrics.density * KawaiiBarComponent.MIN_TOUCH_DP, h.box(h.inputView.findViewById(R.id.code_row_text)).height().toFloat(), 1f)
            h.capture("ShuangpinKeyboardScreenshotTest/keys18_light")
        }
    }

    @Test
    fun theNineKeys() {
        setting.setValue(ShuangpinKeyboard.NineKeys)
        KeyboardHarness(ThemePreset.PixelLight, shuangpin).use { h ->
            h.start()
            val labels = h.texts()
            for (l in listOf("ABC", "JKL;", "WXYZ", "重输", "符", "123")) assertTrue("$l in $labels", l in labels)
            assertFalse("no 中/英: $labels", "中/英" in labels)
            h.capture("ShuangpinKeyboardScreenshotTest/nine_light")
        }
    }
}
