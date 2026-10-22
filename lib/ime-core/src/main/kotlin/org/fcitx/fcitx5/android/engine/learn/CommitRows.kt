/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

/**
 * The rows one commit learns (PLAN §2.1 「记在哪个编码下」, impl/design-1a.md §3), from the picks
 * that made it, as the session saw them. Positions are the session's input's.
 *
 * - Level 1: each pick, under its keys, after the word before it.
 * - Level 2: the whole commit, under all its keys, corrected if any pick was.
 * - Level 3: each word of a pick that the first sentence shown has as a word too (both its ends
 *   are ends there), under its own keys: 老师 of 老师指导 picked over 老师知道 is used, 指导 corrected.
 *   A word the first shown has no ends around (点 of 点｜了 over 带来) is not learned on its own.
 *
 * A row is learned once a commit, a pick of one word being all three: level 1 says whether it
 * was corrected, level 3 only adds the other words.
 */
object CommitRows {

    /** The first sentence shown when a pick was made: its words' texts, and where each ends, from [from]. */
    class Shown(val from: Int, val texts: List<String>, val ends: IntArray)

    /**
     * A pick: its [words], from [from], each ending at [ends]; [how] it was committed ('A' to 'H');
     * the first of the candidates it was picked from ([reference]: those ending where it ends,
     * phrases and emoji aside, or for 'H' the first when a syllable was taken); the first sentence
     * shown then ([shown]), if any; [own] the decoder's own first there, before the habits ranked it (F2739).
     */
    class Pick(val from: Int, val words: List<Word>, val ends: IntArray, val how: Char, val reference: String, val shown: Shown?, val own: Boolean = false) {
        val to get() = ends.last()
        val text get() = words.joinToString("") { it.text }
    }

    /**
     * The rows of a commit of [picks] after [prev] ("" for none): [code] says what the keys from
     * one position to another were, read as [words] (a glide: their pinyin), [origin] where a
     * text comes from ([Row.DICTIONARY] and so on).
     */
    fun build(
        prev: String,
        picks: List<Pick>,
        code: (from: Int, to: Int, words: List<Word>) -> Code,
        origin: (Words) -> Int,
    ): List<Row> {
        if (picks.isEmpty()) return emptyList()
        val rows = LinkedHashMap<Triple<Code, String, Words>, Row>()
        fun add(row: Row) {
            val key = Triple(row.code, row.prev, row.words)
            val had = rows[key]
            // the first level to give a row says whether it was corrected; the others only add their level
            rows[key] = if (had == null) row else Row(had.flags or (row.flags and Row.LEVELS), had.code, had.prev, had.words, had.how, had.origin, had.shown)
        }

        val prevs = ArrayList<String>()
        for (p in picks) {
            val before = prevAfter(prev, picks.subList(0, prevs.size))
            prevs += before
            val words = Words(p.words)
            val corrected = p.text != p.reference && p.reference.isNotEmpty()
            val own = if (corrected && p.own) Row.OWN_FIRST else 0
            add(Row(Row.PICK or (if (corrected) Row.CORRECTED else 0) or own, code(p.from, p.to, p.words), before, words, p.how, origin(words), p.reference))
        }

        val all = Words(picks.flatMap { it.words })
        val anyCorrected = rows.values.any { it.corrected }
        val how = when {
            picks.any { it.how == 'H' } -> 'H'
            picks.size == 1 -> picks[0].how
            else -> 'C'
        }
        val shown = if (picks.size == 1) picks[0].reference else ""
        add(Row(Row.WHOLE or (if (anyCorrected) Row.CORRECTED else 0), code(picks[0].from, picks.last().to, all.list), prev, all, how, origin(all), shown))

        for ((k, p) in picks.withIndex()) words(p, prevs[k], code, origin, ::add)
        return rows.values.toList()
    }

    /**
     * The word a pick after [picks] follows, as its level-1 row files it: the commit's [prev] before
     * the first, else the last word of the last pick (impl/design-1b.md M2). What a session ranks
     * the next pick's candidates after, so the order shown is the one the row learns into.
     */
    fun prevAfter(prev: String, picks: List<Pick>): String = picks.lastOrNull()?.words?.last()?.text ?: prev

    // level 3: the words of [p] the first sentence shown has as words too, after [prev]
    private inline fun words(p: Pick, prev: String, code: (Int, Int, List<Word>) -> Code, origin: (Words) -> Int, add: (Row) -> Unit) {
        val sentence = p.shown ?: return
        val bounds = HashSet<Int>().apply {
            add(sentence.from)
            sentence.ends.forEach { add(it) }
        }
        var wordPrev = prev
        for (i in p.words.indices) {
            val start = if (i == 0) p.from else p.ends[i - 1]
            val end = p.ends[i]
            val word = p.words[i]
            if (start in bounds && end in bounds) {
                val there = shownBetween(sentence, start, end)
                val words = Words(listOf(word))
                val flags = Row.WORD or (if (there != word.text) Row.CORRECTED else 0)
                add(Row(flags, code(start, end, listOf(word)), wordPrev, words, p.how, origin(words), there))
            }
            wordPrev = word.text
        }
    }

    // the text of [shown]'s words from [start] to [end], both ends of its words
    private fun shownBetween(shown: Shown, start: Int, end: Int): String {
        val text = StringBuilder()
        var from = shown.from
        for (i in shown.texts.indices) {
            if (from >= start && shown.ends[i] <= end) text.append(shown.texts[i])
            from = shown.ends[i]
        }
        return text.toString()
    }
}
