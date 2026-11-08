/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 拼音键字母大小写 (keyboard.letterCase): a Chinese input method's letter faces and press bubbles in the
 * case it says, Shift or not; English's as Shift has them whatever it says; what a screen reader hears
 * and what a key types as they were. Golden images under src/test/screenshots/LetterCaseScreenshotTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class LetterCaseScreenshotTest {

    private val setting = AppPrefs.getInstance().keyboard.letterCase

    @After
    fun settingAtItsDefault() = setting.setValue(setting.defaultValue)

    private fun KeyboardHarness.letter(id: String) = keys().single { it.id == id }

    private fun KeyboardHarness.shift() {
        press(keys().single { it.id == "shift" })
        release()
        idle()
    }

    @Test
    fun pinyinAlwaysUpper() {
        val heard = KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN).start().use { h -> h.letter("q").description }
        setting.setValue(LetterCaseChoice.Upper)
        KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN).start().use { h ->
            val q = h.letter("q")
            assertEquals("Q", q.label)
            assertEquals("read as it was", heard, q.description)
            h.press(q)
            assertEquals(listOf("Q"), h.popupChoices().map { it.text })
            h.release()
            h.captureBoard("LetterCaseScreenshotTest/pinyin_upper")
        }
    }

    @Test
    fun pinyinAlwaysLowerWithShiftOn() {
        setting.setValue(LetterCaseChoice.Lower)
        KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN).start().use { h ->
            h.shift()
            val q = h.letter("q")
            assertEquals("q", q.label)
            // Shift on, its key says so; the letters small (taken before a letter's tap uses Shift up)
            h.captureBoard("LetterCaseScreenshotTest/pinyin_lower_shift")
            h.press(q)
            assertEquals(listOf("q"), h.popupChoices().map { it.text })
            h.release()
        }
    }

    @Test
    fun pinyinFollowingShift() {
        KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN).start().use { h ->
            assertEquals("q", h.letter("q").label)
            h.shift()
            assertEquals("Q", h.letter("q").label)
            h.captureBoard("LetterCaseScreenshotTest/pinyin_shift_on")
        }
    }

    // English types what it shows: Shift's case, whatever the setting
    @Test
    fun englishIgnoresIt() {
        setting.setValue(LetterCaseChoice.Upper)
        KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.ENGLISH).start().use { h ->
            assertEquals("q", h.letter("q").label)
            h.shift()
            assertEquals("Q", h.letter("q").label)
        }
        setting.setValue(LetterCaseChoice.Lower)
        KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.ENGLISH).start().use { h ->
            h.shift()
            assertEquals("Q", h.letter("q").label)
        }
    }

    @Test
    fun fourteenAndNineKeysAlwaysLower() {
        val fourteen = ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14)
        val heard14 = KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN, keyboards = fourteen).start().use { h -> h.letter("r1c1").description }
        val heard9 = KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN, keyboards = KeyboardHarness.NINE_KEYS).start().use { h -> h.letter("2").description }
        setting.setValue(LetterCaseChoice.Lower)
        KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN, keyboards = fourteen).start().use { h ->
            val qw = h.letter("r1c1")
            assertEquals("qw", qw.label)
            assertEquals(heard14, qw.description)
            h.captureBoard("LetterCaseScreenshotTest/keys14_lower")
        }
        KeyboardHarness(ThemePreset.PixelLight, KeyboardHarness.PINYIN, keyboards = KeyboardHarness.NINE_KEYS).start().use { h ->
            val abc = h.letter("2")
            assertEquals("abc", abc.label)
            assertEquals(heard9, abc.description)
            h.captureBoard("LetterCaseScreenshotTest/t9_lower")
        }
    }
}
