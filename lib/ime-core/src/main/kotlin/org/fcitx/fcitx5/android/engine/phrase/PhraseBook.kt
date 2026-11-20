/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import java.io.IOException
import java.util.Calendar

/**
 * The custom phrases pinyin, tables and English share, with keyboard edits saved whole via [save] (a
 * refused save takes no effect). [builtIn] phrases (`rq` date) show under keys the user has none of; an edit
 * there adopts them, so deleting the date turns it off. Built-ins are for full pinyin only: in 双拼 `sj`/`xq`
 * spell syllables, so there user lines matching a built-in are left out too.
 */
class PhraseBook(
    phrases: CustomPhrases = CustomPhrases.EMPTY,
    private val builtIn: CustomPhrases = CustomPhrases.EMPTY,
    private val save: (CustomPhrases) -> Unit = {},
) {

    private var user: CustomPhrases = phrases

    var phrases: CustomPhrases = phrases.over(builtIn)
        private set

    // the user's without the built-ins they adopted
    private var own: CustomPhrases = withoutBuiltIns(phrases)

    fun offered(builtIns: Boolean): CustomPhrases = if (builtIns) phrases else own

    /** What is offered and the keys [typed] is: itself on letter keys; on grouped keys each key any of its letters, [taken] spelling a bar syllable. */
    fun typed(
        typed: CharSequence,
        builtIns: Boolean,
        letters: NineKeyLetters?,
        ways: (Char) -> List<String>? = { null },
        taken: (String) -> List<String> = { listOf(it) },
    ): Pair<CustomPhrases, List<String>> {
        val offered = offered(builtIns)
        val keys = when {
            offered.isEmpty -> emptyList()
            letters == null -> listOf(typed.toString()).filter { it in offered }
            else -> PhraseKeys.of(typed, letters, ways, taken)?.let(offered::keysRead).orEmpty()
        }
        return offered to keys
    }

    /** Built-ins under [key] are adopted first, so pinning keeps them. */
    fun pin(key: String, value: String) = change(user.adopting(key, builtIn).pinned(key, value))

    /** Removes [value] under [key]: an adopted built-in is turned off so it stays gone; deleting the last of the user's own gives the key back to the built-ins. */
    fun remove(key: String, value: String) {
        val builtInOne = builtIn.all.any { it.key == key && it.value == value }
        val mine = user.adopting(key, builtIn)
        change(if (builtInOne) mine.turnedOff(key, value) else mine.without(key, value))
    }

    /** Texts at [now] of the built-in phrases [word] stands for ([WORD_KEYS]), only those still offered under their key. */
    fun builtInsOf(word: String, now: () -> Calendar): List<String> {
        val key = WORD_KEYS[word] ?: return emptyList()
        return phrases.offered(key, now()).filter { o -> builtIn.all.any { it.key == key && it.value == o.phrase.value } }.map { it.text }
    }

    private fun withoutBuiltIns(phrases: CustomPhrases) =
        builtIn.all.fold(phrases) { p, b -> p.without(b.key, b.value) }

    private fun change(to: CustomPhrases) {
        // a refused save takes no effect, rather than holding this run and vanishing at the next start
        try {
            save(to)
        } catch (_: IOException) {
            return
        }
        user = to
        phrases = to.over(builtIn)
        own = withoutBuiltIns(to)
    }

    companion object {
        /** Words whose built-in phrases every Chinese input method offers beside them, by key. */
        val WORD_KEYS = mapOf("日期" to "rq", "时间" to "sj", "星期" to "xq")
    }
}
