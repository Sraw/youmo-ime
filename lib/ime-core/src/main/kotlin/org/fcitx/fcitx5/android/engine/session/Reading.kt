/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import org.fcitx.fcitx5.android.engine.lattice.UserWords
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches

/** How a word reads from one place of a syllable graph to another: what each syllable shows, where it ends, and its id. */
internal class Reading(val shown: List<String>, val ends: List<Int>, val syllables: IntArray, val exact: Boolean = true)

/**
 * The syllables along a path of [graph] from [from] to [to] that spell [word], exact readings
 * first, or null if there is none. The decoder keeps only where words end, so they are
 * found again here; [own], the user's words where [word] is the user's (one only imported in their
 * [UserWords.startWords]), else [dictionary]; [show], how an edge read as a syllable shows.
 */
internal fun reading(
    graph: SyllableGraph, from: Int, to: Int, word: Int, dictionary: PinyinDictionary, own: UserWords?,
    show: (edge: Int, syllable: Int) -> String,
): Reading? {
    val shown = ArrayList<String>()
    val ends = ArrayList<Int>()
    val syllables = ArrayList<Int>()
    // the syllables of the path read other than as typed (fuzzy, a slip): an imported word goes after the words read exactly
    var misread = 0

    val trie = Spelt(word, dictionary, own)

    fun walk(at: Int, node: Int): Boolean {
        if (at == to) return syllables.isNotEmpty() && trie.spells(node)
        for (e in graph.edges(at)) {
            if (graph.kind(e) == Kind.RAW || graph.to(e) > to) continue
            val m = graph.matches(e)
            // exact readings first: a fuzzy one reaching the same word is the less likely
            for (i in (0 until m.size).sortedBy { m.flags(it) }) {
                val child = trie.child(node, m.syllable(i))
                if (child < 0) continue
                val off = if (m.flags(i) and MISREAD != 0) 1 else 0
                shown += show(e, m.syllable(i))
                ends += graph.to(e)
                syllables += m.syllable(i)
                misread += off
                if (walk(graph.to(e), child)) return true
                shown.removeAt(shown.size - 1)
                ends.removeAt(ends.size - 1)
                syllables.removeAt(syllables.size - 1)
                misread -= off
            }
        }
        return false
    }

    return if (walk(from, trie.root)) Reading(shown, ends, syllables.toIntArray(), misread == 0) else null
}

private const val MISREAD = SyllableMatches.FUZZY or SyllableMatches.TYPO or SyllableMatches.NEIGHBOUR

// the trie [word] is in: the user's start-only words where it is one only imported, else the user's
// where it is theirs, else the dictionary's
private class Spelt(private val word: Int, private val dictionary: PinyinDictionary, private val own: UserWords?) {
    private val starts = own?.takeIf { it.startOnly(word) }?.startWords

    val root = starts?.root ?: own?.root ?: dictionary.root

    fun child(node: Int, syllable: Int) = starts?.child(node, syllable) ?: own?.child(node, syllable) ?: dictionary.child(node, syllable)

    fun spells(node: Int): Boolean = when {
        starts != null -> (0 until starts.wordCount(node)).any { starts.word(node, it) == word }
        own != null -> (0 until own.wordCount(node)).any { own.word(node, it) == word }
        else -> (0 until dictionary.wordCount(node)).any { dictionary.word(node, it) == word }
    }
}
