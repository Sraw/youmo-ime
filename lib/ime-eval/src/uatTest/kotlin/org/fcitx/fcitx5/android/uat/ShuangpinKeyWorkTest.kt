/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.engine.lattice.Decoding
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a 双拼 key takes of the decoder on the app's data, counted ([PinyinDecoder.Work]), held under
 * a ceiling: no case of uat/. 简拼 in the middle reads every key alone too, and on a phone a key
 * went from 10 to 20 ms with nothing in the tests to say so (the test dictionary's words are few
 * and short; a time on an emulator on a fast host shows a millisecond in ten as none). The counts
 * are the same on every machine, so a change that makes a key read or score much more fails here.
 * And each key's reading, built on the key before, is what a decoder made afresh reads, on the
 * app's words: long ones, hundreds a reading, which the unit tests' dictionary has not.
 */
class ShuangpinKeyWorkTest {

    // 小鹤, as lib/ime-eval/data/shuangpin-xiaohe.tsv types its sentences, their syllables on end: [keys] keys
    // a line, in whole syllables, or the first whole and the rest by their initials
    private fun inputs(keys: Int, lines: Int, initials: Boolean): List<String> {
        val pairs = File("data/shuangpin-xiaohe.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }.filter { it[0].length == 2 * it[1].length }.flatMap { it[0].chunked(2) }
        val each = if (initials) keys - 1 else keys / 2
        return (0 until lines).map { n ->
            val line = pairs.subList(n * each, (n + 1) * each)
            if (initials) line[0] + line.drop(1).joinToString("") { it.take(1) } else line.joinToString("")
        }
    }

    // one array of the nodes' likelier words for every decoder: a decoder a key otherwise works them out again
    private fun decoder() = UatEngines.pinyinData.let { PinyinDecoder(it.dictionary, it.vocabulary, WordScorer.of(it.model), extensions = { extensions }) }

    private fun describe(d: Decoding) = (d.sentences + listOf(null) + d.words).map { c -> c?.let { "${it.text} ${it.end} ${it.score} ${it.words.toList()}" } }

    private class Taken(val arcs: Double, val steps: Double, val scored: Double, val first: Double) {
        override fun toString() = "a key: %.0f arcs, %.0f steps, %.0f scored, %.0f first words scored".format(arcs, steps, scored, first)
    }

    // types [inputs] a key at a time on one decoder: each key read as a decoder made afresh reads it, and what a key took
    private fun type(inputs: List<String>, abbreviations: Boolean = true): Taken {
        val segmenter = ShuangpinSegmenter(ShuangpinScheme.XIAOHE, abbreviations = abbreviations)
        val kept = decoder()
        var arcs = 0L
        var steps = 0L
        var scored = 0L
        var first = 0L
        var keys = 0
        for (input in inputs) {
            kept.reset()
            for (length in 1..input.length) {
                val graph = segmenter.segment(input.substring(0, length))
                val read = describe(kept.decode(graph))
                kept.work.let {
                    arcs += it.arcs
                    steps += it.steps
                    scored += it.scored
                    first += it.firstScored
                }
                keys++
                // the words between the last positions too, where a key changes them, in their order
                fun between(d: PinyinDecoder) = (maxOf(0, length - 6) until length).map { a -> d.arcsBetween(graph, a, length).map { "${it.text} ${it.score}" } }
                val keptBetween = between(kept)
                val fresh = decoder()
                assertEquals(input.substring(0, length), describe(fresh.decode(graph)), read)
                assertEquals(input.substring(0, length), between(fresh), keptBetween)
            }
        }
        return Taken(arcs.toDouble() / keys, steps.toDouble() / keys, scored.toDouble() / keys, first.toDouble() / keys)
    }

    @Test
    fun aKeyInWholeSyllablesTakesNoMoreThanItDid() {
        val taken = type(inputs(KEYS, LINES, initials = false))
        println("双拼 whole syllables, $taken")
        assertTrue("$taken", taken.steps <= WHOLE_STEPS && taken.scored <= WHOLE_SCORED && taken.arcs <= WHOLE_ARCS && taken.first <= WHOLE_FIRST)
    }

    @Test
    fun aKeyByInitialsTakesNoMoreThanItDid() {
        val taken = type(inputs(KEYS, LINES, initials = true))
        println("双拼 initials, $taken")
        assertTrue("$taken", taken.steps <= INITIALS_STEPS && taken.scored <= INITIALS_SCORED && taken.arcs <= INITIALS_ARCS && taken.first <= INITIALS_FIRST)
    }

    @Test
    fun withoutTheKeysReadAloneAKeyTakesAFractionOfIt() {
        // what 简拼 in the middle costs, said: the reading before it, kept for measuring against
        val taken = type(inputs(KEYS, LINES, initials = false), abbreviations = false)
        println("双拼 whole syllables, no key read alone, $taken")
        assertTrue("$taken", taken.steps <= OFF_STEPS && taken.scored <= OFF_SCORED && taken.arcs <= OFF_ARCS && taken.first <= OFF_FIRST)
    }

    private companion object {
        const val KEYS = 40
        const val LINES = 8

        // a quarter over what each was when measured (specs/engine.md, Performance, has the numbers)
        const val WHOLE_ARCS = 17000.0
        const val WHOLE_STEPS = 600.0
        const val WHOLE_SCORED = 8100.0
        const val INITIALS_ARCS = 19400.0
        const val INITIALS_STEPS = 750.0
        const val INITIALS_SCORED = 13400.0
        const val OFF_ARCS = 680.0
        const val OFF_STEPS = 22.0
        const val OFF_SCORED = 3600.0
        const val WHOLE_FIRST = 143.0
        const val INITIALS_FIRST = 159.0
        const val OFF_FIRST = 68.0

        val extensions: Array<IntArray?> by lazy { arrayOfNulls<IntArray>(UatEngines.pinyinData.dictionary.nodeCount) }
    }
}
