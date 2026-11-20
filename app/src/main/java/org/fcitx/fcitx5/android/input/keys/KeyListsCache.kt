/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters

/**
 * `key_lists` of no version ([KeyLists.needsMigration]: before the first unlock after an upgrade the
 * device-protected copy is still the old one's) reads as the old ones migrated in memory, never written
 * back; a later version's as [KeyLists.Empty]. Not thread-safe: the keyboards read it on the main thread.
 */
class KeyListsCache(private val stored: () -> Stored) {

    /** Read only when they are needed. */
    class Stored(val keyLists: String?, val nineLetters: () -> String? = { null }, val old: () -> Old)

    data class Old(val popupOverrides: String?, val t9Symbols: String?)

    private data class Upgraded(val nineLetters: String?, val old: Old)

    private var madeOf: Any? = null
    private var lists = KeyLists.Empty

    /** Read [current] first, which brings it up to date. */
    var version = 0
        private set

    val current: KeyLists
        get() {
            val now = stored()
            val text = now.keyLists
            val of: Any = when {
                !KeyLists.needsMigration(text) -> text.orEmpty()
                else -> Upgraded(now.nineLetters(), now.old())
            }
            if (of == madeOf) return lists
            madeOf = of
            made(KeyLists.parse(if (of is Upgraded) upgraded(of) else text) ?: KeyLists.Empty)
            return lists
        }

    private fun upgraded(u: Upgraded): String {
        val v1 = KeyListsMigration.migrate(u.old.popupOverrides, u.old.t9Symbols).text
        return KeyListsMigration.upgrade(v1, nineLettersOf(u.nineLetters))
    }
    /** Without a read for nothing. */
    val versionNow: Int
        get() {
            current
            return version
        }

    // kept as the instance: a cache comparing by instance (SceneBoard's) and one comparing [version] (KeyResolving's) agree
    private fun made(next: KeyLists) {
        if (next == lists) return
        version++
        lists = next
    }

    companion object {
        // one copy for every keyboard, so a long press and the swipe and corner of its first cannot drift apart
        val user = KeyListsCache { of(AppPrefs.getInstance()) }

        fun nineLettersOf(stored: String?): Map<Char, String> = NineKeyLetters.parse(stored.orEmpty())
            .let { l -> ('2'..'9').associateWith { l.letters(it).lowercase() } }

        fun of(prefs: AppPrefs) = Stored(prefs.storedKeyLists(), { prefs.storedOldLongPress(AppPrefs.T9_LETTERS) }) {
            Old(prefs.storedOldLongPress(AppPrefs.POPUP_OVERRIDES), prefs.storedOldLongPress(AppPrefs.T9_SYMBOLS))
        }
    }
}
