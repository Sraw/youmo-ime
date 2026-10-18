/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.engine.table.TableDictionary

/**
 * A table's glide typing (码表滑行, 五笔 and the rest): the codes a finger's path across the keys
 * may be, a code a glide. The table's entries are walked in their codes' order as a trie, each key
 * held to the path by [GlidePath]; each code that fits is scored by its likeliest entry ([score],
 * the word model's) less what fitting it cost. A reading's keys are the code, its word that entry.
 */
class TableGlide(
    private val dictionary: TableDictionary,
    private val score: (String) -> Float,
    private val weights: GlidePath.Weights = PinyinGlide.WEIGHTS,
) {

    /** The [limit] likeliest codes of [path] over [layout], best first. */
    fun decode(path: FloatArray, layout: GlideLayout, limit: Int): List<GlideReading> {
        if (limit <= 0 || dictionary.size == 0) return emptyList()
        val glide = GlidePath(path, layout, weights, MAX_KEYS)
        if (glide.n == 0) return emptyList()
        return Search(glide).run().sortedByDescending { it.score }.take(limit)
    }

    private inner class Search(private val glide: GlidePath) {
        private val found = ArrayList<GlideReading>()

        fun run(): List<GlideReading> {
            visit(0, 0, dictionary.size)
            return found
        }

        // the entries from [from] to [to], their codes alike for [depth] keys: those of no more first
        private fun visit(depth: Int, from: Int, to: Int) {
            var pos = from
            while (pos < to && dictionary.codeLength(pos) == depth) pos++
            if (depth > 0 && pos > from) glide.cost(depth)?.let { cost -> keep(from, pos, cost) }
            if (depth >= MAX_KEYS) return
            while (pos < to) {
                val c = dictionary.codeKey(pos, depth)
                val end = runEnd(pos, to, depth, c)
                if (glide.step(depth, c)) visit(depth + 1, pos, end)
                pos = end
            }
        }

        // the code of the entries [from] to [to], by the likeliest of them
        private fun keep(from: Int, to: Int, cost: Float) {
            var best = from
            var bestScore = Float.NEGATIVE_INFINITY
            for (i in from until to) {
                val s = score(dictionary.text(i))
                if (s > bestScore) {
                    bestScore = s
                    best = i
                }
            }
            found += GlideReading(dictionary.code(best), dictionary.text(best), bestScore - cost)
        }

        private fun runEnd(from: Int, to: Int, depth: Int, c: Char): Int {
            var lo = from
            var hi = to
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (dictionary.codeKey(mid, depth) <= c) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }

    companion object {
        /** Keys a code has at most that a glide looks for: 五笔's four, 仓颉's five, a phrase's longer in some. */
        const val MAX_KEYS = 8
    }
}
