/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts

/**
 * The 注音 keyboards' keys' shortcut names ([KeyDef.shortcut]), as the settings page names them: a 大千
 * key's [fullKey] of the QWERTY key it sits on (051-zhuyin §1.3), 注音九键's group [nineKey] of its
 * place in the grid, 1 to 11 ([org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPages.ZhuyinNine]).
 */
object ZhuyinShortcutHook {
    fun fullKey(qwerty: Char): String = KeyShortcuts.charKey(KeyShortcutPreset.ZHUYIN_FULL, qwerty.lowercase())

    fun nineKey(key: String): String = KeyShortcuts.charKey(KeyShortcutPreset.ZHUYIN_NINE, key)
}
