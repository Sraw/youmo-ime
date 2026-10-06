/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter

/**
 * A [PinyinSession]'s syllables on the nine keys (九键): which the first digits not yet taken may
 * be ([offered]), and those taken, which stand in the input as their letters and a separator.
 * Taking one and giving it back edit the session's input; the session reads it again after.
 */
internal class NineKeys(private val data: PinyinData) {

    /** A syllable taken at [at], [length] letters, a separator [added] after it; [then] the input's length after. */
    private class Taken(val at: Int, val length: Int, val added: Boolean, val then: Int)

    private val taken = ArrayList<Taken>() // the last last

    /** What the first digits not taken may be, likeliest first, a letter for an initial last: see [Snapshot.syllables]. */
    var offered: List<String> = emptyList()
        private set
    private var offeredAt = -1 // where in the input those digits start

    // where in the input the best reading has a syllable start, and which
    private var best: Map<Int, Int> = emptyMap()

    fun clear() {
        taken.clear()
        offered = emptyList()
        offeredAt = -1
    }

    /** Before the best reading of the input read again is noted: see [note]. */
    fun forgetBest() {
        best = emptyMap()
    }

    /** Syllables of the best reading: the [k]th of [syllables] starting at input position [at] (as [starts] has them). */
    fun note(starts: List<Int>, syllables: IntArray) {
        val noted = HashMap(best)
        for (k in syllables.indices) noted[starts[k]] = syllables[k]
        best = noted
    }

    /**
     * Offers what the first digits after [from] of [input] may be, as [graph] (of the input from
     * [from]) reads them: the best reading's syllable first, then the longer, then the more
     * common; then the letters of the first digit that are initials, for 简拼. Nothing where all
     * is taken.
     */
    fun offer(input: CharSequence, from: Int, graph: SyllableGraph) {
        offered = emptyList()
        offeredAt = -1
        val at = (from until input.length).firstOrNull { input[it] in '2'..'9' } ?: return
        val found = LinkedHashMap<Int, Int>() // syllable to its length
        for (e in graph.edges(at - from)) {
            if (graph.kind(e) != Kind.SYLLABLE) continue
            val m = graph.matches(e)
            val length = graph.text(e).length
            // 嗯 as m, n, ng: the letters stand for those, as initials
            for (i in 0 until m.size) if (m.flags(i) == 0 && Syllables.split(Syllables.spelling(m.syllable(i))) != null) found[m.syllable(i)] = length
        }
        val first = best[at]
        val order = found.keys.sortedWith(
            compareByDescending<Int> { it == first }.thenByDescending { found.getValue(it) }.thenByDescending { weights[it] },
        )
        val letters = T9Segmenter.letters(input[at]).filter { it in INITIAL_LETTERS }.map { it.toString() }
        offered = order.map { Syllables.spelling(it) } + letters
        offeredAt = at
    }

    /** Puts the syllable at [index] of [offered] into [input] for its digits; false if there is none. */
    fun take(input: StringBuilder, index: Int): Boolean {
        val spelling = offered.getOrNull(index) ?: return false
        val at = offeredAt
        val end = at + spelling.length
        input.replace(at, end, spelling)
        val added = end == input.length || input[end] != SyllableGraph.SEPARATOR
        if (added) input.insert(end, SyllableGraph.SEPARATOR)
        taken += Taken(at, spelling.length, added, input.length)
        return true
    }

    /**
     * Gives the syllable taken last back to [input] as its digits, if nothing was typed since:
     * where it starts; else -1, and backspace deletes as it would.
     */
    fun giveBack(input: StringBuilder): Int {
        val last = taken.lastOrNull()
        if (last == null || last.then != input.length) return -1
        taken.removeAt(taken.size - 1)
        val end = last.at + last.length
        input.replace(last.at, end, T9Segmenter.digits(input.substring(last.at, end)))
        if (last.added) input.deleteCharAt(end)
        return last.at
    }

    // how common each syllable is: its commonest character's score; for the order offered only
    private val weights: FloatArray by lazy {
        val dictionary = data.dictionary
        FloatArray(Syllables.count) { s ->
            val node = dictionary.child(dictionary.root, s)
            if (node < 0) {
                Float.NEGATIVE_INFINITY
            } else {
                (0 until dictionary.wordCount(node)).maxOfOrNull { data.model.score(dictionary.word(node, it)) } ?: Float.NEGATIVE_INFINITY
            }
        }
    }

    private companion object {
        /** The letters that are an initial alone. */
        const val INITIAL_LETTERS = "bcdfghjklmnpqrstwxyz"
    }
}
