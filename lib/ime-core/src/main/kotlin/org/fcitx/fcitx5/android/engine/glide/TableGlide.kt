/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.engine.data.runEnd
import org.fcitx.fcitx5.android.engine.table.TableDictionary

/**
 * A table's glide typing (码表滑行, 五笔 and the rest): the codes a finger's path across the keys
 * may be, a code a glide. The table's entries are walked in their codes' order as a trie, each key
 * held to the path by [GlidePath]; each code that fits is scored by its likeliest entry ([score],
 * the word model's) less what fitting it cost. A reading's keys are the code, its word that entry.
 * [version]: what changes [score] or [blocked] for words the user has no count of (added, forgotten,
 * blocked), and changes when a word gets its first count; [learned], a word the user has counts of,
 * asked as an entry is first scored for a [version]: its [score] is asked again each glide that
 * reaches it, as every word learned moves it. An approximation: an entry with no count whose
 * likeliest split has a counted word in it (你们 as 你 + 们) keeps its score till [version] changes.
 * A [blocked] entry is never a code's.
 */
class TableGlide(
    private val dictionary: TableDictionary,
    private val score: (String) -> Float,
    private val weights: GlidePath.Weights = PinyinGlide.WEIGHTS,
    private val version: () -> Any? = { null },
    private val learned: (String) -> Boolean = { false },
    private val blocked: (String) -> Boolean = { false },
) {

    // each entry's [score] (-∞ blocked), as first asked (NaN till then): the same every glide till
    // [version] changes, and a glide scores every entry of every code it fits; made at the first
    // glide, as most never glide a table. [counted]: each entry [learned] as its score was worked
    // out, scored again each glide, no walk of the user's counts a glide
    private var scores: FloatArray? = null
    private var counted: BooleanArray? = null
    private var scoredFor: Any? = null

    /** The search steps ([GlidePath.steps]) the last decode took: for ime-eval to measure. */
    var lastSteps = 0
        private set

    /** The [limit] likeliest codes of [path] over [layout], best first. */
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

        // the entries from [from] to [to], their codes alike for [depth] keys: those of no more first
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

        // the code of the entries [from] to [to], by the likeliest of them not blocked: none, no code
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
                val isBlocked = blocked(text)
                counted[i] = !isBlocked && learned(text)
                return (if (isBlocked) Float.NEGATIVE_INFINITY else score(text)).also { scores[i] = it }
            }
            return if (counted[i]) score(dictionary.text(i)) else s
        }
    }

    companion object {
        /** Keys a code has at most that a glide looks for: 五笔's four, 仓颉's five, a phrase's longer in some. */
        const val MAX_KEYS = 8
    }
}
