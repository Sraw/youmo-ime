/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.lattice.UserWords
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches

/**
 * How a word reads along a syllable graph: each syllable shown, its end and id, and [slips] (place to key meant).
 * [slips] is empty exactly where the path has no [SyllableGraph.isSlip] edge; [SyllableGraph.withoutSlips] and
 * [SyllableGraph.readsSlipWithin] use the same test, so a change to one is a change to all three.
 */
internal class Reading(
    val shown: List<String>, val ends: List<Int>, val syllables: IntArray, val exact: Boolean = true, val slips: Map<Int, Char> = emptyMap(),
    val edges: IntArray = IntArray(0),
)

/** The syllables along [graph] from [from] to [to] spelling [word], exact readings first, or null; the decoder keeps only word ends, so they are found again here. */
internal fun reading(
    graph: SyllableGraph, from: Int, to: Int, word: Int, dictionary: PinyinDictionary, own: UserWords?,
    show: (edge: Int, syllable: Int) -> String,
): Reading? {
    val ends = ArrayList<Int>()
    val syllables = ArrayList<Int>()
    val edges = ArrayList<Int>()
    // read other than as typed: an imported word goes after exact ones
    var misread = 0
    val slips = HashMap<Int, Char>()

    val trie = Spelt(word, dictionary, own)

    fun walk(at: Int, node: Int): Boolean {
        if (at == to) return syllables.isNotEmpty() && trie.spells(node)
        for (e in graph.edges(at)) {
            if (graph.kind(e) == Kind.RAW || graph.to(e) > to) continue
            val m = graph.matches(e)
            val steps = trie.steps(node, m)
            for (k in 0 until steps.size step 2) {
                val i = steps[k]
                val child = steps[k + 1]
                val off = if (m.flags(i) and MISREAD != 0) 1 else 0
                val slip = graph.slips(e)
                val slipAt = if (slip == null) -1 else graph.from(e) + slip.at(i)
                ends += graph.to(e)
                syllables += m.syllable(i)
                edges += e
                misread += off
                if (slip != null) slips[slipAt] = slip.meant(i)
                if (walk(graph.to(e), child)) return true
                ends.removeAt(ends.size - 1)
                syllables.removeAt(syllables.size - 1)
                edges.removeAt(edges.size - 1)
                misread -= off
                if (slip != null) slips.remove(slipAt)
            }
        }
        return false
    }

    // shown only once found: most steps are dead ends, and a key alone has a score of syllables to try
    return if (walk(from, trie.root)) Reading(edges.indices.map { show(edges[it], syllables[it]) }, ends, syllables.toIntArray(), misread == 0, slips, edges.toIntArray()) else null
}

private const val MISREAD = SyllableMatches.FUZZY or SyllableMatches.TYPO or SyllableMatches.NEIGHBOUR

// the trie [word] is in: user's start-only, a merged dictionary's, the user's, else the dictionary's
private class Spelt(private val word: Int, private val dictionary: PinyinDictionary, private val own: UserWords?) {
    private val starts = own?.let { if (it.startOnly(word)) it.startWords else if (it.isMerged(word)) it.mergedWords else null }

    val root = starts?.root ?: own?.root ?: dictionary.root

    /**
     * Each index of [m] with a child at [node], then that child: exact readings first, ties in syllable order. Along
     * the dictionary's children where they are few (a key read alone stands for a score of syllables), else looked up.
     */
    fun steps(node: Int, m: SyllableMatches): IntArray {
        // a user's node may lie beyond the dictionary's nodes
        val children = if (starts != null || own != null) Int.MAX_VALUE else dictionary.childCount(node)
        // one number with PinyinDecoder, so the two never read apart
        if (children > PinyinDecoder.WALKED_PER_MATCH * m.size) {
            val out = IntArray(2 * m.size)
            var n = 0
            for (i in m.byFlags()) {
                val c = child(node, m.syllable(i))
                if (c < 0) continue
                out[n++] = i
                out[n++] = c
            }
            return out.copyOf(n)
        }
        val first = dictionary.firstChild(node)
        val out = IntArray(2 * children)
        var n = 0
        var mixed = false
        for (c in first until first + children) {
            val i = m.indexOf(dictionary.syllable(c))
            if (i < 0) continue
            if (n > 0 && m.flags(i) != m.flags(out[0])) mixed = true
            out[n++] = i
            out[n++] = c
        }
        if (!mixed) return out.copyOf(n)
        val order = (0 until n / 2).sortedBy { m.flags(out[2 * it]) }
        return IntArray(n) { out[2 * order[it / 2] + it % 2] }
    }

    fun child(node: Int, syllable: Int) = starts?.child(node, syllable) ?: own?.child(node, syllable) ?: dictionary.child(node, syllable)

    fun spells(node: Int): Boolean = when {
        starts != null -> (0 until starts.wordCount(node)).any { starts.word(node, it) == word }
        own != null -> (0 until own.wordCount(node)).any { own.word(node, it) == word }
        else -> (0 until dictionary.wordCount(node)).any { dictionary.word(node, it) == word }
    }
}
