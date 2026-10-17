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
    fun <T> place(candidates: List<T>, emoji: (String) -> List<String>, text: (T) -> String, whole: (T) -> Boolean = { true }, make: (String) -> T): List<T> {
        val first = candidates.firstOrNull()?.takeIf(whole) ?: return candidates
        val added = emoji(text(first)).take(MAX + AFTER).filter { e -> candidates.none { text(it) == e } }.take(MAX)
        if (added.isEmpty()) return candidates
        val at = minOf(AFTER, candidates.size)
        return candidates.subList(0, at) + added.map(make) + candidates.subList(at, candidates.size)
    }
}
