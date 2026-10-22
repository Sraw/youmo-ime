/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.fcitx.fcitx5.android.engine.english.EnglishUser
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.learn.LearnLog

/**
 * The English words the user committed, for [Engines], as [UserLexicon] has pinyin's: what
 * [EnglishUser] reads of the learning record ([log], opened as English is first typed or its words
 * listed). It lists, adds and forgets those words for the English page's word list. The older
 * builds' `english.user` is only read, once, into the record (LegacyConversion). Run on the fcitx
 * thread, as [Engines] is.
 */
internal class EnglishLexicon(private val log: () -> LearnLog) {

    private var englishUser: EnglishUser? = null

    fun user(): EnglishUser = englishUser ?: EnglishUser(log).also { englishUser = it }

    /**
     * Each word typed lately or kept (added, or never corrected), the most typed first: added ones
     * [UserWord.Kind.ADDED], the rest [UserWord.Kind.LEARNED]; [UserWord.neverCorrected] those not corrected.
     */
    fun words(): List<UserWord> {
        val user = user()
        val kept = user.neverCorrectedWords
        val added = user.addedWords
        return (user.words + kept).map { w ->
            UserWord(w, "", if (w in added) UserWord.Kind.ADDED else UserWord.Kind.LEARNED, user.count(w), neverCorrected = w in kept)
        }.sortedWith(compareByDescending<UserWord> { it.count }.thenBy { it.text })
    }

    fun add(text: String): Boolean {
        if (!EnglishText.isWord(text)) return false
        user().add(text)
        return true
    }

    fun forget(texts: Collection<String>) = user().forgetAll(texts)

    fun restoreCorrection(texts: Collection<String>) = user().restoreCorrection(texts)

    /** After the record was closed: [user] reads it again as it is opened anew. */
    fun closed() {
        englishUser = null
    }
}
