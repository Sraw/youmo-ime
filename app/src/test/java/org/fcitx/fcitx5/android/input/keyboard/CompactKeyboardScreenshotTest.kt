/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.engine.table.TableRoots
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 键盘高度 紧凑 (uat/14 ST-51) on every keyboard, upright and turned, at redroid's screen: the goldens
 * under src/test/screenshots/CompactKeyboardScreenshotTest, looked at one by one for text cut off
 * and corners crowded (AutoScaleTextView draws its own text, so no
 * measure here would see it cut: the eye does).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class CompactKeyboardScreenshotTest {

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        EngineState.tableKeyboardState.value = null
    }

    private fun entry(im: String, name: String) = InputMethodEntry(im, name, "", "", name.take(1), "zh_CN", "androidengine", false)

    private val shuangpin = entry(InputMethodCatalog.SHUANGPIN, "双拼")

    private class Board(
        val name: String,
        val im: InputMethodEntry = KeyboardHarness.PINYIN,
        val keyboards: ImKeyboards.Chosen? = null,
        val inputType: Int = InputType.TYPE_CLASS_TEXT,
        val table: TableKeyboard? = null,
    )

    private val boards = listOf(
        Board("pinyin_full", keyboards = ImKeyboards.Chosen()),
        Board("pinyin_18", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS18)),
        Board("pinyin_14", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14)),
        Board("pinyin_nine", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS)),
        Board("shuangpin_18", shuangpin, ImKeyboards.Chosen(shuangpin = ImKeyboard.KEYS18)),
        Board("shuangpin_nine", shuangpin, ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS)),
        Board("english_nine", KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS)),
        Board("wubi_names", entry(InputMethodCatalog.WUBI, "五笔"), table = TableKeyboard(InputMethodCatalog.WUBI, emptySet(), false, KeyLabels.NAMES, TableRoots.WUBI86)),
        Board("wubi_roots", entry(InputMethodCatalog.WUBI, "五笔"), table = TableKeyboard(InputMethodCatalog.WUBI, emptySet(), false, KeyLabels.ROOTS, TableRoots.WUBI86)),
        Board("handwriting", entry(InputMethodCatalog.HANDWRITING, "手写")),
        Board("number", inputType = InputType.TYPE_CLASS_NUMBER),
    ) + zhuyin

    // the two 注音 keyboards, their text the largest of any keyboard's: also on a small phone
    private val zhuyin get() = listOf(
        Board("zhuyin_nine", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_NINE)),
        Board("zhuyin_full", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_FULL)),
    )

    private fun shoot(orientation: String, boards: List<Board> = this.boards) {
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        // the 注音 keyboards' 简繁 hint: not said, and its "told" put back for the tests after (KeyboardPickerUatTest)
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        for (b in boards) {
            KeyboardHarness(ThemePreset.PixelLight, b.im, keyboards = b.keyboards).use { h ->
                h.start(b.inputType)
                b.table?.let {
                    EngineState.tableKeyboardState.value = it
                    h.idle()
                    h.relayout()
                }
                h.capture("CompactKeyboardScreenshotTest/${b.name}_$orientation")
                EngineState.tableKeyboardState.value = null
            }
        }
    }

    @Test
    @Uat("ST-51")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi")
    fun everyKeyboardUpright() = shoot("port")

    @Test
    @Uat("ST-51")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun everyKeyboardTurned() = shoot("land")

    // the full keyboard with the layouts 紧凑 meets: floating, one-handed, dark; split turned; [shift]:
    // its capitals too, taller beside their symbols
    private fun shootFull(name: String, theme: Theme = ThemePreset.PixelLight, shift: Boolean = false) {
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        KeyboardHarness(theme).use { h ->
            h.start()
            h.capture("CompactKeyboardScreenshotTest/$name", h.inputView)
            if (shift) {
                h.tap(h.described("Shift"))
                h.idle()
                h.capture("CompactKeyboardScreenshotTest/${name}_shift", h.inputView)
            }
        }
    }

    @Test
    @Uat("ST-51")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi")
    fun floatingOneHandedAndDark() {
        prefs.set(ThemeManager.prefs.floatingKeyboard, true)
        shootFull("floating_port", shift = true)
        ThemeManager.prefs.floatingKeyboard.setValue(false)
        prefs.set(ThemeManager.prefs.oneHanded, OneHanded.Right)
        shootFull("one_handed_port", shift = true)
        ThemeManager.prefs.oneHanded.setValue(OneHanded.Off)
        shootFull("dark_port", ThemePreset.PixelDark)
    }

    @Test
    @Uat("ST-51")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun splitTurned() {
        prefs.set(ThemeManager.prefs.splitKeyboard, SplitKeyboardMode.Always)
        shootFull("split_land")
    }

    @Test
    @Uat("ST-51")
    @Config(qualifiers = "w360dp-h740dp-port-440dpi")
    fun zhuyinOnASmallPhoneUpright() = shoot("small_port", zhuyin)

    @Test
    @Uat("ST-51")
    @Config(qualifiers = "w740dp-h360dp-land-440dpi")
    fun zhuyinOnASmallPhoneTurned() = shoot("small_land", zhuyin)
}
