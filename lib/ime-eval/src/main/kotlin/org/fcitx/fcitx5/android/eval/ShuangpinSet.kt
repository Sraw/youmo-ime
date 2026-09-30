/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind

/**
 * Types the full-pinyin samples of an evaluation set in 双拼, so both are measured on the same
 * sentences. The reading typed is the cut of the input into whole syllables spelt exactly, one
 * per character of the expected text (`xian` for 先 as xian, for 西安 as xi'an): only samples
 * with one such cut are kept, which leaves out abbreviations, unfinished input and typos.
 */
object ShuangpinSet {

    val SCHEMES = mapOf(
        "ziranma" to ShuangpinScheme.ZIRANMA,
        "ms" to ShuangpinScheme.MICROSOFT,
        "ziguang" to ShuangpinScheme.ZIGUANG,
        "abc" to ShuangpinScheme.ABC,
        "zhongwenzhixing" to ShuangpinScheme.ZHONGWENZHIXING,
        "pinyinjiajia" to ShuangpinScheme.PINYINJIAJIA,
        "xiaohe" to ShuangpinScheme.XIAOHE,
        "gb" to ShuangpinScheme.GB,
    )

    private val segmenter = PinyinSegmenter(typos = false)

    fun convert(samples: List<Sample>, scheme: ShuangpinScheme): List<Sample> = samples.mapNotNull { sample ->
        val characters = sample.expected.codePointCount(0, sample.expected.length)
        val codes = reading(segmenter.segment(sample.input), characters) { typed(scheme, it) } ?: return@mapNotNull null
        sample.copy(input = codes.joinToString(""))
    }

    /** How [syllable] is typed in [scheme]: a Latin letter as itself; null for 嗯 and the like, which it has no keys for. */
    private fun typed(scheme: ShuangpinScheme, syllable: Int): String? =
        scheme.encode(syllable) ?: Syllables.spelling(syllable).takeIf { it[0].isUpperCase() }

    /**
     * The [code]s of the one exact cut of [g] into [count] syllables that all have one, or null if
     * there is none or several.
     */
    private fun reading(g: SyllableGraph, count: Int, code: (Int) -> String?): List<String>? {
        val n = g.end
        // ways[at][k]: cuts of the input from at into k syllables, capped at 2; the last one found is kept
        val ways = Array(n + 1) { IntArray(count + 1) }
        val next = Array(n + 1) { IntArray(count + 1) }
        val syllable = Array(n + 1) { IntArray(count + 1) }
        ways[n][0] = 1
        for (at in n - 1 downTo g.start) {
            for ((to, s) in exactFrom(g, at, code)) {
                for (k in 1..count) {
                    if (ways[to][k - 1] == 0) continue
                    ways[at][k] = minOf(2, ways[at][k] + ways[to][k - 1])
                    next[at][k] = to
                    syllable[at][k] = s
                }
            }
        }
        if (ways[g.start][count] != 1) return null
        val out = ArrayList<String>()
        var at = g.start
        for (k in count downTo 1) {
            out += code(syllable[at][k])!!
            at = next[at][k]
        }
        return out
    }

    /** Where each syllable spelt exactly from [at] leads, with the syllable, for those with a [code]. */
    private fun exactFrom(g: SyllableGraph, at: Int, code: (Int) -> String?): List<Pair<Int, Int>> =
        g.edges(at).filter { g.kind(it) == Kind.SYLLABLE }.flatMap { e ->
            val m = g.matches(e)
            (0 until m.size).filter { m.flags(it) == 0 && code(m.syllable(it)) != null }.map { g.to(e) to m.syllable(it) }
        }
}
