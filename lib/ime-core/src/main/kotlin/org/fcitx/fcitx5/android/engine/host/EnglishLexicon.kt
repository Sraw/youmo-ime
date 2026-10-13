/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.fcitx.fcitx5.android.engine.english.EnglishUser
import org.fcitx.fcitx5.android.engine.english.EnglishUserStore
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.USER_ENGLISH
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

/**
 * The English words the user committed, for [Engines], as [UserLexicon] has pinyin's: what
 * [EnglishUser] counted, kept in its own log under [userDir] beside pinyin's (in memory only if
 * null), read once, as English is first typed or its words listed. It lists, adds and forgets
 * those words for the English page's word list. Run on the fcitx thread, as [Engines] is.
 */
internal class EnglishLexicon(
    private val userDir: File?,
    private val onError: (IOException) -> Unit,
    private val compactions: Executor,
) {

    /** The log, while open; closed by [Engines.close] with the others. */
    var store: EnglishUserStore? = null
        private set
    private var englishUser: EnglishUser? = null

    fun user(): EnglishUser = englishUser ?: EnglishUser().also { user ->
        englishUser = user
        if (userDir != null) {
            userDir.mkdirs()
            // a log that cannot be read or moved aside: learn in memory rather than not type
            store = try {
                EnglishUserStore(File(userDir, USER_ENGLISH), user, onError, compactions).apply { open() }
            } catch (e: IOException) {
                onError(e)
                null
            }
        }
    }

    fun words(): List<UserWord> {
        val user = user()
        return user.words.map { UserWord(it, "", UserWord.Kind.LEARNED, user.count(it)) }
            .sortedWith(compareByDescending<UserWord> { it.count }.thenBy { it.text })
    }

    fun add(text: String): Boolean {
        if (!EnglishText.isWord(text)) return false
        user().add(text)
        return true
    }

    fun forget(texts: Collection<String>) {
        val user = user()
        // a FORGOT record a word forgotten, each a pass over all the pairs as a start replays it:
        // compacted so none is, as UserLexicon.removeWords
        val gone = texts.toSet().count { it in user.words }
        user.forgetAll(texts)
        if (gone > 1) store?.compact()
    }

    /** After [store] was closed: [user] reads the log again, and appends to it. */
    fun closed() {
        store = null
        englishUser = null
    }
}
