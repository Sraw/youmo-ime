/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.engine.table.TableRoots
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

/** 拼音键字母大小写: a Chinese input method's faces and bubbles in the case it says; English's as Shift has them; screen reader and typing unchanged. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class LetterCaseScreenshotTest {

    private val setting = AppPrefs.getInstance().keyboard.letterCase

    @After
    fun settingAtItsDefault() {
        setting.setValue(setting.defaultValue)
        // process-wide: a 五笔 keyboard of a later test would show these roots
        EngineState.tableKeyboardState.value = null
    }

    private fun KeyboardHarness.letter(id: String) = keys().single { it.id == id }

    private fun KeyboardHarness.shift() {
        press(keys().single { it.id == "shift" })
        release()
        idle()
    }

    @Test
    @Uat("KB-68")
    fun pinyinAlwaysUpper() {
        val heard = KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN).start().use { h -> h.letter("q").description }
        setting.setValue(LetterCaseChoice.Upper)
        KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN).start().use { h ->
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
    @Uat("KB-68")
    fun pinyinAlwaysLowerWithShiftOn() {
        setting.setValue(LetterCaseChoice.Lower)
        KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN).start().use { h ->
            h.shift()
            val q = h.letter("q")
            assertEquals("q", q.label)
            // taken before a letter's tap uses Shift up
            h.captureBoard("LetterCaseScreenshotTest/pinyin_lower_shift")
            h.press(q)
            assertEquals(listOf("q"), h.popupChoices().map { it.text })
            h.release()
        }
    }

    @Test
    @Uat("KB-68")
    fun pinyinFollowingShift() {
        KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN).start().use { h ->
            assertEquals("q", h.letter("q").label)
            h.shift()
            assertEquals("Q", h.letter("q").label)
            h.captureBoard("LetterCaseScreenshotTest/pinyin_shift_on")
        }
    }

    @Test
    @Uat("KB-68")
    fun englishIgnoresIt() {
        setting.setValue(LetterCaseChoice.Upper)
        KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.ENGLISH).start().use { h ->
            assertEquals("q", h.letter("q").label)
            h.shift()
            assertEquals("Q", h.letter("q").label)
        }
        setting.setValue(LetterCaseChoice.Lower)
        KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.ENGLISH).start().use { h ->
            h.shift()
            assertEquals("Q", h.letter("q").label)
        }
    }

    @Test
    @Uat("KB-68")
    fun fourteenAndNineKeysAlwaysLower() {
        val fourteen = ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14)
        val heard14 = KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN, keyboards = fourteen).start().use { h -> h.letter("r1c1").description }
        val heard9 = KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN, keyboards = KeyboardHarness.NINE_KEYS).start().use { h -> h.letter("2").description }
        setting.setValue(LetterCaseChoice.Lower)
        KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN, keyboards = fourteen).start().use { h ->
            val qw = h.letter("r1c1")
            assertEquals("qw", qw.label)
            assertEquals(heard14, qw.description)
            h.captureBoard("LetterCaseScreenshotTest/keys14_lower")
        }
        KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN, keyboards = KeyboardHarness.NINE_KEYS).start().use { h ->
            val abc = h.letter("2")
            assertEquals("abc", abc.label)
            assertEquals(heard9, abc.description)
            h.captureBoard("LetterCaseScreenshotTest/t9_lower")
        }
    }

    @Test
    @Uat("KB-68")
    fun englishNineKeysIgnoreIt() {
        setting.setValue(LetterCaseChoice.Upper)
        KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.ENGLISH, keyboards = ENGLISH_NINE).start().use { h ->
            assertEquals("abc", h.letter("2").label)
        }
        setting.setValue(LetterCaseChoice.Lower)
        KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.ENGLISH, keyboards = ENGLISH_NINE).start().use { h ->
            h.shift()
            assertEquals("ABC", h.letter("2").label)
        }
    }

    @Test
    @Uat("KB-68")
    fun switchingInputMethodsInOneKeyboard() {
        setting.setValue(LetterCaseChoice.Upper)
        KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN).start().use { h ->
            assertEquals("Q", h.letter("q").label)
            h.switchTo(KeyboardHarness.ENGLISH)
            assertEquals("q", h.letter("q").label)
            h.switchTo(KeyboardHarness.PINYIN)
            assertEquals("Q", h.letter("q").label)
        }
    }

    // the setting draws, never types
    @Test
    @Uat("KB-68")
    fun whatIsTypedIsUnchanged() {
        fun typed(choice: LetterCaseChoice, shift: Boolean, keyboards: ImKeyboards.Chosen? = null, id: String = "q"): List<String> {
            setting.setValue(choice)
            return KeyboardHarness(TestThemes.PixelLight, KeyboardHarness.PINYIN, keyboards = keyboards).start().use { h ->
                if (shift) h.shift()
                h.api.calls.clear()
                h.tap(h.letter(id))
                h.idle()
                h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }
            }
        }
        val lower = typed(LetterCaseChoice.Shift, shift = false)
        val upper = typed(LetterCaseChoice.Shift, shift = true)
        assertTrue(lower.toString(), lower.single().contains("=q,"))
        assertTrue(upper.toString(), upper.single().contains("=Q,"))
        assertEquals(lower, typed(LetterCaseChoice.Upper, shift = false))
        assertEquals(upper, typed(LetterCaseChoice.Lower, shift = true))
        val fourteen = ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14)
        val group = typed(LetterCaseChoice.Shift, false, fourteen, "r1c1")
        val nine = typed(LetterCaseChoice.Shift, false, KeyboardHarness.NINE_KEYS, "2")
        group.single()
        nine.single()
        assertEquals(group, typed(LetterCaseChoice.Lower, false, fourteen, "r1c1"))
        assertEquals(nine, typed(LetterCaseChoice.Lower, false, KeyboardHarness.NINE_KEYS, "2"))
    }

    @Test
    @Uat("KB-68")
    fun aTableLetterWithNoRootIsReadAsEver() {
        val wubi = InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "五", "zh_CN")
        fun z() = KeyboardHarness(TestThemes.PixelLight, wubi).start().use { h ->
            EngineState.tableKeyboardState.value = TableKeyboard(InputMethodCatalog.WUBI, emptySet(), false, KeyLabels.ROOTS, TableRoots.WUBI86)
            h.idle()
            h.relayout()
            h.letter("z").let { it.label to it.description }
        }
        val (face, heard) = z()
        assertEquals("z", face)
        assertTrue("$heard", heard!!.startsWith("z"))
        setting.setValue(LetterCaseChoice.Upper)
        assertEquals("Z" to heard, z())
    }

    private fun KeyboardHarness.switchTo(im: InputMethodEntry) {
        api.inputMethodEntryCached = im
        emit(HostEvent.InputMethodChanged(im))
        idle()
    }

    private companion object {
        val ENGLISH_NINE = ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS)
    }
}
