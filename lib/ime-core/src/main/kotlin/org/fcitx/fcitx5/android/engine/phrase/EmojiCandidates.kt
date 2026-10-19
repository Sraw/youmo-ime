/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

/**
 * Where a word's emoji go among the candidates, in pinyin and the tables alike: those of the first
 * candidate's text ([EmojiData.of]), at most [MAX], after the first [AFTER], so the likeliest
 * readings stay where space and the eye are, the emoji next. An emoji is text, as a phrase is:
 * nothing is learned of it.
 */
object EmojiCandidates {
    const val AFTER = 2
    const val MAX = 2

    /**
     * [candidates] with the emoji of the first's [text] among them, made by [make]; none if the
     * first is not [whole] (a word of the start of the input only) or [candidates] has them all.
     */
    fun <T> place(candidates: List<T>, emoji: (String) -> List<String>, text: (T) -> String, whole: (T) -> Boolean = { true }, make: (String) -> T): List<T> =
        placed(candidates, candidates.firstOrNull(), emoji, text, whole, make = make).first

    /**
     * [candidates] with the emoji of [of] (the candidate shown first, [whole]) after the first
     * [AFTER], and the emoji added, so what is listed of them is told, not worked out again.
     * None added, [candidates] is returned itself, not a copy: PinyinSession.place reuses what it
     * worked out for that list by that identity.
     */
    fun <T> placed(
        candidates: List<T>,
        of: T?,
        emoji: (String) -> List<String>,
        text: (T) -> String,
        whole: (T) -> Boolean,
        phrases: (String) -> List<String> = { emptyList() },
        make: (String) -> T,
    ): Pair<List<T>, List<String>> {
        val first = of?.takeIf(whole) ?: return candidates to emptyList()
        val added = pick(text(first), emoji, phrases) { e -> candidates.any { text(it) == e } }
        if (added.isEmpty()) return candidates to added
        val at = minOf(AFTER, candidates.size)
        return candidates.subList(0, at) + added.map(make) + candidates.subList(at, candidates.size) to added
    }

    /**
     * The emoji of [word] to list, at most [MAX], none [listed] already: pinyin's and the tables' rule.
     * Before them the texts of the built-in phrases [word] stands for ([PhraseBook.builtInsOf]: 日期's
     * dates), all of them: placed as the emoji are, so the input methods with no `rq` have them too.
     */
    fun pick(word: String, emoji: (String) -> List<String>, phrases: (String) -> List<String> = { emptyList() }, listed: (String) -> Boolean): List<String> =
        phrases(word).filterNot(listed) + emoji(word).take(MAX + AFTER).filterNot(listed).take(MAX)
}
