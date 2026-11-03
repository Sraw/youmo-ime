/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The keys' swipe marks ([KeyMarks]): a shortcut's icon in its way's corner, the symbol moved to the
 * other, a text shortcut's whole shrunk, the function keys' up and down; against the goldens under
 * src/test/screenshots, recorded again with `-Proborazzi.test.record=true` when the look changes on purpose.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyShortcutsScreenshotTest {

    private val prefs = AppPrefs.getInstance()
    private val position = ThemeManager.prefs.punctuationPosition

    @After
    fun settingsAtTheirDefaults() {
        prefs.internal.keyShortcuts.setValue("")
        prefs.keyboard.swipeLayers.setValue(prefs.keyboard.swipeLayers.defaultValue)
        prefs.keyboard.pinyinKeyboard.setValue(prefs.keyboard.pinyinKeyboard.defaultValue)
        position.setValue(position.defaultValue)
    }

    private fun shot(name: String, theme: Theme = ThemePreset.PixelLight) =
        KeyboardHarness(theme, KeyboardHarness.PINYIN).use { h ->
            h.start()
            h.capture("KeyShortcutsScreenshotTest/$name")
        }

    @Test
    fun theDefaultMarksLightAndDark() {
        prefs.internal.keyShortcuts.setValue(KeyShortcuts.Empty.with("full/q", KeyShortcut.Text("你好")).serialize())
        KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN).use { h ->
            h.start()
            // a's select all in its top right corner, its @ moved to the bottom right
            val a = h.key("a") as AltTextKeyView
            assertEquals(android.view.View.VISIBLE, a.altText.visibility)
            assertTrue(h.texts().contains("你好"))
            h.capture("KeyShortcutsScreenshotTest/default_light")
        }
        shot("default_dark", ThemePreset.PixelDark)
    }

    @Test
    fun theSymbolUnderTheLetterAndNoMarksAtAll() {
        position.setValue(PunctuationPosition.Bottom)
        shot("bottom")
        position.setValue(PunctuationPosition.None)
        shot("none")
    }

    @Test
    fun symbolsUpShortcutsDown() {
        prefs.keyboard.swipeLayers.setValue(SwipeLayersChoice.SymbolUpShortcutDown)
        shot("symbol_up")
    }

    @Test
    fun theNineAndFourteenKeys() {
        prefs.keyboard.pinyinKeyboard.setValue(PinyinKeyboard.NineKeys)
        shot("nine")
        prefs.keyboard.pinyinKeyboard.setValue(PinyinKeyboard.Keys14)
        shot("keys14")
    }
}
