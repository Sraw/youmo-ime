/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind

/**
 * The syllables a full-pinyin sample was typed as: the cut of its input into whole syllables
 * spelt exactly, one per character of the expected text (`xian` for 先 as xian, for 西安 as
 * xi'an). Sets derived from a typed set start from it.
 */
object ExactReading {

    /** A syllable of the reading and where it was typed: [length] letters from [at], separators not counted. */
    data class Typed(val syllable: Int, val at: Int, val length: Int)

    private val segmenter = PinyinSegmenter(typos = false)

    /**
     * The one exact cut of [sample]'s input into syllables that [keep] allows, or null if there
     * is none or several.
     */
    fun of(sample: Sample, keep: (Int) -> Boolean = { true }): List<Typed>? {
        val count = sample.expected.codePointCount(0, sample.expected.length)
        return cut(segmenter.segment(sample.input), count, keep)
    }

    private fun cut(g: SyllableGraph, count: Int, keep: (Int) -> Boolean): List<Typed>? {
        val n = g.end
        // ways[at][k]: cuts of the input from at into k syllables, capped at 2; the last one found is kept
        val ways = Array(n + 1) { IntArray(count + 1) }
        val next = Array(n + 1) { IntArray(count + 1) }
        val syllable = Array(n + 1) { IntArray(count + 1) }
        ways[n][0] = 1
        for (at in n - 1 downTo g.start) {
            for ((to, s) in exactFrom(g, at, keep)) {
                for (k in 1..count) {
                    if (ways[to][k - 1] == 0) continue
                    ways[at][k] = minOf(2, ways[at][k] + ways[to][k - 1])
                    next[at][k] = to
                    syllable[at][k] = s
                }
            }
        }
        if (ways[g.start][count] != 1) return null
        val out = ArrayList<Typed>()
        var at = g.start
        for (k in count downTo 1) {
            val to = next[at][k]
            out += Typed(syllable[at][k], at, g.text(at, to).length)
            at = to
        }
        return out
    }

    /** Where each syllable spelt exactly from [at] leads, with the syllable, for those [keep] allows. */
    private fun exactFrom(g: SyllableGraph, at: Int, keep: (Int) -> Boolean): List<Pair<Int, Int>> =
        g.edges(at).filter { g.kind(it) == Kind.SYLLABLE }.flatMap { e ->
            val m = g.matches(e)
            (0 until m.size).filter { m.flags(it) == 0 && keep(m.syllable(it)) }.map { g.to(e) to m.syllable(it) }
        }
}
