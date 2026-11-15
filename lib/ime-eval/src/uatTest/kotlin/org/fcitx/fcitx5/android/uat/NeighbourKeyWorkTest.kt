/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.engine.lattice.Decoding
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.eval.Sample
import org.fcitx.fcitx5.android.eval.TouchSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What reading a key by where it was tapped (061-neighbour-correction §7.2) takes of the decoder on
 * the app's data, counted ([PinyinDecoder.Work]): clean taps, no key slipped, as most are. Each kind
 * of input with the slips read takes at most a tenth more than without (on a phone 1.5 ms of a 双拼
 * key's 14), full pinyin against its 0.6.0 rule (a tap with no place), which it read before. And a
 * key's reading kept from the key before is what a decoder made afresh reads, the slips' costs in it.
 */
class NeighbourKeyWorkTest {

    private val chat by lazy {
        File("data/pinyin-chat.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t')[0] }.filter { it.length in KEYS / 2..KEYS && it.all { c -> c in 'a'..'z' } }.take(LINES)
    }

    // 小鹤 as ShuangpinKeyWorkTest types it: [KEYS] keys a line, whole syllables or the first and initials after
    private fun xiaohe(initials: Boolean): List<String> {
        val pairs = File("data/shuangpin-xiaohe.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }.filter { it[0].length == 2 * it[1].length }.flatMap { it[0].chunked(2) }
        val each = if (initials) KEYS - 1 else KEYS / 2
        return (0 until LINES).map { n ->
            val line = pairs.subList(n * each, (n + 1) * each)
            if (initials) line[0] + line.drop(1).joinToString("") { it.take(1) } else line.joinToString("")
        }
    }

    private fun decoder() = UatEngines.pinyinData.let { PinyinDecoder(it.dictionary, it.vocabulary, WordScorer.of(it.model), extensions = { extensions }) }

    private fun describe(d: Decoding) = (d.sentences + listOf(null) + d.words).map { c -> c?.let { "${it.text} ${it.end} ${it.score} ${it.words.toList()}" } }

    // each of [inputs] tapped cleanly on [board], a key at a time on one decoder: what a key took, arcs and scored words
    private fun type(inputs: List<String>, board: String, segment: (String, Array<Touch?>?) -> SyllableGraph, placed: Boolean): Pair<Double, Double> {
        val tapped = TouchSim.convert(inputs.map { Sample(it, it, "") }, "$board:s0.2:clean")
        assertEquals(inputs.size, tapped.size)
        val kept = decoder()
        var arcs = 0L
        var scored = 0L
        var keys = 0
        for ((n, s) in tapped.withIndex()) {
            assertEquals(inputs[n], s.input)
            kept.reset()
            for (length in 1..s.input.length) {
                val touches = if (placed) s.touches!!.subList(0, length).toTypedArray() else null
                val graph = segment(s.input.substring(0, length), touches)
                val read = describe(kept.decode(graph))
                arcs += kept.work.arcs
                scored += kept.work.scored
                keys++
                assertEquals(s.input.substring(0, length), describe(decoder().decode(graph)), read)
            }
        }
        return arcs.toDouble() / keys to scored.toDouble() / keys
    }

    private fun within(what: String, on: Pair<Double, Double>, off: Pair<Double, Double>) {
        println("$what: a key %.0f arcs, %.0f scored with the slips read; %.0f, %.0f without".format(on.first, on.second, off.first, off.second))
        assertTrue("$what arcs ${on.first} of ${off.first}", on.first <= off.first * AT_MOST)
        assertTrue("$what scored ${on.second} of ${off.second}", on.second <= off.second * AT_MOST)
    }

    @Test
    fun fullPinyinTakesAtMostATenthMore() {
        val s = PinyinSegmenter(neighbours = true)
        within("full pinyin", type(chat, "qwerty", { i, t -> s.segment(i, null, t) }, placed = true), type(chat, "qwerty", { i, t -> s.segment(i, null, t) }, placed = false))
    }

    @Test
    fun xiaoheTakesAtMostATenthMore() {
        val on = ShuangpinSegmenter(ShuangpinScheme.XIAOHE, neighbours = true)
        val off = ShuangpinSegmenter(ShuangpinScheme.XIAOHE)
        for (initials in listOf(false, true)) {
            val inputs = xiaohe(initials)
            within("小鹤${if (initials) " initials" else ""}", type(inputs, "qwerty", { i, t -> on.segment(i, null, t) }, placed = true), type(inputs, "qwerty", { i, t -> off.segment(i, null, t) }, placed = false))
        }
    }

    @Test
    fun theNineFourteenAndEighteenKeysTakeAtMostATenthMore() {
        for ((letters, board) in listOf(NineKeyLetters.STANDARD to "nine", NineKeyLetters.G14 to "k14", NineKeyLetters.G18 to "k18")) {
            val inputs = chat.map { line -> line.map { letters.key(it) }.joinToString("") }
            val on = T9Segmenter(abbreviations = false, letters = letters, neighbours = true)
            val off = T9Segmenter(abbreviations = false, letters = letters)
            within(board, type(inputs, board, { i, t -> on.segment(i, null, t) }, placed = true), type(inputs, board, { i, t -> off.segment(i, null, t) }, placed = false))
        }
    }

    private companion object {
        const val KEYS = 40
        const val LINES = 8
        // 1.5 ms of a 双拼 key's 14.1 on the 6T (061-neighbour-correction §7.1)
        const val AT_MOST = 1.10

        val extensions: Array<IntArray?> by lazy { arrayOfNulls<IntArray>(UatEngines.pinyinData.dictionary.nodeCount) }
    }
}
