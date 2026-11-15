/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.HabitCounts
import org.fcitx.fcitx5.android.engine.user.UserModel

/**
 * Words a user typed before the run, for what hangs on them: 双拼 reads a key alone, 简拼 in the
 * middle, only for words typed or chosen ([org.fcitx.fcitx5.android.engine.lattice.UserWords.typed]),
 * so a run with no history measures that reading off. A history is lines of `text` or
 * `text<TAB>reading` (syllables apart by spaces); a text alone is typed at each reading the
 * dictionary has of it.
 */
object TypedHistory {

    // each word of the dictionary: its id, its reading and its weight there
    private fun forEachWord(data: PinyinData, visit: (word: Int, reading: String, weight: Float) -> Unit) {
        val dictionary = data.dictionary
        val path = ArrayList<String>()
        fun walk(node: Int) {
            if (path.isNotEmpty()) {
                val reading = path.joinToString(" ")
                for (w in 0 until dictionary.wordCount(node)) visit(dictionary.word(node, w), reading, dictionary.weight(node, w))
            }
            val first = dictionary.firstChild(node)
            for (child in first until first + dictionary.childCount(node)) {
                path += Syllables.spelling(dictionary.syllable(child))
                walk(child)
                path.removeAt(path.size - 1)
            }
        }
        walk(dictionary.root)
    }

    /** The [n] likeliest words of [data]'s dictionary by the model's score of each alone and its reading's weight, as a history's lines. */
    fun likeliest(data: PinyinData, n: Int): List<String> {
        val scorer = WordScorer.of(data.model)
        val scored = ArrayList<Pair<Float, String>>()
        forEachWord(data) { word, reading, weight ->
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
        val wanted = lines.filter { it.isNotBlank() }.map { it.split('\t') }
        val readings = HashMap<String, ArrayList<String>>()
        wanted.filter { it.size == 1 }.forEach { readings[it[0]] = ArrayList(1) }
        if (readings.isNotEmpty()) forEachWord(data) { word, reading, _ -> readings[data.vocabulary.word(word)]?.add(reading) }
        val log = LearnLog(null).apply { open() }
        val counts = HabitCounts(user).also { it.attach(log.state) }
        log.listener = {
            counts.apply(log.state, it)
            counts.settle(log.state)
        }
        var counted = 0
        for (line in wanted) {
            for (reading in if (line.size > 1) listOf(line[1]) else readings.getValue(line[0])) {
                // a text the dictionary lacks so read is none of its words
                if (HabitCounts.entry(line[0], reading)?.let(user::known) ?: NO_WORD == NO_WORD) continue
                log.record(listOf(Row(Row.PICK or Row.WHOLE, Code(Kinds.FULL, reading.replace(" ", "")), "", Words(listOf(Word(line[0], reading))), 'A', Row.DICTIONARY, "")))
                counted++
            }
        }
        return counted
    }
}
