/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.Mine

/** How a user dictionary page list is shown: as it comes or 「最近学到」 first; all, or only 「很久没用」. */
object LexiconView {
    /** Typing days, not calendar ones: time away from the keyboard makes no word old. */
    const val UNUSED_DAYS = 60

    fun arrange(words: List<UserWord>, recentFirst: Boolean, unusedOnly: Boolean): List<UserWord> {
        val kept = if (unusedOnly) words.filter(::unused) else words
        return if (recentFirst) kept.sortedByDescending { it.order } else kept
    }

    /** Not a word whose last use is unknown (used before days were kept, added and never typed): the list is for deleting. */
    fun unused(word: UserWord) = word.idle != UserWord.NEVER && word.idle >= UNUSED_DAYS

    /** [words] with last use ([UserWord.lastUsed] local day, [UserWord.idle] typing days; [UserWord.NEVER] if unknown) and 「最近学到」's order. */
    fun dated(state: LearnState, words: List<UserWord>): List<UserWord> {
        // a user word typed whole is split into model words in its rows: its code's uses, gathered once (per-word scans were too slow)
        val coded by lazy(LazyThreadSafetyMode.NONE) {
            HashMap<String, Int>().also { days ->
                // by hand, not Map.merge: API floor below 24
                for ((key, stat) in state.stats) days[key.text] = maxOf(days[key.text] ?: Int.MIN_VALUE, stat.uses.lastDay)
            }
        }
        // learned words by when they became the user's: typing day first (a merge shifts another install's), then seq
        val learned = words.mapNotNull { w -> state.mine[w.text]?.takeIf { w.kind == UserWord.Kind.LEARNED }?.let { w.text to it } }
            .sortedWith(compareBy<Pair<String, Mine>>({ it.second.since }, { it.second.sinceSeq }))
            .mapIndexed { rank, (text, _) -> text to rank + 1L }.toMap()
        return words.mapIndexed { i, w ->
            // an empty count's last day is MIN_VALUE: unknown
            val day = (state.wordUses[w.text]?.values?.maxOfOrNull { it.lastDay } ?: coded[w.text])?.takeIf { it != Int.MIN_VALUE }
            val order = if (w.kind == UserWord.Kind.LEARNED) learned[w.text] ?: 0 else i.toLong()
            w.copy(lastUsed = lastUsed(state, w.text, day), idle = day?.let { state.day - it } ?: UserWord.NEVER, order = order)
        }
    }

    private fun lastUsed(state: LearnState, text: String, day: Int?): Int {
        day?.let { state.dates[it] }?.let { return it }
        return state.mine[text]?.lastUsedLocalDay?.takeIf { it != Int.MIN_VALUE && it > 0 } ?: UserWord.NEVER
    }
}
