/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/**
 * The [KeyLists] the settings hold now (060-keys-unify §1.3), parsed again only when they changed. `key_lists`
 * not of the format (before the first unlock after an upgrade: the device-protected copy is still 0.5's or a
 * beta's, and AppPrefs.dropRetired has not run on it) reads as the old ones migrated in memory (§4.3), never
 * written back. Not thread-safe: its readers, the keyboards, run on the main thread.
 */
class KeyListsCache(private val stored: () -> Stored) {

    /** What [current] is made of: `key_lists`, and the three it was migrated from, read only where it is not of the format. */
    class Stored(val keyLists: String, val old: () -> Triple<String, String, String>)

    private var made: Any? = null
    private var lists = KeyLists.Empty

    /** Changed whenever [current] is another: a cache made of the lists keys on it. */
    var version = 0
        private set

    val current: KeyLists
        get() {
            val now = stored()
            val parsed = KeyLists.parse(now.keyLists)
            val key: Any = if (parsed != null) now.keyLists else now.old()
            if (key != made) {
                made = key
                val next = parsed ?: (key as Triple<*, *, *>).let { (p, n, t) ->
                    KeyLists.parse(KeyListsMigration.migrate(p as String, n as String, t as String).text)
                } ?: KeyLists.Empty
                if (next != lists) version++
                lists = next
            }
            return lists
        }

    companion object {
        // one copy for every keyboard, so a long press and the swipe and corner of its first cannot drift apart
        val user = KeyListsCache {
            val prefs = AppPrefs.getInstance()
            Stored(prefs.internal.keyLists.getValue()) {
                Triple(prefs.internal.popupOverrides.getValue(), prefs.internal.keyPopups.getValue(), prefs.keyboard.t9Symbols.getValue())
            }
        }
    }
}
