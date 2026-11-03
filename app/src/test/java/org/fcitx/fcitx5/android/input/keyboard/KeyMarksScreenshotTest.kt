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
 * 快捷键图标位置's six places and 标点位置's on the full keyboard, standard and 紧凑, and on 九键, 大千
 * and handwriting (uat/02 KB-66): the goldens under src/test/screenshots/KeyMarksScreenshotTest,
 * named <board>_<标点位置>_<快捷键图标位置>, looked at one by one for a mark on another or on a letter
 * (KeyMarksEverywhereUatTest measures it everywhere).
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

    // each shortcut place with the symbols top right (bottom right where the shortcuts are), each symbol
    // place with the shortcuts top left (bottom right where the symbols are): pairs the settings allow
    private val pairs = KeyMarkPosition.entries.map { (if (it == KeyMarkPosition.TopRight) KeyMarkPosition.BottomRight else KeyMarkPosition.TopRight) to it } +
        KeyMarkPosition.entries.filter { it != KeyMarkPosition.TopRight }
            .map { it to (if (it == KeyMarkPosition.TopLeft) KeyMarkPosition.BottomRight else KeyMarkPosition.TopLeft) }

    private fun shoot(name: String, symbolAt: KeyMarkPosition, shortcutAt: KeyMarkPosition, board: Pair<InputMethodEntry, ImKeyboards.Chosen?>) {
        prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, symbolAt)
        prefs.set(AppPrefs.getInstance().keyboard.shortcutMarkPosition, shortcutAt)
        KeyboardHarness(ThemePreset.PixelLight, board.first, keyboards = board.second).use { h ->
            h.start()
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
        // 紧凑 is both heights at its level (KeyboardHeightLevels.isCompact): the portrait one alone is
        // just a low keyboard, its keys made as standard ones
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        // 底部 has no room there: the first corner the other does not take
        for ((symbolAt, shortcutAt) in (pairs + (KeyMarkPosition.Bottom to KeyMarkPosition.TopRight) + (KeyMarkPosition.TopLeft to KeyMarkPosition.Bottom)).distinct()) {
            shoot("full_compact", symbolAt, shortcutAt, boards[0].second)
        }
    }
}
