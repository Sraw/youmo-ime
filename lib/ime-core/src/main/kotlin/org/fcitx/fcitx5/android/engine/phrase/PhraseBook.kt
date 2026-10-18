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
 * a word there keeps the date, and deleting the date turns it off rather than letting it come back,
 * while deleting the user's own word gives the key back to the built-in ones not deleted.
 * The built-in ones are for full pinyin only ([offered] without them elsewhere): in 双拼 `sj` and
 * `xq` spell syllables (san, xiu in 自然码), whose words they would push down; so there the user's
 * lines reading as a built-in one are left out too, as a change in either made them the user's.
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

    // the user's without the built-in ones they were made to keep
    private var own: CustomPhrases = withoutBuiltIns(phrases)

    /** What is offered where [builtIns] are: [phrases]; elsewhere the user's alone. */
    fun offered(builtIns: Boolean): CustomPhrases = if (builtIns) phrases else own

    /** [value] made the first phrase under [key], the built-in ones there kept for where they are offered. */
    fun pin(key: String, value: String) = change(user.adopting(key, builtIn).pinned(key, value))

    /**
     * [value] no longer a phrase under [key]: one reading as a built-in one there is turned off, the
     * user's own line too as the two cannot be told apart once adopted, so it stays gone; any other
     * is deleted, and a key emptied so offers again the built-in ones never deleted.
     */
    fun remove(key: String, value: String) {
        val builtInOne = builtIn.all.any { it.key == key && it.value == value }
        val mine = user.adopting(key, builtIn)
        change(if (builtInOne) mine.turnedOff(key, value) else mine.without(key, value))
    }

    private fun withoutBuiltIns(phrases: CustomPhrases) =
        builtIn.all.fold(phrases) { p, b -> p.without(b.key, b.value) }

    private fun change(to: CustomPhrases) {
        user = to
        phrases = to.over(builtIn)
        own = withoutBuiltIns(to)
        save(to)
    }
}
