/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/**
 * Pinyin glide typing (拼音滑行): the words a finger's path across the letters may be, a word's
 * syllables glided in one go. The words are walked as the dictionary's syllable trie, each
 * syllable's letters held to the path by [GlidePath]; each reading with a word is scored by its
 * likeliest word (the model's unigram, or its characters' where the model lacks it; a rarer
 * reading's weight added) less what fitting the path
 * cost. A word a glide: chained in one, the words' readings ran to thousands and the search to seconds.
 */
class PinyinGlide(private val data: PinyinData, private val weights: GlidePath.Weights = WEIGHTS) {

    /** A reading of the path: its syllables apart by [SEPARATOR], its likeliest [word] and the score. */
    class Reading(val pinyin: String, val word: String, val score: Float)

    // each node's likeliest word and its score, as first asked (NaN till then): a node's words are in no such order
    private val likeliest = FloatArray(data.dictionary.nodeCount) { Float.NaN }
    private val likeliestWord = IntArray(data.dictionary.nodeCount)

    private fun likeliest(node: Int): Float {
        val known = likeliest[node]
        if (!known.isNaN()) return known
        val d = data.dictionary
        var best = Float.NEGATIVE_INFINITY
        for (i in 0 until d.wordCount(node)) {
            val score = score(d.word(node, i)) + d.weight(node, i)
            if (score > best) {
                best = score
                likeliestWord[node] = d.word(node, i)
            }
        }
        likeliest[node] = best
        return best
    }

    // the model's, or its characters' one after another where likelier, as the pinyin session's best
    // path takes them: 你好 is likelier as 你 then 好 (-5.2) than as a word (-6.5), the model's
    // text cut 你好 apart mostly
    private fun score(word: Int): Float {
        val model = data.model
        val own = if (word < model.vocabularySize) model.score(word) else model.unknownScore
        val text = data.vocabulary.word(word)
        if (text.length < 2) return own
        var prev = NO_WORD
        var chars = 0f
        for (c in text) {
            val id = data.wordIndex.find(c.toString())
            chars += if (id == NO_WORD) model.unknownScore else model.score(prev, id)
            prev = id
        }
        return maxOf(own, chars)
    }

    /** The [limit] likeliest readings of [path] over [layout], best first, each once. */
    fun decode(path: FloatArray, layout: GlideLayout, limit: Int): List<Reading> {
        if (limit <= 0) return emptyList()
        val glide = GlidePath(path, layout, weights, MAX_LETTERS)
        if (glide.n == 0) return emptyList()
        return Search(glide, limit).run()
    }

    private inner class Search(private val glide: GlidePath, private val limit: Int) {
        private val dictionary = data.dictionary
        private val syllables = IntArray(MAX_LETTERS)

        // each reading with a word: the trie spells each once
        private val found = ArrayList<Reading>()

        fun run(): List<Reading> = found.sortedByDescending { it.score }.take(limit)

        // the children of [node], [depth] letters and [count] syllables in
        private fun visit(node: Int, depth: Int, count: Int) {
            val first = dictionary.firstChild(node)
            for (child in first until first + dictionary.childCount(node)) {
                val s = dictionary.syllable(child)
                val d = stepped(Syllables.spelling(s), depth) ?: continue
                syllables[count] = s
                if (dictionary.wordCount(child) > 0) glide.cost(d)?.let { found += reading(child, count + 1, likeliest(child) - it) }
                if (d < MAX_LETTERS) visit(child, d, count + 1)
            }
        }

        init {
            visit(dictionary.root, 0, 0)
        }

        // the letters of [spelling] stepped from [depth]: where they end, or null where the path does not pass them
        private fun stepped(spelling: String, depth: Int): Int? {
            var d = depth
            for (ch in spelling) {
                if (d >= MAX_LETTERS || !glide.step(d, ch)) return null
                d++
            }
            return d
        }

        private fun reading(node: Int, count: Int, score: Float) = Reading(
            (0 until count).joinToString(SEPARATOR.toString()) { Syllables.spelling(syllables[it]) },
            data.vocabulary.word(likeliestWord[node]),
            score,
        )
    }

    companion object {
        /** Letters a glide spells at most. */
        const val MAX_LETTERS = 30

        /**
         * Pinyin's weights: the path weighed more against the model than English's (GlidePath.Weights),
         * a word's unigram lower in a vocabulary of many more (ime-eval pinyin-glide).
         */
        val WEIGHTS = GlidePath.Weights(shape = 5f, proximity = 6.25f, corner = 1.5f)

        /** Between syllables: the pinyin session reads it as their boundary (SyllableGraph.SEPARATOR). */
        const val SEPARATOR = '\''
    }
}
