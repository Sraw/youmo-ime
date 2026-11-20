/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 全拼's 14 keys in the whole keyboard against the golden; record again with `-Proborazzi.test.record=true` when the look changes on purpose. */
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
        KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN).use { h ->
            h.start()
            val labels = h.texts()
            for (l in listOf("QW", "ER", "OP", "AS", "L", "ZX", "BN", "M", "分词")) assertTrue("$l in $labels", l in labels)
            assertEquals(h.inputView.resources.displayMetrics.density * CodeRowHeight.STRIP_DP, h.box(h.inputView.findViewById<View>(R.id.code_row_text)).height().toFloat(), 1f)
            h.captureBoard("PinyinKeyboardScreenshotTest/keys14_light")
        }
    }

    @Test
    fun theCodeRowFollowsTheInputMethod() {
        setting.setValue(PinyinKeyboard.Keys14)
        KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN).use { h ->
            h.start()
            h.relayout()
            val density = h.inputView.resources.displayMetrics.density
            fun row() = h.box(h.inputView.findViewById<View>(R.id.code_row_text)).height().toFloat()
            val keys = h.box(h.board).height()
            assertEquals(density * CodeRowHeight.STRIP_DP, row(), 1f)
            val english = InputMethodEntry(InputMethodCatalog.ENGLISH, "English", "", "En", "en")
            h.api.inputMethodEntryCached = english
            h.emit(HostEvent.InputMethodChanged(english))
            assertEquals(density * CodeRowHeight.THIN_DP, row(), 1f)
            assertEquals(keys, h.box(h.board).height())
            h.api.inputMethodEntryCached = KeyboardHarness.PINYIN
            h.emit(HostEvent.InputMethodChanged(KeyboardHarness.PINYIN))
            assertEquals(density * CodeRowHeight.STRIP_DP, row(), 1f)
            assertEquals(keys, h.box(h.board).height())
        }
    }

    // landscape at 很高, where the app's 120 dp binds: the keys give up the row's 14 dp in pinyin and take it back in English
    @Test
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun whereTheCapBindsTheKeysGiveUpTheTallRow() {
        setting.setValue(PinyinKeyboard.Keys14)
        val level = AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape
        level.setValue(60)
        try {
            KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN).use { h ->
                h.start()
                h.relayout()
                val density = h.inputView.resources.displayMetrics.density
                val screenH = h.inputView.resources.displayMetrics.heightPixels
                fun keys() = h.box(h.board).height()
                val tall = keys()
                fun app() = h.box(h.inputView.findViewById<View>(R.id.code_row_text)).top - h.box(h.inputView).top
                assertTrue("app ${app()} of $screenH", app() >= KeyboardHeightCap.MIN_APP_DP * density - 2)
                val english = InputMethodEntry(InputMethodCatalog.ENGLISH, "English", "", "En", "en")
                h.api.inputMethodEntryCached = english
                h.emit(HostEvent.InputMethodChanged(english))
                assertEquals(density * (CodeRowHeight.STRIP_DP - CodeRowHeight.THIN_DP), (keys() - tall).toFloat(), 2f)
                assertTrue("app ${app()} of $screenH", app() >= KeyboardHeightCap.MIN_APP_DP * density - 2)
                h.api.inputMethodEntryCached = KeyboardHarness.PINYIN
                h.emit(HostEvent.InputMethodChanged(KeyboardHarness.PINYIN))
                assertEquals(tall, keys())
            }
        } finally {
            level.setValue(level.defaultValue)
        }
    }
}
