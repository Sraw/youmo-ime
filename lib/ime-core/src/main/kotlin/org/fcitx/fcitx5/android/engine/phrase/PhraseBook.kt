/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

/**
 * The custom phrases the pinyin sessions share, and the user's changes to them from the keyboard
 * (a candidate pinned, a phrase deleted), each handed to [save] as the whole list, as the file
 * keeps them. [builtIn] phrases ([CustomPhrases.BUILT_IN]: the date under `rq`) are offered under
 * a key the user has none of; a change under such a key makes them the user's first, so pinning
 * a word there keeps the date, and deleting the date turns it off rather than letting it come back.
 */
class PhraseBook(
    phrases: CustomPhrases = CustomPhrases.EMPTY,
    private val builtIn: CustomPhrases = CustomPhrases.EMPTY,
    private val save: (CustomPhrases) -> Unit = {},
) {

    private var user: CustomPhrases = phrases

    /** What is offered: the user's, and the built-in ones under keys the user has none of. */
    var phrases: CustomPhrases = phrases.over(builtIn)
        private set

    /** [value] made the first phrase under [key]. */
    fun pin(key: String, value: String) = change(user.adopting(key, builtIn).pinned(key, value))

    /** [value] no longer a phrase under [key]: a built-in one turned off, so it stays gone. */
    fun remove(key: String, value: String) {
        val mine = user.adopting(key, builtIn)
        val builtInOne = builtIn.all.any { it.key == key && it.value == value }
        change(if (builtInOne) mine.turnedOff(key, value) else mine.without(key, value))
    }

    private fun change(to: CustomPhrases) {
        user = to
        phrases = to.over(builtIn)
        save(to)
    }
}
