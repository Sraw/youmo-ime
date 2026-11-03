/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts

/**
 * The [KeyShortcuts] the setting holds now, parsed again only when it changed. Read on the main
 * thread alone: the keys' swipes and marks, and the settings page.
 */
object KeyShortcutsCache {

    private var parsedText = ""
    private var parsed = KeyShortcuts.Empty

    val current: KeyShortcuts
        get() {
            val now = AppPrefs.getInstance().internal.keyShortcuts.getValue()
            if (now != parsedText) {
                parsedText = now
                parsed = KeyShortcuts.parse(now)
            }
            return parsed
        }
}
