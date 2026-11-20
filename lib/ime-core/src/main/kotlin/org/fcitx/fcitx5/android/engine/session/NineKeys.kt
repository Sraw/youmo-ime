/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.learn.Take
import org.fcitx.fcitx5.android.engine.pinyin.KeyedSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter

/** A [PinyinSession]'s nine-key syllables: what the first untaken digits may be ([offered]), and those taken, standing in the input as letters plus separator; taking and returning edit the input, which the session rereads. */
internal class NineKeys(
    private val data: PinyinData,
    private val letters: NineKeyLetters,
    /** Kept in step with the input as this edits it. */
    private val pressed: PressedKeys = PressedKeys(),
    /** Whether an input character is a pressed key ([KeyedSegmenter.isKey]): codes and fixed letters, which [offer] and 连按 work on. */
    private val isKey: (Char) -> Boolean = T9Segmenter.Companion::isKey,
    /** Initials a key offers for 简拼 ([KeyedSegmenter.initialsOf]). */
    private val initialsOf: (Char) -> List<String> = { c -> T9Segmenter.initialsOf(letters, c) },
    /** The key an initial is taken as ([KeyedSegmenter.keyOf]): zhuyin's symbol; null for letters plus separator. */
    private val keyOf: (String) -> Char? = { null },
    /** [KeyedSegmenter.shownOffered]. */
    private val shown: (String) -> String = { it },
    /** How often each syllable was taken for some digits (the bar's habit): orders them after the best reading's. */
    private val bar: (digits: String) -> Map<String, Int> = { emptyMap() },
) {

    /** A syllable taken at [at] for keys [was], [put] in their place (its [spelling], or the key of an initial), a separator [added] after; [then] the input after; [runs] what [pressed] had. */
    private class Taken(val at: Int, val was: String, val spelling: String, val put: String, val added: Boolean, val then: String, val runs: List<PressedKeys.Run>) {
        val length get() = put.length
    }

    private val taken = ArrayList<Taken>() // latest last

    /** Whether 1 separates syllables (the nine keys'; the 14 keys have their own key, a swiped 1 is the keypad's). */
    val oneSeparates = letters.separator == PinyinSession.NINE_KEYS_SEPARATOR

    /** Likeliest first, initials last: see [Snapshot.syllables]. */
    var offered: List<String> = emptyList()
        private set
    private var offeredAt = -1 // where in the input those digits start
    private var offeredPresses: List<Int> = emptyList() // the presses each offered one stands for

    /** See [Snapshot.syllablesId]. */
    var id = 0
        private set

    // input positions where the best reading's syllables start, with lengths
    private var best: Map<Int, Int> = emptyMap()

    fun clear() {
        taken.clear()
        show(emptyList(), -1)
    }

    // a different list gets a new id: a pick from the last is stale
    private fun show(syllables: List<String>, at: Int, presses: List<Int> = syllables.map { it.length }) {
        if (syllables == offered && at == offeredAt && presses == offeredPresses) return
        offered = syllables
        offeredAt = at
        offeredPresses = presses
        id = (id + 1) % Action.Syllable.IDS
    }

    /** Before the reread input's best reading is noted ([note]). */
    fun forgetBest() {
        best = emptyMap()
    }

    /** The best reading's [syllables] from [from], the [k]th ending at [ends] [k]. */
    fun note(from: Int, ends: List<Int>, syllables: IntArray) {
        val noted = HashMap(best)
        for (k in syllables.indices) noted[if (k == 0) from else ends[k - 1]] = syllables[k]
        best = noted
    }

    /** Offers what the first digits after [from] may be per [graph]: the best reading's syllable, then most taken, then those no tap clearly contradicts, then longer, then commoner; then initials of the first digit for 简拼. Nothing when all is taken. */
    fun offer(input: CharSequence, from: Int, graph: SyllableGraph) {
        val key = (from until input.length).firstOrNull { isKey(input[it]) }
        if (key == null) {
            show(emptyList(), -1)
            return
        }
        // an initial taken that binds this key as its final (grouped 双拼): its syllables from the initial on
        val at = boundFrom(input, from, key, graph) ?: key
        val first = best[at] ?: -1
        val found = syllablesAt(graph, from, at)
        // habits by the digits each syllable takes, as a take files them
        val bars = HashMap<Int, Map<String, Int>>()
        val taken = { s: Int -> bars.getOrPut(found.getValue(s)) { bar(pressed.keys(at, at + found.getValue(s))) }[Syllables.spelling(s)] ?: 0 }
        val taps = pressed.taps(at, input.length)
        // the nine keys and 双拼 have no tap places: nothing to weigh
        val objections = if (taps == null) emptyMap() else found.keys.associateWith { objected(input, at, Syllables.spelling(it), found.getValue(it), taps) }
        val order = found.keys.sortedWith(
            compareByDescending<Int> { it == first }.thenByDescending(taken)
                .thenBy { objections[it] == true }
                .thenByDescending { found.getValue(it) }.thenByDescending { weights[it] },
        )
        val initials = if (at == key) initialsOf(input[at]) else emptyList()
        val presses = HashMap<String, Int>()
        for ((s, length) in found) presses[Syllables.spelling(s)] = length
        // an initial taken as a key that alone reads a same-looking syllable (zhuyin ㄓ for zhi and zh): the initial only
        val keyed = initials.filter { keyOf(it) != null }.mapTo(HashSet(), shown)
        val offered = (order.map { Syllables.spelling(it) }.filterNot { shown(it) in keyed } + initials).take(Action.Syllable.MAX)
        show(offered, at, offered.map { s -> presses[s] ?: 1 })
    }

    // the start of taken letters before [key] and its separator if [graph] reads them with [key] as one syllable
    // (an initial taken on grouped 双拼); else null
    private fun boundFrom(input: CharSequence, from: Int, key: Int, graph: SyllableGraph): Int? {
        if (key - 1 <= from || input[key - 1] != SyllableGraph.SEPARATOR) return null
        var start = key - 1
        while (start > from && input[start - 1] in 'a'..'z') start--
        return start.takeIf { s -> s < key - 1 && graph.edges(s - from).any { graph.kind(it) == Kind.SYLLABLE && graph.to(it) > key - from } }
    }

    // whether [spelling]'s keys from [at] were tapped at or past the other letter's centre: yes or no, so a slightly
    // off-centre tap does not reorder the bar. Only its [presses] keys; üe's v typed as u is weighed as u
    private fun objected(input: CharSequence, at: Int, spelling: String, presses: Int, taps: FloatArray): Boolean = (0 until minOf(spelling.length, presses)).any { k ->
        val tap = taps.getOrElse(k) { Float.NaN }
        val group = if (at + k < input.length) letters.letters(input[at + k]) else ""
        val j = group.indexOf(spelling[k]).takeIf { it >= 0 || spelling[k] != 'v' } ?: group.indexOf('u')
        !tap.isNaN() && at + k < input.length && letters.weighs(input[at + k]) && j >= 0 && letters.against(input[at + k], j, tap) >= OBJECTION
    }

    // syllables [graph] reads at [at], each to its most presses, in read order
    private fun syllablesAt(graph: SyllableGraph, from: Int, at: Int): Map<Int, Int> {
        val found = LinkedHashMap<Int, Int>() // syllable to its length
        for (e in graph.edges(at - from)) {
            if (graph.kind(e) != Kind.SYLLABLE) continue
            val m = graph.matches(e)
            val length = graph.text(e).length
            // 嗯 as m, n, ng: the letters stand for those, as initials
            for (i in 0 until m.size) {
                val s = m.syllable(i)
                if (m.flags(i) != 0 || Syllables.split(Syllables.spelling(s)) == null) continue
                found.merge(s, length, ::maxOf)
            }
        }
        return found
    }

    /** Puts the [index]th offered syllable in place of its digits; the new cursor, or -1 if none or the pick was [of] another list. */
    fun take(input: StringBuilder, index: Int, of: Int, cursor: Int): Int {
        if (of != Action.Syllable.ANY && of != id) return -1
        val spelling = offered.getOrNull(index) ?: return -1
        val at = offeredAt
        val was = input.substring(at, at + offeredPresses[index])
        val lengthBefore = input.length
        val runs = pressed.runs(at, at + was.length)
        // an initial taken as a key goes on into the next, no separator (no syllable is spelt as an initial)
        val key = keyOf(spelling)
        val put = key?.toString() ?: spelling
        if (put == was) return cursor
        input.replace(at, at + was.length, put)
        val end = at + put.length
        val added = key == null && (end == input.length || input[end] != SyllableGraph.SEPARATOR)
        if (added) input.insert(end, SyllableGraph.SEPARATOR)
        pressed.replace(at, at + was.length, put.length, runs.joinToString("") { it.keys }, added = if (added) 1 else 0)
        taken += Taken(at, was, spelling, put, added, input.toString(), runs)
        return if (cursor < at) cursor else maxOf(cursor, at + was.length) + input.length - lengthBefore
    }

    /**
     * Puts [c] in as a letter typed as itself at [cursor]; the new cursor, or -1 for no letter. With [again] (连按)
     * after the same key, or a letter after [picked], it replaces that in place with the key's next letter, so 4 4 fast
     * is `H`. Again with no such key it is its key, as a press after a pause.
     */
    fun letter(input: StringBuilder, c: Char, again: Boolean, picked: Int, cursor: Int, glided: Boolean = false): Int {
        if (c !in 'a'..'z') return -1
        val last = cursor - 1
        val same = last >= picked && isKey(input[last]) && letters.sameKey(input[last], c)
        if (again && same) {
            input.setCharAt(last, c.uppercaseChar())
            pressed.set(last, c.toString(), multiTap = true)
            return cursor
        }
        val key = when {
            // which of its keys was pressed is not told: the letter itself where it is on more than one
            again -> if (letters.keysOf(c).length == 1) letters.key(c) else c.uppercaseChar()
            else -> c.uppercaseChar()
        }
        pressed.insert(cursor, key.toString(), glided, input = input, letters = letters)
        input.insert(cursor, key)
        return cursor + 1
    }

    /** Gives the last taken syllable back as its keys if nothing was typed since: where it starts, else -1. */
    fun giveBack(input: StringBuilder): Int {
        val last = taken.lastOrNull()
        // the input itself, not its length: a letter typed as itself replaces the last key in place
        if (last == null || last.then != input.toString()) return -1
        taken.removeAt(taken.size - 1)
        input.replace(last.at, last.at + last.length, last.was)
        if (last.added) input.deleteCharAt(last.at + last.was.length)
        pressed.putBack(last.at, last.at + last.length + if (last.added) 1 else 0, last.runs)
        return last.at
    }

    /** The syllables taken and kept, for their digits, under [layout]. */
    fun takes(layout: String): List<Take> = taken.map { Take(layout, it.runs.joinToString("") { r -> r.keys }, it.spelling) }

    /** Whether a syllable is taken: a pick then is 'H', weighed against the first shown before it was. */
    val anyTaken get() = taken.isNotEmpty()

    // each syllable's commonest character's score; for the order offered only
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
        // how far past the middle a tap must go to count against a letter (the other letter's centre)
        private const val OBJECTION = 0.5f
    }
}
