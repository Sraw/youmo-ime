/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.learn.Phrases

/**
 * The whole phrases committed often enough to go on with (U-73), by their first character: after
 * the text before the cursor ends with a phrase's start, a word or several of it from a word's
 * start on, what is left of it (two characters or more) is offered first, the rest of 你好呀亲爱的
 * after 你, 你好 or 你好呀. Only the start counts: 谢谢你 gives it too (C51), 迷你 does not, its
 * last word being 迷你. [HabitCounts] keeps it as the learning record has the phrases.
 */
class Continuations {
    /** A phrase of [count] uses, last on typing day [lastDay]. */
    class Phrase(val text: String, val count: Int, val lastDay: Int)

    /** What is left ([rest]) of the phrase [whole] after the text before. */
    class Found(val whole: String, val rest: String, val count: Int)

    private val byFirst = HashMap<Int, HashMap<String, Phrase>>()

    fun isEmpty() = byFirst.isEmpty()

    fun clear() = byFirst.clear()

    /** [text] used whole [count] times, last on [lastDay]; offered while [count] is at least [least]. */
    fun set(text: String, count: Int, lastDay: Int, least: Int) {
        if (text.isEmpty()) return
        val first = text.codePointAt(0)
        if (count >= least) {
            byFirst.getOrPut(first) { HashMap(2) }[text] = Phrase(text, count, lastDay)
        } else {
            val those = byFirst[first] ?: return
            those.remove(text)
            if (those.isEmpty()) byFirst.remove(first)
        }
    }

    /** The phrase of [text], null for none offered. */
    fun of(text: String): Phrase? = if (text.isEmpty()) null else byFirst[text.codePointAt(0)]?.get(text)

    /**
     * What may follow [before], whose words start at [starts] (offsets, ascending, as
     * `TextWords.boundaries` gives them), [last] the word put in last, which what is gone on from
     * covers whole: at most [limit] phrases, most used first, then the last used, each [offered]
     * (not turned off, nothing blocked in it); of each its longest start [before] ends with.
     */
    fun find(before: String, starts: IntArray, last: String, limit: Int, offered: (String) -> Boolean): List<Found> {
        if (byFirst.isEmpty() || before.isEmpty() || limit <= 0) return emptyList()
        val latest = before.length - last.length
        val found = HashMap<String, Found>()
        // the last word's own start too, where the split put it in another (你好 split 你 好)
        val starting = if (last.isNotEmpty() && before.endsWith(last)) starts.toList() + latest else starts.toList()
        // the longest start first: a phrase found keeps it
        val froms = starting.filter { it in 0..latest && it < before.length && !Character.isLowSurrogate(before[it]) }.distinct().sorted()
            .map { before.substring(it) }.filter { it.codePointCount(0, it.length) <= Phrases.MAX - MIN_REST }
        for (from in froms) {
            for (p in byFirst[from.codePointAt(0)]?.values.orEmpty()) {
                val rest = if (p.text.startsWith(from)) p.text.substring(from.length) else ""
                if (p.text !in found && rest.codePointCount(0, rest.length) >= MIN_REST && offered(p.text)) found[p.text] = Found(p.text, rest, p.count)
            }
        }
        // two phrases of the same rest (你好呀亲爱的 and 好呀亲爱的 after 你好呀) are one candidate: the one used most
        return found.values.sortedWith(compareByDescending<Found> { it.count }.thenByDescending { of(it.whole)?.lastDay ?: 0 }.thenBy { it.whole })
            .distinctBy { it.rest }.take(limit)
    }

    companion object {
        /**
         * The end of the text before the cursor [text] a phrase's start may be in: no longer than a
         * phrase, a character not cut in two. What [find] is given, by the sessions and the evaluation alike.
         */
        fun tail(text: String): String {
            val tail = text.takeLast(Phrases.MAX)
            return if (tail.isNotEmpty() && Character.isLowSurrogate(tail[0])) tail.substring(1) else tail
        }

        /** What is left of a phrase to offer it: one character is a word's 联想's (U-71). */
        const val MIN_REST = 2

        /** D-C2: phrases offered in one place at most, so the first page keeps the words. */
        const val LIMIT = 2
    }
}
