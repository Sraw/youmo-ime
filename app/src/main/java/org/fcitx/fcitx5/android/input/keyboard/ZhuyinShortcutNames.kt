/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts

/** As the settings page names them: a 大千 key by the QWERTY key it sits on, 注音九键's by its place in the grid, 1 to 11. */
object ZhuyinShortcutNames {
    fun fullKey(qwerty: Char): String = KeyShortcuts.charKey(KeyShortcutPreset.ZHUYIN_FULL, qwerty.lowercase())

    fun nineKey(key: String): String = KeyShortcuts.charKey(KeyShortcutPreset.ZHUYIN_NINE, key)
}
