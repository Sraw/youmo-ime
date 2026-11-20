/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.engine.data.runEnd
import org.fcitx.fcitx5.android.engine.table.TableDictionary

/**
 * Table glide typing (五笔 etc.): a code per glide, walking the table's entries in code order as a trie. An
 * approximation: an uncounted entry whose likeliest split has a counted word (你们 as 你 + 们) keeps its score
 * until [version] changes. [blocked] is asked with the code, as a table blocks under a code.
 */
class TableGlide(
    private val dictionary: TableDictionary,
    private val score: (String) -> Float,
    private val weights: GlidePath.Weights = PinyinGlide.WEIGHTS,
    private val version: () -> Any? = { null },
    private val learned: (String) -> Boolean = { false },
    private val blocked: (code: String, text: String) -> Boolean = { _, _ -> false },
) {

    // each entry's score as first asked, kept till [version] changes; built at the first glide, as most never
    // glide a table. [counted] ones are rescored each glide
    private var scores: FloatArray? = null
    private var counted: BooleanArray? = null
    private var scoredFor: Any? = null

    var lastSteps = 0
        private set

    fun decode(path: FloatArray, layout: GlideLayout, limit: Int): List<GlideReading> {
        if (limit <= 0 || dictionary.size == 0) return emptyList()
        val glide = GlidePath(path, layout, weights, MAX_KEYS)
        if (glide.points == 0) return emptyList()
        val now = version()
        val known = scores
        if (known == null) {
            scores = FloatArray(dictionary.size) { Float.NaN }
            counted = BooleanArray(dictionary.size)
        } else if (now != scoredFor) {
            known.fill(Float.NaN)
        }
        scoredFor = now
        return Search(glide).run().also { lastSteps = glide.steps }.sortedByDescending { it.score }.take(limit)
    }

    private inner class Search(private val glide: GlidePath) {
        private val found = ArrayList<GlideReading>()
        private val scores = checkNotNull(this@TableGlide.scores)
        private val counted = checkNotNull(this@TableGlide.counted)

        fun run(): List<GlideReading> {
            visit(0, 0, dictionary.size)
            return found
        }

        private fun visit(depth: Int, from: Int, to: Int) {
            var pos = from
            while (pos < to && dictionary.codeLength(pos) == depth) pos++
            if (depth > 0 && pos > from) glide.cost(depth)?.let { cost -> keep(from, pos, cost) }
            if (depth >= MAX_KEYS) return
            while (pos < to) {
                val c = dictionary.codeKey(pos, depth)
                val end = runEnd(pos, to, c) { dictionary.codeKey(it, depth) }
                if (glide.step(depth, c)) visit(depth + 1, pos, end)
                pos = end
            }
        }

        private fun keep(from: Int, to: Int, cost: Float) {
            var best = from
            var bestScore = Float.NEGATIVE_INFINITY
            for (i in from until to) {
                val s = scored(i)
                if (s > bestScore) {
                    bestScore = s
                    best = i
                }
            }
            if (bestScore == Float.NEGATIVE_INFINITY) return
            found += GlideReading(dictionary.code(best), dictionary.text(best), bestScore - cost)
        }

        private fun scored(i: Int): Float {
            val s = scores[i]
            if (s.isNaN()) {
                val text = dictionary.text(i)
                val isBlocked = blocked(dictionary.code(i), text)
                counted[i] = !isBlocked && learned(text)
                return (if (isBlocked) Float.NEGATIVE_INFINITY else score(text)).also { scores[i] = it }
            }
            return if (counted[i]) score(dictionary.text(i)) else s
        }
    }

    companion object {
        /** 五笔's four, 仓颉's five, longer for some phrases. */
        const val MAX_KEYS = 8
    }
}
