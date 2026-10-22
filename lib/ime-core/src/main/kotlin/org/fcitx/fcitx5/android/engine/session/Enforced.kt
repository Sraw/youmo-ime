/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.Candidate

/**
 * What the user's sentences keep (U-08, D33; impl/design-1c.md §1): in each of the decoder's
 * sentences, the words over a stretch of the input the user pinned, made their first there after
 * the word before, or added by hand as the only word of its keys, put in place of the decoder's.
 * 我→指导 twice, wozhidao reads 我指导. A sentence whose word runs past either end of the stretch
 * has a longer word over it (知道了 in zhidaole) and keeps its own. The scores stay the decoder's.
 */
internal object Enforced {

    /** An item's kind, in the order that wins where two are of the same stretch. */
    enum class Kind { PIN, FIRST, ADDED }

    /**
     * The [words] ([text]) over the input from [from] to [to], each ending at [ends]; of the user's
     * first after [prev], or anywhere (a pin, a word added by hand: null). Places are the graph's.
     */
    class Item(val from: Int, val to: Int, val words: IntArray, val ends: IntArray, val text: String, val prev: String?, val kind: Kind)

    /**
     * [sentences] with [items] put in: in each, those with a word boundary at both their ends, and
     * for a first the word before its [Item.prev] ([prev] at the sentence's start); of two of them
     * that overlap, neither; of one stretch, the kind first ([Kind.PIN] over [Kind.FIRST] over [Kind.ADDED]). Then
     * each text once, the first. [text] gives a word's text; [start], the graph's first place (past a
     * separator the input starts with), where the sentences' first word starts.
     */
    fun apply(sentences: List<Candidate>, items: List<Item>, prev: String, text: (Int) -> String, start: Int = 0): List<Candidate> {
        if (items.isEmpty()) return sentences
        return sentences.map { s -> applying(s, items, prev, text, start).fold(s) { c, item -> put(c, item, text, start) } }.distinctBy { it.text }
    }

    // the items that apply to [s], left to right (review-design-1c S5: settled among those alone)
    private fun applying(s: Candidate, items: List<Item>, prev: String, text: (Int) -> String, start: Int): List<Item> {
        val bounded = items.filter { (it.from == start || s.ends.contains(it.from)) && s.ends.contains(it.to) }
        // the word before each place: the sentence's, or the last of an item put in ending there
        val before = HashMap<Int, MutableSet<String>>()
        fun saw(at: Int, word: String?) {
            if (word != null) before.getOrPut(at) { HashSet() } += word
        }
        saw(start, prev)
        for (i in s.ends.indices) saw(s.ends[i], wordText(s.words[i], text))
        // a first after a pin or a word added by hand: only one that stands (review-1c N3)
        val anywhere = settle(bounded.filter { it.prev == null })
        for (item in anywhere) saw(item.to, wordText(item.words.last(), text))
        val firsts = bounded.filter { it.prev != null && before[it.from]?.contains(it.prev) == true }
        return settle(anywhere + firsts).sortedBy { it.from }
    }

    /** Of [items], those that stand: of two stretches that overlap, neither; of one stretch, the kind first. */
    fun settle(items: List<Item>): List<Item> {
        if (items.size < 2) return items
        val bySpan = items.groupBy { it.from to it.to }
        val out = ArrayList<Item>()
        for ((span, here) in bySpan) {
            if (bySpan.keys.any { it != span && it.first < span.second && span.first < it.second }) continue
            val best = here.minOf { it.kind }
            out += here.first { it.kind == best }
        }
        return out
    }

    // [item] in place of [c]'s words over its stretch, if it has a boundary at both ends and, for a
    // first, the word before (after what was put in before it) is its prev
    private fun put(c: Candidate, item: Item, text: (Int) -> String, start: Int): Candidate {
        val i = if (item.from == start) 0 else c.ends.indexOf(item.from) + 1
        val j = c.ends.indexOf(item.to)
        if (i == 0 && item.from != start || j < i) return c
        if (item.prev != null && i > 0 && item.prev != wordText(c.words[i - 1], text)) return c
        if (c.words.copyOfRange(i, j + 1).contentEquals(item.words)) return c
        val textStart = textOffset(c, i, text) ?: return c
        val textEnd = textOffset(c, j + 1, text) ?: return c
        return Candidate(
            c.text.substring(0, textStart) + item.text + c.text.substring(textEnd),
            c.end,
            c.score,
            c.words.copyOfRange(0, i) + item.words + c.words.copyOfRange(j + 1, c.words.size),
            c.ends.copyOfRange(0, i) + item.ends + c.ends.copyOfRange(j + 1, c.ends.size),
        )
    }

    // where [c]'s word [k] starts in its text, counted from whichever side has no text kept as typed
    private fun textOffset(c: Candidate, k: Int, text: (Int) -> String): Int? {
        var before = 0
        for (w in 0 until k) before += wordText(c.words[w], text)?.length ?: return fromEnd(c, k, text)
        return before
    }

    private fun fromEnd(c: Candidate, k: Int, text: (Int) -> String): Int? {
        var after = 0
        for (w in k until c.words.size) after += wordText(c.words[w], text)?.length ?: return null
        return c.text.length - after
    }

    private fun wordText(word: Int, text: (Int) -> String) = if (word == NO_WORD) null else text(word)
}
