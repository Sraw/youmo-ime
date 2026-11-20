/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import java.util.BitSet

/**
 * The dictionary's and merged file's nodes on the way to each word the user typed or chose, along each reading
 * typed: what a walk over a lone 双拼 key keeps to ([org.fcitx.fcitx5.android.engine.lattice.UserWords.typedBelow]).
 * Words added mark their nodes at once; one removed makes it [stale], remarked when next asked (a millisecond or
 * more on a phone). Not thread-safe: read and written on the engine thread only, as [UserModel] is.
 */
internal class TypedNodes(
    private val dictionary: PinyinDictionary,
    private val merged: DictionaryFile?,
    private val dictionaryWords: Int,
    private val mergedIds: IntRange,
    private val reading: (word: Int) -> IntArray?,
    private val typedWords: (visit: (word: Int) -> Unit) -> Unit,
) {
    private val base = BitSet()
    private val mergedNodes = BitSet()

    var stale = true

    var indexed = 0
        private set

    fun added(word: Int) {
        if (!stale) markWord(word)
    }

    // readings a dictionary word was typed at besides the model's: 行 as xing and hang
    private val otherReadings = HashMap<Int, ArrayList<IntArray>>()

    private val byHand = HashMap<Int, ArrayList<IntArray>>()

    private var pinned: Map<Int, List<IntArray>> = emptyMap()

    /** Whether [word] is typed for good: a dictionary word added by hand, or pinned. */
    fun kept(word: Int): Boolean = byHand.isNotEmpty() && word in byHand || pinned.isNotEmpty() && word in pinned

    fun pinned(words: Map<Int, List<IntArray>>) {
        if (pinned.isEmpty() && words.isEmpty()) return
        pinned = words
        stale = true
    }

    fun alsoRead(word: Int, syllables: IntArray) {
        if (word !in 0 until dictionaryWords || reading(word)?.contentEquals(syllables) == true) return
        if (add(otherReadings, word, syllables) && !stale) mark(syllables)
    }

    fun byHand(word: Int, syllables: IntArray, added: Boolean) {
        if (added) {
            if (add(byHand, word, syllables) && !stale) mark(syllables)
        } else {
            byHand[word]?.let { readings ->
                readings.removeAll { it.contentEquals(syllables) }
                if (readings.isEmpty()) byHand.remove(word)
            }
            stale = true
        }
    }

    private fun add(to: HashMap<Int, ArrayList<IntArray>>, word: Int, syllables: IntArray): Boolean {
        val readings = to.getOrPut(word) { ArrayList(1) }
        if (readings.any { it.contentEquals(syllables) }) return false
        readings += syllables
        return true
    }

    fun forgetReadings(words: Set<Int>? = null) {
        if (words == null) otherReadings.clear() else otherReadings.keys.removeAll(words)
        stale = true
    }

    fun below(node: Int): Boolean {
        if (stale) index()
        return base[node]
    }

    fun nextBelow(from: Int): Int {
        if (stale) index()
        return base.nextSetBit(from)
    }

    fun mergedBelow(node: Int): Boolean {
        if (stale) index()
        return mergedNodes[node]
    }

    private fun index() {
        stale = false
        indexed++
        base.clear()
        mergedNodes.clear()
        typedWords(::markWord)
        for (readings in byHand.values) readings.forEach(::mark)
        for ((word, readings) in pinned) {
            if (word in mergedIds) markMerged(word - mergedIds.first) else if (word in 0 until dictionaryWords) readings.forEach(::mark)
        }
    }

    // a word of the user's own trie or only imported has no node to mark: those tries are walked whole
    private fun markWord(word: Int) {
        if (word in 0 until dictionaryWords) {
            reading(word)?.let(::mark)
            otherReadings[word]?.forEach(::mark)
        } else if (word in mergedIds) {
            markMerged(word - mergedIds.first)
        }
    }

    private fun markMerged(place: Int) {
        val file = merged ?: return
        var node = 0
        for (syllable in file.syllables(place)) {
            node = file.child(node, syllable)
            if (node < 0) return
            mergedNodes.set(node)
        }
    }

    private fun mark(syllables: IntArray) {
        var node = dictionary.root
        for (syllable in syllables) {
            node = dictionary.child(node, syllable)
            if (node < 0) return
            base.set(node)
        }
    }
}
