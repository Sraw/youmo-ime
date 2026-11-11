/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/**
 * The [KeyLists] the settings hold now (060-keys-unify §1.3), parsed again only when the text stored changed.
 * `key_lists` none of any version's ([KeyLists.needsMigration]: before the first unlock after an upgrade the
 * device-protected copy is still 0.5's or a beta's, and AppPrefs.dropRetired has not run on it; or an old page
 * changed the old ones since) reads as the old ones migrated in memory (§4.3), never written back; a later
 * version's (`@2`) as [KeyLists.Empty]. Not thread-safe: its readers, the keyboards, run on the main thread.
 */
class KeyListsCache(private val stored: () -> Stored) {

    /** What [current] is made of: `key_lists` as stored (null: not), and the three it is migrated from, read only then. */
    class Stored(val keyLists: String?, val old: () -> Old)

    data class Old(val popupOverrides: String?, val keyPopups: String?, val t9Symbols: String?)

    private var madeOf: String? = null
    private var madeOfOld: Old? = null
    private var lists = KeyLists.Empty

    /** Changed whenever [current] is another: a cache made of the lists keys on it. Read [current] first, which brings it up to date. */
    var version = 0
        private set

    val current: KeyLists
        get() {
            val now = stored()
            val text = now.keyLists
            if (!KeyLists.needsMigration(text)) {
                if (madeOfOld == null && text == madeOf) return lists
                madeOf = text
                madeOfOld = null
                made(KeyLists.parse(text) ?: KeyLists.Empty)
            } else {
                val old = now.old()
                if (old == madeOfOld) return lists
                madeOf = null
                madeOfOld = old
                made(KeyLists.parse(KeyListsMigration.migrate(old.popupOverrides, old.keyPopups, old.t9Symbols).text) ?: KeyLists.Empty)
            }
            return lists
        }

    private fun made(next: KeyLists) {
        if (next != lists) version++
        lists = next
    }

    companion object {
        // one copy for every keyboard, so a long press and the swipe and corner of its first cannot drift apart
        val user = KeyListsCache { of(AppPrefs.getInstance()) }

        fun of(prefs: AppPrefs) = Stored(prefs.storedKeyLists()) {
            Old(prefs.internal.popupOverrides.getValue(), prefs.internal.keyPopups.getValue(), prefs.keyboard.t9Symbols.getValue())
        }
    }
}
