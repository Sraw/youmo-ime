/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.learn.Phrases

/**
 * The whole phrases committed often enough to go on with, by their first character: after text ending with a
 * phrase's start from a word's start on, its rest (two characters or more) is offered first (the rest of 你好呀亲爱的
 * after 你, 你好 or 你好呀). Only word starts count: 谢谢你 gives it, 迷你 does not.
 */
class Continuations {
    class Phrase(val text: String, val count: Int, val lastDay: Int)

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

    fun of(text: String): Phrase? = if (text.isEmpty()) null else byFirst[text.codePointAt(0)]?.get(text)

    /** What may follow [before] with words at [starts] (ascending, as `TextWords.boundaries`), [last] the word put in last: at most [limit] phrases, most used then latest, each [offered], from its longest start. */
    fun find(before: String, starts: IntArray, last: String, limit: Int, offered: (String) -> Boolean): List<Found> {
        if (byFirst.isEmpty() || before.isEmpty() || limit <= 0) return emptyList()
        val latest = before.length - last.length
        val found = HashMap<String, Found>()
        // the last word's own start too, where the split put it in another (你好 split 你 好)
        val starting = if (last.isNotEmpty() && before.endsWith(last)) starts.toList() + latest else starts.toList()
        val froms = starting.filter { it in 0..latest && it < before.length && !Character.isLowSurrogate(before[it]) }.distinct().sorted()
            .map { before.substring(it) }.filter { it.codePointCount(0, it.length) <= Phrases.MAX - MIN_REST }
        for (from in froms) {
            for (p in byFirst[from.codePointAt(0)]?.values.orEmpty()) {
                val rest = if (p.text.startsWith(from)) p.text.substring(from.length) else ""
                if (p.text !in found && rest.codePointCount(0, rest.length) >= MIN_REST && offered(p.text)) found[p.text] = Found(p.text, rest, p.count)
            }
        }
        // two phrases of the same rest are one candidate: the one used most
        return found.values.sortedWith(compareByDescending<Found> { it.count }.thenByDescending { of(it.whole)?.lastDay ?: 0 }.thenBy { it.whole })
            .distinctBy { it.rest }.take(limit)
    }

    companion object {
        /** The end of [text] a phrase's start may be in: no longer than a phrase in code points, no character cut in two. */
        fun tail(text: String): String {
            var start = text.length
            var taken = 0
            while (start > 0 && taken < Phrases.MAX) {
                val pair = start >= 2 && Character.isLowSurrogate(text[start - 1]) && Character.isHighSurrogate(text[start - 2])
                start -= if (pair) 2 else 1
                taken++
            }
            return text.substring(start)
        }

        /** One character left is a word's 联想's, not a phrase's. */
        const val MIN_REST = 2

        /** Phrases offered in one place at most, so the first page keeps the words. */
        const val LIMIT = 2
    }
}
