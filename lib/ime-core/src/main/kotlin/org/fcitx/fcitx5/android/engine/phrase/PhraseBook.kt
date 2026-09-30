/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

/**
 * The custom phrases the pinyin sessions share, and the user's changes to them from the keyboard
 * (a candidate pinned, a phrase deleted), each handed to [save] as the whole list, as the file
 * keeps them.
 */
class PhraseBook(phrases: CustomPhrases = CustomPhrases.EMPTY, private val save: (CustomPhrases) -> Unit = {}) {

    var phrases: CustomPhrases = phrases
        private set

    /** [value] made the first phrase under [key]. */
    fun pin(key: String, value: String) = change(phrases.pinned(key, value))

    /** [value] no longer a phrase under [key]. */
    fun remove(key: String, value: String) = change(phrases.without(key, value))

    private fun change(to: CustomPhrases) {
        phrases = to
        save(to)
    }
}
