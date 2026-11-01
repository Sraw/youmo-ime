/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/**
 * The reading of a run of characters, as the dictionary reads its words: the run is cut into the
 * fewest words the dictionary has, each read as the dictionary reads it likeliest. Read character
 * by character instead, 长期服用 came out zhang'qi'fu'yong and 行业标杆 xing'ye'biao'gan: in a
 * pack, words under readings no one types, pushing out what is typed (dev/TRAINING-PLAN.md 12.10).
 * Most readings weigh the same (a weight marks a polyphone's rarer one only), so ties are broken:
 * between two readings of a word, the one more of its characters are likeliest read as alone;
 * between two cuts, the likelier reading weights, then the words the model finds commoner.
 */
internal class Readings(private val data: PinyinData) {
    private class Reading(val syllables: IntArray, val weight: Float, val agreement: Int)

    /** A cut of a run's first characters into words: the greater, the better. */
    private class Cut(val words: Int, val weight: Float, val common: Float) : Comparable<Cut> {
        // fewer words first, then more weight, then commoner ones
        override fun compareTo(other: Cut) = compareValuesBy(this, other, { -it.words }, { it.weight }, { it.common })
    }

    private val best = HashMap<String, Reading>()
    private val alone = HashMap<Char, Pair<Int, Float>>()
    private var longest = 1

    init {
        val d = data.dictionary
        for (i in 0 until d.childCount(d.root)) {
            val node = d.firstChild(d.root) + i
            for (w in 0 until d.wordCount(node)) {
                val word = data.vocabulary.word(d.word(node, w))
                val weight = d.weight(node, w)
                if (word.length == 1 && (alone[word[0]]?.second ?: Float.NEGATIVE_INFINITY) < weight) alone[word[0]] = d.syllable(node) to weight
            }
        }
        val path = IntArray(MAX_WORD)
        fun visit(node: Int, depth: Int) {
            for (w in 0 until d.wordCount(node)) {
                val word = data.vocabulary.word(d.word(node, w))
                // a reading a character, as every word of the dictionary has
                if (word.length == depth) offer(word, path.copyOf(depth), d.weight(node, w))
            }
            if (depth == MAX_WORD) return
            for (i in 0 until d.childCount(node)) {
                val child = d.firstChild(node) + i
                path[depth] = d.syllable(child)
                visit(child, depth + 1)
            }
        }
        visit(d.root, 0)
    }

    private fun offer(word: String, syllables: IntArray, weight: Float) {
        val agreement = word.indices.count { alone[word[it]]?.first == syllables[it] }
        val held = best[word]
        val better = held == null || compareValuesBy(Reading(syllables, weight, agreement), held, { it.weight }, { it.agreement }) > 0
        if (better) {
            best[word] = Reading(syllables, weight, agreement)
            if (word.length > longest) longest = word.length
        }
    }

    private fun commonness(word: String): Float {
        val id = data.wordIndex.find(word)
        return if (id >= 0 && id < data.model.vocabularySize) data.model.score(id) else UNKNOWN
    }

    /** `pin'yin` for [text], or null if a character of it has no reading. */
    fun of(text: String): String? {
        // the best cut to each position, and where its last word starts
        val cuts = arrayOfNulls<Cut>(text.length + 1)
        val from = IntArray(text.length + 1)
        cuts[0] = Cut(0, 0f, 0f)
        for (end in 1..text.length) {
            for (start in maxOf(0, end - longest) until end) {
                val part = text.substring(start, end)
                val reading = best[part]
                val before = cuts[start]
                if (reading == null || before == null) continue
                val cut = Cut(before.words + 1, before.weight + reading.weight, before.common + commonness(part))
                val held = cuts[end]
                if (held == null || cut > held) {
                    cuts[end] = cut
                    from[end] = start
                }
            }
        }
        if (cuts[text.length] == null) return null
        val syllables = ArrayList<Int>()
        var end = text.length
        while (end > 0) {
            syllables.addAll(0, best.getValue(text.substring(from[end], end)).syllables.asList())
            end = from[end]
        }
        return Syllables.apostrophed(syllables.toIntArray())
    }

    private companion object {
        // the dictionary's words are seldom longer; a longer one is read in parts
        const val MAX_WORD = 8
        // a word the model has not: rarer than any it has
        const val UNKNOWN = -99f
    }
}
