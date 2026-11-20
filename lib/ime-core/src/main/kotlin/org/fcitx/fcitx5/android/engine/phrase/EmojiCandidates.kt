/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

/** Where emoji go among pinyin and table candidates: the first candidate's ([EmojiData.of]), at most [MAX], after the first [AFTER]; never learned. */
object EmojiCandidates {
    const val AFTER = 2
    const val MAX = 2

    /** None if the first is not [whole] (a word from the input's start) or all are listed already. */
    fun <T> place(candidates: List<T>, emoji: (String) -> List<String>, text: (T) -> String, whole: (T) -> Boolean = { true }, make: (String) -> T): List<T> =
        placed(candidates, candidates.firstOrNull(), emoji, text, whole, make = make).first

    /**
     * [candidates] with [of]'s emoji after the first [AFTER], plus the emoji added. With none added, returns
     * [candidates] itself: PinyinSession.place reuses work by that identity. A word [lifted] to the emoji's
     * place keeps it, the emoji right after: the user's habit beats an aside.
     */
    fun <T> placed(
        candidates: List<T>,
        of: T?,
        emoji: (String) -> List<String>,
        text: (T) -> String,
        whole: (T) -> Boolean,
        phrases: (String) -> List<String> = { emptyList() },
        lifted: (T) -> Boolean = { false },
        make: (String) -> T,
    ): Pair<List<T>, List<String>> {
        val first = of?.takeIf(whole) ?: return candidates to emptyList()
        val added = pick(text(first), emoji, phrases) { e -> candidates.any { text(it) == e } }
        if (added.isEmpty()) return candidates to added
        // the lifted words are one run (HabitRanker.raise): the emoji go after it, never inside
        var at = (AFTER..minOf(AFTER + 1, candidates.size - 1)).firstOrNull { lifted(candidates[it]) } ?: minOf(AFTER, candidates.size)
        while (at < candidates.size && lifted(candidates[at])) at++
        return candidates.subList(0, at) + added.map(make) + candidates.subList(at, candidates.size) to added
    }

    /** [word]'s emoji to list (at most [MAX], none [listed] already), preceded by the built-in phrases it stands for (日期's dates), so methods without `rq` get them too. */
    fun pick(word: String, emoji: (String) -> List<String>, phrases: (String) -> List<String> = { emptyList() }, listed: (String) -> Boolean): List<String> =
        phrases(word).filterNot(listed) + emoji(word).take(MAX + AFTER).filterNot(listed).take(MAX)
}
