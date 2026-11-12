/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters

/**
 * The [KeyLists] the settings hold now (060-keys-unify §1.3), parsed again only when what it is made of changed.
 * `key_lists` of `@1` reads upgraded in memory (060-row-unlock §5: its rows by the user's 按键字母, so those are of
 * what it is made); none of any version's ([KeyLists.needsMigration]: before the first unlock after an upgrade the
 * device-protected copy is still 0.5's or a beta's, and AppPrefs.dropRetired has not run on it; or an old page
 * changed the old ones since) as the old ones migrated and upgraded in memory (§4.3), never written back; a later
 * version's (`@3`) as [KeyLists.Empty]. Not thread-safe: its readers, the keyboards, run on the main thread.
 */
class KeyListsCache(private val stored: () -> Stored) {

    /**
     * What [current] is made of: `key_lists` as stored (null: not), the nine keys' letters (`t9_letters` as stored) and
     * the three it is migrated from, read only when they are needed.
     */
    class Stored(val keyLists: String?, val nineLetters: () -> String? = { null }, val old: () -> Old)

    data class Old(val popupOverrides: String?, val keyPopups: String?, val t9Symbols: String?)

    // what [lists] was made of: the text of the current version's, else what its upgrade read
    private data class Upgraded(val text: String?, val nineLetters: String?, val old: Old?)

    private var madeOf: Any? = null
    private var lists = KeyLists.Empty

    /** Changed whenever [current] is another: a cache made of the lists keys on it. Read [current] first, which brings it up to date. */
    var version = 0
        private set

    val current: KeyLists
        get() {
            val now = stored()
            val text = now.keyLists
            val of: Any = when {
                !KeyLists.needsMigration(text) -> text.orEmpty()
                KeyLists.isVersion1(text) -> Upgraded(text, now.nineLetters(), null)
                else -> Upgraded(null, now.nineLetters(), now.old())
            }
            if (of == madeOf) return lists
            madeOf = of
            made(KeyLists.parse(if (of is Upgraded) upgraded(of) else text) ?: KeyLists.Empty)
            return lists
        }

    private fun upgraded(u: Upgraded): String {
        val v1 = u.text ?: u.old!!.let { KeyListsMigration.migrate(it.popupOverrides, it.keyPopups, it.t9Symbols).text }
        return KeyListsMigration.upgrade(v1, nineLettersOf(u.nineLetters))
    }
    /** [version] once [current] is brought up to date: what a cache of the lists compares, without a read for nothing. */
    val versionNow: Int
        get() {
            current
            return version
        }

    // the same lists parsed again kept as the instance they were: a cache comparing them by instance
    // (SceneBoard's resolvers) and one comparing [version] (KeyResolving's) then agree that nothing changed
    private fun made(next: KeyLists) {
        if (next == lists) return
        version++
        lists = next
    }

    companion object {
        // one copy for every keyboard, so a long press and the swipe and corner of its first cannot drift apart
        val user = KeyListsCache { of(AppPrefs.getInstance()) }

        /** `t9_letters` [stored] as the nine keys' letters by their digit, as 九键's keyboard and the editor have them. */
        fun nineLettersOf(stored: String?): Map<Char, String> = NineKeyLetters.parse(stored.orEmpty())
            .let { l -> ('2'..'9').associateWith { l.letters(it).lowercase() } }

        fun of(prefs: AppPrefs) = Stored(prefs.storedKeyLists(), { prefs.storedOldLongPress(AppPrefs.T9_LETTERS) }) {
            Old(prefs.storedOldLongPress(AppPrefs.POPUP_OVERRIDES), prefs.storedOldLongPress(AppPrefs.KEY_POPUPS), prefs.storedOldLongPress(AppPrefs.T9_SYMBOLS))
        }
    }
}
