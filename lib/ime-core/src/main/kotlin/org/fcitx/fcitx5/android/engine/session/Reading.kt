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
    val shown = ArrayList<String>()
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
            // exact readings first; a key read alone is walked along the trie's children
            val order = if (graph.isAbbreviation(e)) trie.matched(node, m) else m.byFlags()
            for (i in order) {
                val child = trie.child(node, m.syllable(i))
                if (child < 0) continue
                val off = if (m.flags(i) and MISREAD != 0) 1 else 0
                val slip = graph.slips(e)
                val slipAt = if (slip == null) -1 else graph.from(e) + slip.at(i)
                shown += show(e, m.syllable(i))
                ends += graph.to(e)
                syllables += m.syllable(i)
                edges += e
                misread += off
                if (slip != null) slips[slipAt] = slip.meant(i)
                if (walk(graph.to(e), child)) return true
                shown.removeAt(shown.size - 1)
                ends.removeAt(ends.size - 1)
                syllables.removeAt(syllables.size - 1)
                edges.removeAt(edges.size - 1)
                misread -= off
                if (slip != null) slips.remove(slipAt)
            }
        }
        return false
    }

    return if (walk(from, trie.root)) Reading(shown, ends, syllables.toIntArray(), misread == 0, slips, edges.toIntArray()) else null
}

private const val MISREAD = SyllableMatches.FUZZY or SyllableMatches.TYPO or SyllableMatches.NEIGHBOUR

// the trie [word] is in: user's start-only, a merged dictionary's, the user's, else the dictionary's
private class Spelt(private val word: Int, private val dictionary: PinyinDictionary, private val own: UserWords?) {
    private val starts = own?.let { if (it.startOnly(word)) it.startWords else if (it.isMerged(word)) it.mergedWords else null }

    val root = starts?.root ?: own?.root ?: dictionary.root

    /** The indexes of [m] with a child at [node], exact first: by the dictionary's children where they are few; else every index. */
    fun matched(node: Int, m: SyllableMatches): IntArray {
        // a user's node may lie beyond the dictionary's nodes
        if (starts != null || own != null) return m.byFlags()
        val children = dictionary.childCount(node)
        // one number with PinyinDecoder, so the two never read apart
        if (children > PinyinDecoder.WALKED_PER_MATCH * m.size) return m.byFlags()
        val first = dictionary.firstChild(node)
        val out = IntArray(children)
        var n = 0
        var mixed = false
        for (c in first until first + children) {
            val i = m.indexOf(dictionary.syllable(c))
            if (i < 0) continue
            if (n > 0 && m.flags(i) != m.flags(out[0])) mixed = true
            out[n++] = i
        }
        val found = out.copyOf(n)
        return if (mixed) found.sortedBy { m.flags(it) }.toIntArray() else found
    }

    fun child(node: Int, syllable: Int) = starts?.child(node, syllable) ?: own?.child(node, syllable) ?: dictionary.child(node, syllable)

    fun spells(node: Int): Boolean = when {
        starts != null -> (0 until starts.wordCount(node)).any { starts.word(node, it) == word }
        own != null -> (0 until own.wordCount(node)).any { own.word(node, it) == word }
        else -> (0 until dictionary.wordCount(node)).any { dictionary.word(node, it) == word }
    }
}
