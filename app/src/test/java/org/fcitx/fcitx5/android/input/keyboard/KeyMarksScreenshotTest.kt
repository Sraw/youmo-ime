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
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.engine.table.TableRoots
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 快捷键图标位置's six places and 标点位置's on several boards: goldens named <board>_<标点位置>_<快捷键图标位置>, looked at by eye. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyMarksScreenshotTest {

    @get:Rule
    val prefs = PrefsRule()

    private val boards = listOf(
        "full" to (KeyboardHarness.PINYIN to ImKeyboards.Chosen()),
        "nine" to (KeyboardHarness.PINYIN to ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS)),
        "zhuyin_full" to (KeyboardHarness.PINYIN to ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_FULL)),
    )

    // the pairs the settings allow: each place with the other mark in a corner it does not take
    private val pairs = KeyMarkPosition.entries.map { (if (it == KeyMarkPosition.TopRight) KeyMarkPosition.BottomRight else KeyMarkPosition.TopRight) to it } +
        KeyMarkPosition.entries.filter { it != KeyMarkPosition.TopRight }
            .map { it to (if (it == KeyMarkPosition.TopLeft) KeyMarkPosition.BottomRight else KeyMarkPosition.TopLeft) }

    private fun shoot(
        name: String, symbolAt: KeyMarkPosition, shortcutAt: KeyMarkPosition, board: Pair<InputMethodEntry, ImKeyboards.Chosen?>,
        table: TableKeyboard? = null,
    ) {
        prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, symbolAt)
        prefs.set(AppPrefs.getInstance().keyboard.swipeDownPosition, shortcutAt)
        KeyboardHarness(TestThemes.PixelLight, board.first, keyboards = board.second).use { h ->
            h.start()
            table?.let {
                EngineState.tableKeyboardState.value = it
                h.idle()
                h.relayout()
            }
            h.capture("KeyMarksScreenshotTest/${name}_${symbolAt.name}_${shortcutAt.name}")
        }
    }

    @Test
    @Uat("KB-66")
    fun everyPlaceOnTheKeyboards() {
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        for ((symbolAt, shortcutAt) in pairs.distinct()) for ((name, board) in boards) shoot(name, symbolAt, shortcutAt, board)
    }

    @Test
    @Uat("KB-66")
    fun everyPlaceCompact() {
        // 紧凑 is both heights at its level (KeyboardHeightLevels.isCompact): the portrait one alone is just a low keyboard
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        // 底部 has no room there: the symbol top right, the shortcut the first free corner
        for ((symbolAt, shortcutAt) in (pairs + (KeyMarkPosition.Bottom to KeyMarkPosition.TopRight) + (KeyMarkPosition.TopLeft to KeyMarkPosition.Bottom)).distinct()) {
            shoot("full_compact", symbolAt, shortcutAt, boards[0].second)
        }
    }

    // no room in the chosen place: another free corner of that key
    @Test
    @Uat("KB-67")
    fun noRoomAnotherCornerOfTheKey() {
        // 大千's 简繁 hint not said, its "told" put back after (PrefsRule)
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        prefs.setStyle(KeyboardStyle.ONE_HANDED, side = OneHanded.Right)
        shoot("full_compact_one_handed", KeyMarkPosition.TopRight, KeyMarkPosition.BottomRight, boards[0].second)
        val wubi = InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "五", "zh_CN")
        try {
            val names = TableKeyboard(InputMethodCatalog.WUBI, emptySet(), false, KeyLabels.NAMES, TableRoots.WUBI86)
            shoot("wubi_compact_one_handed", KeyMarkPosition.TopRight, KeyMarkPosition.BottomLeft, wubi to null, names)
        } finally {
            EngineState.tableKeyboardState.value = null
        }
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 30)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 49)
        prefs.setStyleDefault()
        prefs.setStyle(KeyboardStyle.FLOATING)
        shoot("zhuyin_full_floating", KeyMarkPosition.TopRight, KeyMarkPosition.Bottom, boards[2].second)
    }
}
