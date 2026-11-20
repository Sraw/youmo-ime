/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import org.fcitx.fcitx5.android.engine.data.pinyinDataOf
import org.fcitx.fcitx5.android.engine.lattice.UserWords
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Near
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Whether a word's reading goes over a slip ([Reading.slips]); a key read alone is 简拼, no slip. */
class ReadingSlipsTest {

    private val data = pinyinDataOf(Triple("下面", "xia mian", -3f), Triple("他", "ta", -2f), Triple("呀", "ya", -2f))

    private val ziranma = ShuangpinSegmenter(ShuangpinScheme.ZIRANMA, neighbours = true)

    private fun slipped(keys: String, word: String, touches: Array<Touch?>? = null): Boolean? {
        val graph = ziranma.segment(keys, null, touches)
        val id = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == word }
        return reading(graph, graph.start, graph.end, id, data.dictionary, null) { _, _ -> "" }?.slips?.isNotEmpty()
    }

    // 他 typed ta, and ya with y tapped at its left edge, towards t
    private val t = Touch(-0.45f, 0f, 40f, 56f, listOf(Near('r', -1f, 0f), Near('y', 1f, 0f)))
    private val y = Touch(-0.45f, 0f, 40f, 56f, listOf(Near('t', -1f, 0f), Near('u', 1f, 0f)))

    @Test
    fun aWordIsReadOverASlipOnlyWhereItsKeysReadAsTheirNeighbours() = assertRows(
        "a word read by its whole syllables: no slip" to { assertEquals(false, slipped("xwmm", "下面")) },
        "a word read by its initials alone: no slip" to { assertEquals(false, slipped("xm", "下面")) },
        "a key tapped towards its neighbour, read as typed: no slip" to { assertEquals(false, slipped("ta", "他", arrayOf(t, null))) },
        "a key tapped towards its neighbour, read as the neighbour: a slip" to { assertEquals(true, slipped("ya", "他", arrayOf(y, null))) },
    )

    // the dictionary as a user's trie, its nodes numbered from [base]
    private class AsOwn(private val d: PinyinDictionary, private val base: Int = 0) : UserWords {
        override val root get() = d.root + base
        override fun child(node: Int, syllable: Int) = d.child(node - base, syllable).let { if (it < 0) it else it + base }
        override fun childCount(node: Int) = d.childCount(node - base)
        override fun wordCount(node: Int) = d.wordCount(node - base)
        override fun word(node: Int, index: Int) = d.word(node - base, index)
        override fun text(word: Int) = ""
    }

    // counted at the user's node, the dictionary's children went out of bounds
    @Test
    fun aUsersWordWhoseNodesLieBeyondTheDictionarysIsReadOverKeysAlone() {
        val graph = ziranma.segment("xm", null, null)
        val id = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == "下面" }
        val beyond = AsOwn(data.dictionary, base = data.dictionary.nodeCount + 100)
        val own = reading(graph, graph.start, graph.end, id, data.dictionary, beyond) { _, _ -> "" }
        val dictionary = reading(graph, graph.start, graph.end, id, data.dictionary, null) { _, _ -> "" }
        assertTrue("the dictionary's", dictionary != null)
        assertTrue("the user's", own != null)
        assertEquals(dictionary?.syllables?.toList(), own?.syllables?.toList())
    }

    // walking few children reads what asking every match reads
    @Test
    fun aReadingOverKeysAloneIsTheSameWalkedAlongTheChildren() {
        val words = pinyinDataOf(
            Triple("下面", "xia mian", -3f), Triple("什么", "shen me", -2f), Triple("什么时候", "shen me shi hou", -3f), Triple("时候", "shi hou", -2f),
            Triple("你好", "ni hao", -2f), Triple("你", "ni", -2f), Triple("好", "hao", -2f), Triple("小明", "xiao ming", -3f),
        )
        val segmenters: List<Pair<(String) -> String, Segmenter>> = listOf({ k: String -> k } to ShuangpinSegmenter(ShuangpinScheme.ZIRANMA)) +
            listOf(NineKeyLetters.G18, NineKeyLetters.G14, NineKeyLetters.NINE_SP).map { l ->
                { k: String -> k.map(l::key).joinToString("") } to GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, l)
            }
        val asOwn = AsOwn(words.dictionary)
        val random = Random(7)
        val pieces = listOf("x", "m", "u", "f", "h", "n", "xw", "mm", "uf", "ui", "hb", "ni", "hk", "xc", "my")
        var read = 0
        for ((code, segmenter) in segmenters) repeat(200) {
            val keys = (1..random.nextInt(1, 5)).joinToString("") { pieces.random(random) }
            val graph: SyllableGraph = segmenter.segment(code(keys))
            if (!graph.hasAbbreviations) return@repeat
            for (from in graph.start until graph.end) for (to in from + 1..graph.end) for (w in 1 until words.vocabulary.size) {
                val walked = reading(graph, from, to, w, words.dictionary, null) { _, _ -> "" }
                val asked = reading(graph, from, to, w, words.dictionary, asOwn) { _, _ -> "" }
                // how exactly and over which edges too: aloneShape reads those
                fun shape(r: Reading?) = r?.let { Triple(it.syllables.toList(), it.ends, it.exact) to it.edges.toList() }
                assertEquals("$keys $from-$to $w", shape(asked), shape(walked))
                if (walked != null && (from until to).any { at -> graph.edges(at).any { graph.isAbbreviation(it) && graph.to(it) <= to } }) read++
            }
        }
        assertTrue("$read read over keys alone", read > 100)
    }
}
