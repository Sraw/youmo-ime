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

/**
 * A [PinyinSession]'s syllables on the nine keys (九键): which the first digits not yet taken may
 * be ([offered]), and those taken, which stand in the input as their letters and a separator.
 * Taking one and giving it back edit the session's input; the session reads it again after.
 */
internal class NineKeys(
    private val data: PinyinData,
    private val letters: NineKeyLetters,
    /** The keys pressed, kept in step with the input as this edits it. */
    private val pressed: PressedKeys = PressedKeys(),
    /**
     * Whether a character of the input is a key pressed: the segmenter's ([KeyedSegmenter.isKey]), its
     * codes and the letters typed as themselves, which [offer] finds the first of and 连按 runs on.
     */
    private val isKey: (Char) -> Boolean = T9Segmenter.Companion::isKey,
    /** The initials a key offers for 简拼 ([KeyedSegmenter.initialsOf]): the nine keys' its initial letters. */
    private val initialsOf: (Char) -> List<String> = { c -> T9Segmenter.initialsOf(letters, c) },
    /** How often each syllable was taken for some digits (the bar's habit, U-23): its order after the best reading's. */
    private val bar: (digits: String) -> Map<String, Int> = { emptyMap() },
) {

    /**
     * A syllable taken at [at] for the keys [was], [spelling] in their place, a separator [added]
     * after it; [then] the input after; [runs], what [pressed] had for the keys.
     */
    private class Taken(val at: Int, val was: String, val spelling: String, val added: Boolean, val then: String, val runs: List<PressedKeys.Run>) {
        val length get() = spelling.length
    }

    private val taken = ArrayList<Taken>() // the last last

    /** Whether the keys' 1 splits syllables (分词), the nine keys': the 14 keys have a key of their own for it, a 1 swiped off one the keypad's. */
    val oneSeparates = letters.separator == PinyinSession.NINE_KEYS_SEPARATOR

    /** What the first digits not taken may be, likeliest first, a letter for an initial last: see [Snapshot.syllables]. */
    var offered: List<String> = emptyList()
        private set
    private var offeredAt = -1 // where in the input those digits start
    private var offeredPresses: List<Int> = emptyList() // the presses each of offered stands for

    /** Which list [offered] is: see [Snapshot.syllablesId]. */
    var id = 0
        private set

    // where in the input the best reading has a syllable start: which
    private var best: Map<Int, Int> = emptyMap()

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
        for (k in syllables.indices) noted[if (k == 0) from else ends[k - 1]] = syllables[k]
        best = noted
    }

    /**
     * Offers what the first digits after [from] of [input] may be, as [graph] (of the input from
     * [from]) reads them: the best reading's syllable first, then those taken most for their digits,
     * then those no tap clearly goes against (a key of two letters tapped at its other letter's
     * centre or further: a tap near the middle moves nothing), then the longer, then the more common; then the letters of the first digit that are initials, for 简拼. Nothing where all
     * is taken.
     */
    fun offer(input: CharSequence, from: Int, graph: SyllableGraph) {
        val key = (from until input.length).firstOrNull { isKey(input[it]) }
        if (key == null) {
            show(emptyList(), -1)
            return
        }
        // an initial taken that binds this key as its final (grouped 双拼): its syllables, from the initial on
        val at = boundFrom(input, from, key, graph) ?: key
        val first = best[at] ?: -1
        val found = syllablesAt(graph, from, at)
        // a habit by the digits each syllable takes, as a take files it (impl/design-1b.md NIT 7)
        val bars = HashMap<Int, Map<String, Int>>()
        val taken = { s: Int -> bars.getOrPut(found.getValue(s)) { bar(pressed.keys(at, at + found.getValue(s))) }[Syllables.spelling(s)] ?: 0 }
        val taps = pressed.taps(at, input.length)
        // the nine keys and 双拼's are never tapped with a place: nothing to weigh, nothing made for it
        val objections = if (taps == null) emptyMap() else found.keys.associateWith { objected(input, at, Syllables.spelling(it), found.getValue(it), taps) }
        val order = found.keys.sortedWith(
            compareByDescending<Int> { it == first }.thenByDescending(taken)
                .thenBy { objections[it] == true }
                .thenByDescending { found.getValue(it) }.thenByDescending { weights[it] },
        )
        val initials = if (at == key) initialsOf(input[at]) else emptyList()
        val presses = HashMap<String, Int>()
        for ((s, length) in found) presses[Syllables.spelling(s)] = length
        val shown = (order.map { Syllables.spelling(it) } + initials).take(Action.Syllable.MAX)
        show(shown, at, shown.map { s -> presses[s] ?: 1 })
    }

    // the start of the taken letters before [key] and its separator, if [graph] (of the input from [from]) reads
    // them and [key] as one syllable: an initial taken on grouped 双拼 (GroupedShuangpinSegmenter); else null
    private fun boundFrom(input: CharSequence, from: Int, key: Int, graph: SyllableGraph): Int? {
        if (key - 1 <= from || input[key - 1] != SyllableGraph.SEPARATOR) return null
        var start = key - 1
        while (start > from && input[start - 1] in 'a'..'z') start--
        return start.takeIf { s -> s < key - 1 && graph.edges(s - from).any { graph.kind(it) == Kind.SYLLABLE && graph.to(it) > key - from } }
    }

    // whether a key [spelling] reads from [at] of [input] was tapped on its other letter's half at
    // that letter's centre or further ([taps] from [at]): a yes or no, not how far, so the bar
    // does not reorder for a tap a little off the middle. Only its [presses] keys: a syllable started
    // there, its later letters on keys of the next one (NineKeyLetters.against has the sides). üe's v
    // typed as u on a key without v is weighed as that u, as the segmenter does
    private fun objected(input: CharSequence, at: Int, spelling: String, presses: Int, taps: FloatArray): Boolean = (0 until minOf(spelling.length, presses)).any { k ->
        val tap = taps.getOrElse(k) { Float.NaN }
        val group = if (at + k < input.length) letters.letters(input[at + k]) else ""
        val j = group.indexOf(spelling[k]).takeIf { it >= 0 || spelling[k] != 'v' } ?: group.indexOf('u')
        !tap.isNaN() && group.length > 1 && j >= 0 && letters.against(j, tap) >= OBJECTION
    }

    // the syllables [graph] (of the input from [from]) reads at input position [at], each to its most
    // presses, in the order read
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

    /**
     * Puts the syllable at [index] of [offered] into [input] for its digits: where the [cursor] is
     * after it, past the syllable if it was in or after its digits; -1 if there is none, or the pick
     * was [of] another list (see [Action.Syllable]).
     */
    fun take(input: StringBuilder, index: Int, of: Int, cursor: Int): Int {
        if (of != Action.Syllable.ANY && of != id) return -1
        val spelling = offered.getOrNull(index) ?: return -1
        val at = offeredAt
        val was = input.substring(at, at + offeredPresses[index])
        val lengthBefore = input.length
        val runs = pressed.runs(at, at + was.length)
        input.replace(at, at + was.length, spelling)
        val end = at + spelling.length
        val added = end == input.length || input[end] != SyllableGraph.SEPARATOR
        if (added) input.insert(end, SyllableGraph.SEPARATOR)
        pressed.replace(at, at + was.length, spelling.length, runs.joinToString("") { it.keys }, added = if (added) 1 else 0)
        taken += Taken(at, was, spelling, added, input.toString(), runs)
        return if (cursor < at) cursor else maxOf(cursor, at + was.length) + input.length - lengthBefore
    }

    /**
     * Puts [c] into [input] as a letter typed as itself ([Action.Letter]), upper case, at [cursor],
     * the key before it the last; where the cursor is after it, or -1 for what is no letter. But
     * [again] (连按) after the same key, a digit or a letter typed as itself after [picked], what is
     * picked already, in its place: the key's next letter, what its preview showed, and nothing
     * else, so 4 4 fast is `H`. The letter shown wins where the keyboard counted a press the input
     * lacks (a pick it did not see). Again with no such key (a candidate picked in between, or the
     * cursor moved, [picked] then the cursor itself: what the keyboard did not see) it is its key,
     * any of its letters, as a press after a pause.
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
        // pressed again after another key: a press after a pause, the key or the letter, not one 连按 chose
        pressed.insert(cursor, key.toString(), glided, input = input, letters = letters)
        input.insert(cursor, key)
        return cursor + 1
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
        pressed.putBack(last.at, last.at + last.length + if (last.added) 1 else 0, last.runs)
        return last.at
    }

    /** The syllables taken and kept, for their digits: learned with the commit, the bar's (PLAN §2.2 state 6) under [layout]. */
    fun takes(layout: String): List<Take> = taken.map { Take(layout, it.runs.joinToString("") { r -> r.keys }, it.spelling) }

    /** Whether a syllable is taken: a pick then is 'H' (spec §1), learned against the first shown before it was. */
    val anyTaken get() = taken.isNotEmpty()

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
        // how far a tap goes against a letter for it to count (NineKeyLetters.against): a quarter of the
        // key past the middle, the other letter's centre (SyllableGraph.against 0.5)
        private const val OBJECTION = 0.5f
    }
}
