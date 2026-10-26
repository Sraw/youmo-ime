/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 全拼's 14 keys in the whole keyboard, as its 「键盘」 setting shows them, against the golden image
 * under src/test/screenshots (as [ShuangpinKeyboardScreenshotTest]); before this only checked on a device.
 * Record it again with `-Proborazzi.test.record=true` when the look changes on purpose.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class PinyinKeyboardScreenshotTest {

    private val setting = AppPrefs.getInstance().keyboard.pinyinKeyboard

    @After
    fun settingAtItsDefault() = setting.setValue(setting.defaultValue)

    @Test
    fun theFourteenKeys() {
        setting.setValue(PinyinKeyboard.Keys14)
        KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN).use { h ->
            h.start()
            val labels = h.texts()
            for (l in listOf("QW", "ER", "OP", "AS", "L", "ZX", "BN", "M", "分词")) assertTrue("$l in $labels", l in labels)
            // the code row a touch tall, for the syllables on it
            assertEquals(h.inputView.resources.displayMetrics.density * KawaiiBarComponent.MIN_TOUCH_DP, h.box(h.inputView.findViewById(R.id.code_row_text)).height().toFloat(), 1f)
            h.capture("PinyinKeyboardScreenshotTest/keys14_light")
        }
    }
}
