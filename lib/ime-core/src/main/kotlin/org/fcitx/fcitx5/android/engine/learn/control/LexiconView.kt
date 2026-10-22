/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.Mine

/**
 * How a list of the user dictionary page is shown (U-80): as it comes, or the words that became
 * the user's most recently first (「最近学到」); all of them, or only those not used for long
 * (「很久没用」), to clear out together.
 */
object LexiconView {
    /**
     * Typing days unused after which a word is 「很久没用」: two months of typing. Typing days, not
     * calendar ones: a month away from the keyboard makes no word old (§1: 「回来一切照旧」).
     */
    const val UNUSED_DAYS = 60

    /** [words] as asked: [recentFirst] by [UserWord.order], latest first; [unusedOnly], those not used for [UNUSED_DAYS] typing days. */
    fun arrange(words: List<UserWord>, recentFirst: Boolean, unusedOnly: Boolean): List<UserWord> {
        val kept = if (unusedOnly) words.filter(::unused) else words
        return if (recentFirst) kept.sortedByDescending { it.order } else kept
    }

    /**
     * Whether [word] was last used [UNUSED_DAYS] typing days ago or more. Not one whose last use is
     * not known (used before this build kept days, added and never typed): 「很久没用」 is read as a
     * fact, and the list is there to delete from (review-E-ui M2).
     */
    fun unused(word: UserWord) = word.idle != UserWord.NEVER && word.idle >= UNUSED_DAYS

    /**
     * [words] with when each was last used, in [state]: the local day shown ([UserWord.lastUsed]),
     * the typing days since ([UserWord.idle]), [UserWord.NEVER] for either not known; and the order
     * 「最近学到」 sorts by, a learned word's becoming the user's, else the list's own.
     */
    fun dated(state: LearnState, words: List<UserWord>): List<UserWord> {
        // a word of the user's typed whole has its rows split into the words the model knows: its code's
        // uses, gathered once for the list (a scan of the stats a word was too slow: review-E-engine S8)
        val coded by lazy(LazyThreadSafetyMode.NONE) {
            HashMap<String, Int>().also { days ->
                // no Map.merge below API 24
                for ((key, stat) in state.stats) days[key.text] = maxOf(days[key.text] ?: Int.MIN_VALUE, stat.uses.lastDay)
            }
        }
        // learned words by when they became the user's: the typing day first (a merge moves another
        // install's onto the phone's), then the seq, whose count is each install's own
        val learned = words.mapNotNull { w -> state.mine[w.text]?.takeIf { w.kind == UserWord.Kind.LEARNED }?.let { w.text to it } }
            .sortedWith(compareBy<Pair<String, Mine>>({ it.second.since }, { it.second.sinceSeq }))
            .mapIndexed { rank, (text, _) -> text to rank + 1L }.toMap()
        return words.mapIndexed { i, w ->
            // an empty count's last day is MIN_VALUE: no day known, as none at all (verify-E F3)
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
