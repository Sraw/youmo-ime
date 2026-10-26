/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.input.KeyboardHarness
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
            assertEquals(h.inputView.resources.displayMetrics.density * CodeRowHeight.STRIP_DP, h.box(h.inputView.findViewById(R.id.code_row_text)).height().toFloat(), 1f)
            h.capture("PinyinKeyboardScreenshotTest/keys14_light")
        }
    }

    // the code row is tall in pinyin on the 14 keys, thin in English; the keys keep their height
    @Test
    fun theCodeRowFollowsTheInputMethod() {
        setting.setValue(PinyinKeyboard.Keys14)
        KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN).use { h ->
            h.start()
            h.relayout()
            val density = h.inputView.resources.displayMetrics.density
            fun row() = h.box(h.inputView.findViewById(R.id.code_row_text)).height().toFloat()
            val keys = h.box(h.board).height()
            assertEquals(density * CodeRowHeight.STRIP_DP, row(), 1f)
            val english = InputMethodEntry(Engines.ENGLISH, "English", "", "", "En", "en", "keyboard", false)
            h.api.inputMethodEntryCached = english
            h.emit(FcitxEvent.IMChangeEvent(english))
            assertEquals(density * CodeRowHeight.THIN_DP, row(), 1f)
            assertEquals(keys, h.box(h.board).height())
            h.api.inputMethodEntryCached = KeyboardHarness.PINYIN
            h.emit(FcitxEvent.IMChangeEvent(KeyboardHarness.PINYIN))
            assertEquals(density * CodeRowHeight.STRIP_DP, row(), 1f)
            assertEquals(keys, h.box(h.board).height())
        }
    }

    // landscape at 很高, where the 70% cap binds: the area stays put, the keys give up the row's 14 dp
    // in pinyin on the 14 keys and take it back in English, recomputed on each switch
    @Test
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun whereTheCapBindsTheKeysGiveUpTheTallRow() {
        setting.setValue(PinyinKeyboard.Keys14)
        val level = AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape
        level.setValue(60)
        try {
            KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN).use { h ->
                h.start()
                h.relayout()
                val density = h.inputView.resources.displayMetrics.density
                val screenH = h.inputView.resources.displayMetrics.heightPixels
                fun keys() = h.box(h.board).height()
                fun area() = h.box(h.board).bottom - h.box(h.inputView.findViewById(R.id.code_row_text)).top
                val tall = keys()
                assertTrue("area ${area()} of $screenH", area() <= 0.70 * screenH + 2)
                val english = InputMethodEntry(Engines.ENGLISH, "English", "", "", "En", "en", "keyboard", false)
                h.api.inputMethodEntryCached = english
                h.emit(FcitxEvent.IMChangeEvent(english))
                assertEquals(density * (CodeRowHeight.STRIP_DP - CodeRowHeight.THIN_DP), (keys() - tall).toFloat(), 2f)
                assertTrue("area ${area()} of $screenH", area() <= 0.70 * screenH + 2)
                h.api.inputMethodEntryCached = KeyboardHarness.PINYIN
                h.emit(FcitxEvent.IMChangeEvent(KeyboardHarness.PINYIN))
                assertEquals(tall, keys())
            }
        } finally {
            level.setValue(level.defaultValue)
        }
    }
}
