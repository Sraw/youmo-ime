/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.fcitx.fcitx5.android.engine.english.EnglishUser
import org.fcitx.fcitx5.android.engine.learn.LearnLog

/** The user's committed English words for the English settings list, read via [EnglishUser] from the learning record ([log], opened on first use). On the fcitx thread. */
internal class EnglishLexicon(private val log: () -> LearnLog) {

    private var englishUser: EnglishUser? = null

    fun user(): EnglishUser = englishUser ?: EnglishUser(log).also { englishUser = it }

    /** Words typed lately or kept, most typed first; added ones [UserWord.Kind.ADDED], the rest [UserWord.Kind.LEARNED]. */
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

    /** After the record closed: [user] rereads it when reopened. */
    fun closed() {
        englishUser = null
    }
}
