/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import android.widget.ImageView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.theme.Theme
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
 * The keys' swipe marks ([KeyMarks]): each in the corner its own setting gives it (KeyMarks.settled:
 * 快捷键标记位置, by default top left; 标点位置, by default top right), a text shortcut's whole shrunk,
 * the function keys' up and down; against the goldens under
 * src/test/screenshots, recorded again with `-Proborazzi.test.record=true` when the look changes on purpose.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyShortcutsScreenshotTest {

    private val prefs = AppPrefs.getInstance()
    private val position = AppPrefs.getInstance().keyboard.punctuationPosition

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
            // a's select all top left, its @ top right: the two settings' defaults
            val a = h.viewKey("a") as AltTextKeyView
            assertEquals(View.VISIBLE, a.altText.visibility)
            assertTrue(h.texts().contains("你好"))
            h.capture("KeyShortcutsScreenshotTest/default_light")
        }
        shot("default_dark", ThemePreset.PixelDark)
    }

    @Test
    fun theSymbolUnderTheLetterAndNoSymbols() {
        position.setValue(KeyMarkPosition.Bottom)
        shot("bottom")
        position.setValue(KeyMarkPosition.None)
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

    // the space swiped up: the edit menu over it, paste (straight up) picked, the cells the finger reaches next beside it
    @Test
    fun theEditMenuOverTheSpace() {
        KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN).use { h ->
            h.start()
            // the icons up outside the keys: the bar's, then the menu's cells as well
            fun icons() = KeyboardHarness.all(h.inputView).count { v ->
                v is ImageView && h.shown(v) && generateSequence(v.parent as? View) { it.parent as? View }.none { it is KeyView }
            }
            val before = icons()
            val space = h.inputView.findViewById<View>(R.id.button_space)
            val finger = h.finger(space, h.at(space))
            for (i in 1..6) finger.move(h.at(space, dy = -10f * i, ms = 16))
            val menu = icons() - before
            h.capture("KeyShortcutsScreenshotTest/edit_menu", h.inputView)
            finger.up(h.at(space, dy = -60f))
            assertEquals("select all, cut, copy, paste, undo", 5, menu)
        }
    }

    // landscape's keys are low: each mark in its setting's corner, a 底部 one moved to a free corner
    // (KeyMarks.onKeys), the symbol never under the letter
    @Test
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun landscapeAndTheSymbolUnderTheLetterThere() {
        shot("landscape")
        position.setValue(KeyMarkPosition.Bottom)
        shot("landscape_bottom")
    }
}
