/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts

/**
 * Where the 注音 keyboards (built apart from this) take their keys' shortcuts: a 大千 key's
 * [KeyDef.shortcut] is [fullKey] of the QWERTY key it sits on (051-zhuyin §1.3), as the
 * settings page names it, and the keyboard is made with `shortcuts = true`. 注音九键's keys get
 * names under [KeyShortcutPreset.ZHUYIN_NINE] once its layout is known, and a tab on the page then.
 */
object ZhuyinShortcutHook {
    fun fullKey(qwerty: Char): String = KeyShortcuts.charKey(KeyShortcutPreset.ZHUYIN_FULL, qwerty.lowercase())

    fun nineKey(key: String): String = KeyShortcuts.charKey(KeyShortcutPreset.ZHUYIN_NINE, key)
}
