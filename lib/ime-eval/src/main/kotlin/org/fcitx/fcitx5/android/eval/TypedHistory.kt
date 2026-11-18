/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.user.HabitCounts
import org.fcitx.fcitx5.android.engine.user.HistoryLines
import org.fcitx.fcitx5.android.engine.user.UserModel

/**
 * Words a user typed before the run, for what hangs on them: 双拼 reads a key alone, 简拼 in the
 * middle, only for words typed or chosen ([org.fcitx.fcitx5.android.engine.lattice.UserWords.typed]),
 * so a run with no history measures that reading off. A history is lines of `text` or
 * `text<TAB>reading` (syllables apart by spaces); a text alone is typed at each reading the
 * dictionary has of it.
 */
object TypedHistory {

    /** The [n] likeliest words of [data]'s dictionary by the model's score of each alone and its reading's weight, as a history's lines. */
    fun likeliest(data: PinyinData, n: Int): List<String> {
        val scorer = WordScorer.of(data.model)
        val scored = ArrayList<Pair<Float, String>>()
        HistoryLines.forEachWord(data) { word, reading, weight ->
            scored += scorer.score(NO_WORD, NO_WORD, word) + weight to "${data.vocabulary.word(word)}\t$reading"
        }
        // by the line too, so the same on every machine
        return scored.sortedWith(compareByDescending<Pair<Float, String>> { it.first }.thenBy { it.second }).take(n).map { it.second }
    }

    /**
     * Counts [lines]' words in [user] as the learning record counts a word committed whole, once
     * each, today.
     * @return how many were counted: a text the dictionary lacks is none
     */
    fun count(user: UserModel, data: PinyinData, lines: List<String>): Int {
        val log = LearnLog(null).apply { open() }
        val counts = HabitCounts(user).also { it.attach(log.state) }
        log.listener = {
            counts.apply(log.state, it)
            counts.settle(log.state)
        }
        val rows = HistoryLines.rows(user, data, lines)
        rows.forEach { log.record(listOf(it)) }
        return rows.size
    }
}
