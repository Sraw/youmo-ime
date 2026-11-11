/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.KeyListsMigration

/**
 * The user's lists of every key as the keyboards read them now (main thread). Until the stored `key_lists`
 * (060-keys-unify S4a, its KeyListsCache) is merged, made in memory from the old keys as its migration makes
 * them, kept while they are the same text: the one place S4a's merge changes.
 */
internal object KeyListsNow {
    private var from: List<String>? = null
    private var made = KeyLists.Empty

    val current: KeyLists
        get() {
            val prefs = AppPrefs.getInstance()
            val now = listOf(prefs.internal.popupOverrides.getValue(), prefs.internal.keyPopups.getValue(), prefs.keyboard.t9Symbols.getValue())
            if (now != from) {
                from = now
                made = KeyLists.parse(KeyListsMigration.migrate(now[0], now[1], now[2]).text) ?: KeyLists.Empty
            }
            return made
        }
}
