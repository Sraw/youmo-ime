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

/** Words a user typed before the run (`text` or `text<TAB>reading` lines): 双拼 and 简拼 read a key alone only for those. */
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

    /** Counts [lines]' words in [user] once each, today; returns how many were counted. */
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
