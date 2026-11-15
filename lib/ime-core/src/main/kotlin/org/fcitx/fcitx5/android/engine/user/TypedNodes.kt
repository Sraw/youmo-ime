/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import java.util.BitSet

/**
 * The nodes of the dictionary and of the merged dictionaries' file on the way to a word the user
 * typed or chose ([org.fcitx.fcitx5.android.engine.lattice.UserWords.typed]), along each reading
 * it was typed at: what a walk over a 双拼 key read alone keeps to
 * ([org.fcitx.fcitx5.android.engine.lattice.UserWords.typedBelow]). [UserModel] says when such a
 * word came ([added], [alsoRead], [byHand]), whose nodes are marked there and then, and when one went
 * ([stale]): the nodes are marked anew when next asked, a walk of the counts, the user's own words
 * and the readings here, a few thousand short paths (a millisecond or more on a phone, so not on the
 * key after each new word's first commit).
 *
 * Not safe across threads: [below] and [mergedBelow] mark the nodes anew, so it is read and written
 * on the engine's one thread only, as [UserModel] is.
 */
internal class TypedNodes(
    private val dictionary: PinyinDictionary,
    private val merged: DictionaryFile?,
    // the dictionary's words have the ids below this
    private val dictionaryWords: Int,
    // the ids of the merged file's words, by place: from the first up to the second
    private val mergedIds: IntRange,
    // the syllables the model has a word read as, null for none kept
    private val reading: (word: Int) -> IntArray?,
    // every word counted or the user's choice, some perhaps twice
    private val typedWords: (visit: (word: Int) -> Unit) -> Unit,
) {
    private val base = BitSet()
    private val mergedNodes = BitSet()

    /** Whether a word went since the nodes were marked: they are marked anew when next asked. */
    var stale = true

    /** How often the nodes were marked anew. */
    var indexed = 0
        private set

    /** [word] is typed or chosen now: its nodes marked. */
    fun added(word: Int) {
        if (!stale) markWord(word)
    }

    // the readings a dictionary's word was typed at beside the one the model keeps: 行 as xing and as hang
    private val otherReadings = HashMap<Int, ArrayList<IntArray>>()

    // the dictionary's words the user added by hand, with the readings they gave: typed ones, for good
    private val byHand = HashMap<Int, ArrayList<IntArray>>()

    // the words pinned under some keys, with the readings pinned at: typed ones while pinned
    private var pinned: Map<Int, List<IntArray>> = emptyMap()

    /** Whether [word] is typed for good: one of the dictionary's the user added by hand, or any pinned. */
    fun kept(word: Int): Boolean = byHand.isNotEmpty() && word in byHand || pinned.isNotEmpty() && word in pinned

    /** The words pinned are [words], each with the readings it is pinned at. */
    fun pinned(words: Map<Int, List<IntArray>>) {
        if (pinned.isEmpty() && words.isEmpty()) return
        pinned = words
        stale = true
    }

    /** [word], counted, was typed read as [syllables] too: kept for a word of the dictionary's, whose readings are several. */
    fun alsoRead(word: Int, syllables: IntArray) {
        if (word !in 0 until dictionaryWords || reading(word)?.contentEquals(syllables) == true) return
        if (add(otherReadings, word, syllables) && !stale) mark(syllables)
    }

    /** The dictionary's [word] added by hand, read as [syllables]; or no more so. */
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

    /** The readings counted words were typed at are no more: all of them, or those of [words]. */
    fun forgetReadings(words: Set<Int>? = null) {
        if (words == null) otherReadings.clear() else otherReadings.keys.removeAll(words)
        stale = true
    }

    /** Whether the dictionary's [node] has a word typed or chosen at it or below. */
    fun below(node: Int): Boolean {
        if (stale) index()
        return base[node]
    }

    /** [below], of a node of the merged file. */
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
