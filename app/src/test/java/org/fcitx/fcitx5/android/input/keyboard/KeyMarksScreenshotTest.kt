/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 快捷键标记位置's five places (uat/02 KB-64) on the full keyboard, standard and 紧凑, and on 九键,
 * 大千 and handwriting: the goldens under src/test/screenshots/KeyMarksScreenshotTest, looked at one
 * by one for a mark on another or on a letter (KeyMarksEverywhereUatTest measures it everywhere).
 */
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
        "handwriting" to (InputMethodEntry(InputMethodCatalog.HANDWRITING, "手写", "", "", "手", "zh_CN", "androidengine", false) to null),
    )

    private fun shoot(name: String, at: ShortcutMarkPosition, board: Pair<InputMethodEntry, ImKeyboards.Chosen?>) {
        prefs.set(AppPrefs.getInstance().keyboard.shortcutMarkPosition, at)
        KeyboardHarness(ThemePreset.PixelLight, board.first, keyboards = board.second).use { h ->
            h.start()
            h.capture("KeyMarksScreenshotTest/${name}_${at.name}")
        }
    }

    @Test
    @Uat("KB-64")
    fun everyPositionOnTheKeyboards() {
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        for (at in ShortcutMarkPosition.entries) for ((name, board) in boards) shoot(name, at, board)
    }

    @Test
    @Uat("KB-64")
    fun everyPositionCompact() {
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        for (at in ShortcutMarkPosition.entries) shoot("full_compact", at, boards[0].second)
    }
}
