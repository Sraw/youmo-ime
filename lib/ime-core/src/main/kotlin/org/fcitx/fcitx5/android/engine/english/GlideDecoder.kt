/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.data.runEnd
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.glide.GlidePath

/** Glide typing for English: [EnglishDecoder]'s key trie, each key held to the path by [GlidePath]. */
class GlideDecoder(
    private val data: EnglishData,
    private val score: (context: Long, word: Int) -> Float = data.model::scoreAfter,
    private val weights: GlidePath.Weights = GlidePath.Weights(),
) {

    class Suggestion(val word: Int, val lm: Float, val cost: Float) {
        val score: Float get() = lm - cost
    }

    var lastSteps = 0
        private set

    fun decode(path: FloatArray, layout: GlideLayout, context: Long, limit: Int): List<Suggestion> {
        if (limit <= 0) return emptyList()
        val glide = GlidePath(path, layout, weights, EnglishDecoder.MAX_KEYS)
        if (glide.points == 0) return emptyList()
        return Search(glide, context, limit).run().also { lastSteps = glide.steps }
    }

    private inner class Search(private val glide: GlidePath, private val context: Long, private val limit: Int) {
        // the [limit] best so far: a scribbled path fits thousands of prefixes
        private val best = ArrayList<Suggestion>()

        fun run(): List<Suggestion> {
            visit(0, 0, data.keyed)
            return best
        }

        private fun visit(depth: Int, from: Int, to: Int) {
            var pos = from
            while (pos < to && data.keyLength(pos) == depth) {
                if (depth > 0) glide.cost(depth)?.let { keep(data.keyedWord(pos), it) }
                pos++
            }
            if (depth >= EnglishDecoder.MAX_KEYS) return
            while (pos < to) {
                val c = data.keyChar(pos, depth)
                val end = runEnd(pos, to, c) { data.keyChar(it, depth) }
                if (glide.step(depth, c)) visit(depth + 1, pos, end)
                pos = end
            }
        }

        private fun keep(word: Int, cost: Float) {
            val s = Suggestion(word, score(context, word), cost)
            if (best.size == limit && !outranks(s, best.last())) return
            val at = best.binarySearch { b -> if (outranks(s, b)) 1 else -1 }.let { -(it + 1) }
            best.add(at, s)
            if (best.size > limit) best.removeAt(best.size - 1)
        }

        private fun outranks(s: Suggestion, b: Suggestion) = s.score > b.score || s.score == b.score && s.word < b.word
    }
}
