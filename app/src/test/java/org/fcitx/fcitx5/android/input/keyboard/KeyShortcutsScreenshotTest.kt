/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The keys' swipe marks, each in its setting's corner; record again with `-Proborazzi.test.record=true` when the look changes on purpose. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyShortcutsScreenshotTest {

    private val prefs = AppPrefs.getInstance()
    private val position = AppPrefs.getInstance().keyboard.swipeUpPosition

    @After
    fun settingsAtTheirDefaults() {
        prefs.internal.keySwipes.setValue(prefs.internal.keySwipes.defaultValue)
        prefs.keyboard.swipeDownPosition.setValue(prefs.keyboard.swipeDownPosition.defaultValue)
        prefs.keyboard.pinyinKeyboard.setValue(prefs.keyboard.pinyinKeyboard.defaultValue)
        position.setValue(position.defaultValue)
    }

    private fun shot(name: String, theme: Theme = TestThemes.PixelLight, moved: Boolean = false) =
        KeyboardHarness(theme, KeyboardHarness.PINYIN).use { h ->
            h.start()
            if (moved) h.captureBoard("KeyShortcutsScreenshotTest/$name") else h.capture("KeyShortcutsScreenshotTest/$name")
        }

    @Test
    fun theDefaultMarksLightAndDark() {
        prefs.internal.keySwipes.setValue(SwipeSlots.Default.with("full/q", up = false, SwipeSlot.Text("你好")).serialize())
        KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN).use { h ->
            h.start()
            assertNotNull("a's symbol shown", h.key("a").alt)
            assertTrue(h.texts().contains("你好"))
            h.capture("KeyShortcutsScreenshotTest/default_light")
        }
        shot("default_dark", TestThemes.PixelDark)
    }

    @Test
    fun theSymbolUnderTheLetterAndNoSymbols() {
        position.setValue(KeyMarkPosition.Bottom)
        shot("bottom")
        position.setValue(KeyMarkPosition.None)
        shot("none")
    }

    @Test
    fun theFirstDownDrawsItAtTheDownPlace() {
        // the places are the ways' (上滑 top right, 下滑 top left): flipped, the symbol top left
        prefs.internal.keySwipes.setValue(SwipeSlots.Default.applied(SwipeOrientation.FirstDown).serialize())
        shot("first_down")
    }

    @Test
    fun theNineAndFourteenKeys() {
        prefs.keyboard.pinyinKeyboard.setValue(PinyinKeyboard.NineKeys)
        shot("nine", moved = true)
        prefs.keyboard.pinyinKeyboard.setValue(PinyinKeyboard.Keys14)
        shot("keys14", moved = true)
    }

    @Test
    @Uat("KB-105")
    fun theEditMenuOverTheSpace() {
        KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN).use { h ->
            h.start()
            fun icons() = h.shownIcons().size
            val before = icons()
            val space = h.keyNamed("space")
            val finger = h.finger(space, h.at(space))
            for (i in 1..6) finger.move(h.at(space, dy = -10f * i, ms = 16))
            val menu = icons() - before
            h.capture("KeyShortcutsScreenshotTest/edit_menu", h.inputView)
            finger.up(h.at(space, dy = -60f))
            assertEquals("select all, cut, copy, paste, undo", 5, menu)
        }
    }

    // landscape's low keys: a 底部 mark moved to a free corner (KeyMarks.onKeys), the symbol never under the letter
    @Test
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun landscapeAndTheSymbolUnderTheLetterThere() {
        shot("landscape")
        position.setValue(KeyMarkPosition.Bottom)
        shot("landscape_bottom")
    }
}
