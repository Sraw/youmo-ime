/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.Candidate

/**
 * What the user's sentences keep: in each decoder sentence, the user's pinned words, firsts after the word before,
 * or sole hand-added words over a stretch replace the decoder's (我→指导 twice: wozhidao reads 我指导). A sentence
 * whose word runs past either end of the stretch keeps its own (知道了 in zhidaole). Scores stay the decoder's.
 */
internal object Enforced {

    /** In the order that wins for the same stretch. */
    enum class Kind { PIN, FIRST, ADDED }

    /** [words] ([text]) over the graph from [from] to [to] ending at [ends]; a first after [prev], or anywhere (pin, added: null). */
    class Item(val from: Int, val to: Int, val words: IntArray, val ends: IntArray, val text: String, val prev: String?, val kind: Kind)

    /** [sentences] with [items] put in where both ends are word boundaries (and, for a first, the word before is [Item.prev]); overlapping items drop, the stronger kind wins a stretch, texts deduplicated. [start] is the graph's first place. */
    fun apply(sentences: List<Candidate>, items: List<Item>, prev: String, text: (Int) -> String, start: Int = 0): List<Candidate> {
        if (items.isEmpty()) return sentences
        return sentences.map { s -> applying(s, items, prev, text, start).fold(s) { c, item -> put(c, item, text, start) } }.distinctBy { it.text }
    }

    // the items applying to [s], left to right, settled among those alone
    private fun applying(s: Candidate, items: List<Item>, prev: String, text: (Int) -> String, start: Int): List<Item> {
        val bounded = items.filter { (it.from == start || s.ends.contains(it.from)) && s.ends.contains(it.to) }
        // the word before each place: the sentence's, or the last of an item ending there
        val before = HashMap<Int, MutableSet<String>>()
        fun saw(at: Int, word: String?) {
            if (word != null) before.getOrPut(at) { HashSet() } += word
        }
        saw(start, prev)
        for (i in s.ends.indices) saw(s.ends[i], wordText(s.words[i], text))
        // a first after a pin or added word: only one that stands
        val anywhere = settle(bounded.filter { it.prev == null })
        for (item in anywhere) saw(item.to, wordText(item.words.last(), text))
        val firsts = bounded.filter { it.prev != null && before[it.from]?.contains(it.prev) == true }
        return settle(anywhere + firsts).sortedBy { it.from }
    }

    /** Those that stand: overlapping stretches drop both; one stretch keeps the stronger kind. */
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

    // [item] in place of [c]'s words over its stretch, if both ends are boundaries and, for a first, its prev matches
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

    // where [c]'s word [k] starts in its text, counted from whichever side has no as-typed text
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
