/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

/** A field's last few corrections, so a tap right after one offers the typed word back; known by the text before it, so the same word elsewhere is not taken for it. */
internal class Corrections {
    /** [word] committed for [typed] after [lead], by commits [seqs] (EnglishUser.commit). */
    class Made(val typed: String, val word: String, val lead: String, val fieldStart: Boolean, val seqs: List<Long>)

    class Found(val made: Made, val spaced: Boolean) {
        val length get() = made.word.length + if (spaced) 1 else 0
    }

    private val made = ArrayList<Made>()

    fun add(m: Made) {
        made += m
        if (made.size > SIZE) made.removeAt(0)
    }

    fun remove(m: Made) {
        made.remove(m)
    }

    fun clear() = made.clear()

    /** A correction a backspace may take back; kept for a later tap only if [learning] and its lead is known. */
    fun made(typed: String, word: String, lead: Pair<String, Boolean>, seqs: List<Long>, learning: Boolean): Made? {
        if (word == typed) return null
        val m = Made(typed, word, lead.first, lead.second, seqs)
        if (learning && (lead.first.isNotEmpty() || lead.second)) add(m)
        return m
    }

    fun at(before: String): Found? {
        val spaced = before.endsWith(' ')
        val end = if (spaced) before.dropLast(1) else before
        val m = made.lastOrNull { fits(it, end) } ?: return null
        return Found(m, spaced)
    }

    private fun fits(m: Made, end: String): Boolean {
        val tail = m.lead + m.word
        return if (m.fieldStart) end == tail else end.endsWith(tail)
    }

    companion object {
        const val SIZE = 8

        const val LEAD = 16

        /** The end of the text before the cursor as the session knows it, and whether that is all of it. */
        fun lead(recent: String, clearedAt: Int, startKnown: Boolean): Pair<String, Boolean> {
            val own = if (clearedAt >= 0) recent.substring(clearedAt) else recent
            val whole = (startKnown || clearedAt >= 0) && own.length <= LEAD
            return own.takeLast(LEAD) to whole
        }
    }
}
