/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter

/**
 * A [PinyinSession]'s syllables on the nine keys (九键): which the first digits not yet taken may
 * be ([offered]), and those taken, which stand in the input as their letters and a separator.
 * Taking one and giving it back edit the session's input; the session reads it again after.
 */
internal class NineKeys(private val data: PinyinData, private val letters: NineKeyLetters) {

    /** A syllable taken at [at] for the keys [was], [length] letters, a separator [added] after it; [then] the input after. */
    private class Taken(val at: Int, val was: String, val length: Int, val added: Boolean, val then: String)

    private val taken = ArrayList<Taken>() // the last last

    /** What the first digits not taken may be, likeliest first, a letter for an initial last: see [Snapshot.syllables]. */
    var offered: List<String> = emptyList()
        private set
    private var offeredAt = -1 // where in the input those digits start
    private var offeredPresses: List<Int> = emptyList() // the presses each of offered stands for

    /** Which list [offered] is: see [Snapshot.syllablesId]. */
    var id = 0
        private set

    // where in the input the best reading has a syllable start: which, and where it ends
    private var best: Map<Int, Pair<Int, Int>> = emptyMap()

    fun clear() {
        taken.clear()
        show(emptyList(), -1)
    }

    // a list unlike the last gets another id: a pick from the last is stale
    private fun show(syllables: List<String>, at: Int, presses: List<Int> = syllables.map { it.length }) {
        if (syllables == offered && at == offeredAt && presses == offeredPresses) return
        offered = syllables
        offeredAt = at
        offeredPresses = presses
        id = (id + 1) % Action.Syllable.IDS
    }

    /** Before the best reading of the input read again is noted: see [note]. */
    fun forgetBest() {
        best = emptyMap()
    }

    /** Syllables of the best reading: [syllables] from input position [from] on, the [k]th ending at [ends] [k]. */
    fun note(from: Int, ends: List<Int>, syllables: IntArray) {
        val noted = HashMap(best)
        for (k in syllables.indices) noted[if (k == 0) from else ends[k - 1]] = syllables[k] to ends[k]
        best = noted
    }

    /**
     * Offers what the first digits after [from] of [input] may be, as [graph] (of the input from
     * [from]) reads them: the best reading's syllable first, then the longer, then the more
     * common; then the letters of the first digit that are initials, for 简拼. Nothing where all
     * is taken.
     */
    fun offer(input: CharSequence, from: Int, graph: SyllableGraph) {
        val at = (from until input.length).firstOrNull { T9Segmenter.isKey(input[it]) }
        if (at == null) {
            show(emptyList(), -1)
            return
        }
        val (first, firstEnd) = best[at] ?: (-1 to -1)
        val (found, firstPresses) = syllablesAt(graph, from, at, first, firstEnd)
        val order = found.keys.sortedWith(
            compareByDescending<Int> { it == first }.thenByDescending { found.getValue(it) }.thenByDescending { weights[it] },
        )
        // a letter typed as itself offers itself alone
        val digit = T9Segmenter.digitOf(input[at])
        val keys = if (input[at] in T9Segmenter.FIXED) input[at].lowercase() else letters.letters(digit)
        val initials = keys.filter { it in INITIAL_LETTERS }.map { it.toString() }
        // a key pressed again: the letter it came to stands for all its presses, the others for the first
        var run = 1
        while (at + run < input.length && input[at + run] == T9Segmenter.again(digit)) run++
        val cameTo = if (run > 1 && keys.isNotEmpty()) keys[(run - 1) % keys.length].toString() else null
        val presses = HashMap<String, Int>()
        for ((s, length) in found) presses[Syllables.spelling(s)] = if (s == first && firstPresses > 0) firstPresses else length
        val shown = (order.map { Syllables.spelling(it) } + initials).take(Action.Syllable.MAX)
        show(shown, at, shown.map { s -> presses[s] ?: if (s == cameTo) run else 1 })
    }

    // the syllables [graph] (of the input from [from]) reads at input position [at], each to its most
    // presses, in the order read; and the presses of [first], the best reading's syllable ending at [firstEnd]
    private fun syllablesAt(graph: SyllableGraph, from: Int, at: Int, first: Int, firstEnd: Int): Pair<Map<Int, Int>, Int> {
        val found = LinkedHashMap<Int, Int>() // syllable to its length
        var firstPresses = 0 // of the best reading's syllable as it read them: maybe fewer than its longest
        for (e in graph.edges(at - from)) {
            if (graph.kind(e) != Kind.SYLLABLE) continue
            val m = graph.matches(e)
            val length = graph.text(e).length
            // 嗯 as m, n, ng: the letters stand for those, as initials; read with a run of a key
            // pressed again as keys and as a letter, the more presses
            for (i in 0 until m.size) {
                val s = m.syllable(i)
                if (m.flags(i) and SyllableMatches.TAPPED.inv() != 0 || Syllables.split(Syllables.spelling(s)) == null) continue
                found.merge(s, length, ::maxOf)
                // 3 3 fast read as e then d…: e stands for its one press there, not for both
                if (s == first && from + graph.to(e) == firstEnd) firstPresses = length
            }
        }
        return found to firstPresses
    }

    /**
     * Puts the syllable at [index] of [offered] into [input] for its digits; false if there is
     * none, or the pick was [of] another list (see [Action.Syllable]).
     */
    fun take(input: StringBuilder, index: Int, of: Int): Boolean {
        if (of != Action.Syllable.ANY && of != id) return false
        val spelling = offered.getOrNull(index) ?: return false
        val at = offeredAt
        val was = input.substring(at, at + offeredPresses[index])
        input.replace(at, at + was.length, spelling)
        // the presses may have been more than its letters: a run of a key pressed again one
        val end = at + spelling.length
        val added = end == input.length || input[end] != SyllableGraph.SEPARATOR
        if (added) input.insert(end, SyllableGraph.SEPARATOR)
        taken += Taken(at, was, spelling.length, added, input.toString())
        return true
    }

    /**
     * Puts [c] into [input] as a letter typed as itself ([Action.Letter]), upper case, after the
     * last key; but [again] after the same key's digit after [picked], what is picked already, a
     * press of its own ([T9Segmenter.again]: the decoder reads the run as keys or as the letter it
     * came to), and after the same letter typed as itself, in its place. Again with no such key (a candidate
     * picked in between, which the keyboard did not see) it is its key, any of its letters, as a
     * press after a pause; so too after such a key while [c], the letter the keyboard counted the
     * presses to, is not the one the run comes to. False for what is no letter.
     */
    fun letter(input: StringBuilder, c: Char, again: Boolean, picked: Int): Boolean {
        if (c !in 'a'..'z') return false
        val last = input.length - 1
        val same = last >= picked && T9Segmenter.isKey(input[last]) && letters.sameKey(T9Segmenter.digitOf(input[last]), c)
        // the keyboard counted no press the input lacks: 2, a pick it did not see, then 2 2 came as b
        // and c, which as a run of the 2 typed after the pick would read b
        val counted = same && input[last] !in T9Segmenter.FIXED && c == nextLetter(input, last, picked)
        when {
            // a press of its own, the decoder to read the run as keys or as the letter it came to
            again && counted -> input.append(T9Segmenter.again(T9Segmenter.digitOf(input[last])))
            again && same && input[last] in T9Segmenter.FIXED -> input.setCharAt(last, c.uppercaseChar())
            again && same -> input.append(T9Segmenter.digitOf(input[last]))
            // which of its keys was pressed is not told: the letter itself where it is on more than one
            again -> input.append(if (letters.keysOf(c).length == 1) letters.key(c) else c.uppercaseChar())
            else -> input.append(c.uppercaseChar())
        }
        return true
    }

    // the letter a press more of the key whose run ends at [last] comes to, the run as the segmenter
    // reads it: not past what is picked, [picked], nor past a separator
    private fun nextLetter(input: CharSequence, last: Int, picked: Int): Char {
        val digit = T9Segmenter.digitOf(input[last])
        var start = last
        while (start > picked && input[start] == T9Segmenter.again(digit) && T9Segmenter.digitOf(input[start - 1]) == digit) start--
        val keyLetters = letters.letters(digit)
        return keyLetters[(last - start + 1) % keyLetters.length]
    }

    /**
     * Gives the syllable taken last back to [input] as the keys it was, if nothing was typed since:
     * where it starts; else -1, and backspace deletes as it would.
     */
    fun giveBack(input: StringBuilder): Int {
        val last = taken.lastOrNull()
        // the input itself, not its length: a letter typed as itself replaces the last key in place
        if (last == null || last.then != input.toString()) return -1
        taken.removeAt(taken.size - 1)
        input.replace(last.at, last.at + last.length, last.was)
        if (last.added) input.deleteCharAt(last.at + last.was.length)
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
