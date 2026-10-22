/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

/**
 * The last few corrections English made in a field (U-31, 「过后点回这个词换回原词」): a tap that
 * puts the cursor right after one again, a space after it or not, finds it ([at]), for the word
 * typed to be offered in its place. A correction is known by the text before it as the session
 * knew it ([Made.lead]), so the same word in another field, or elsewhere in this one, is not taken
 * for it; one at the field's start ([Made.fieldStart]) only where nothing else is before it.
 */
internal class Corrections {
    /** [word] committed for [typed] after [lead], by the commits [seqs] (EnglishUser.commit). */
    class Made(val typed: String, val word: String, val lead: String, val fieldStart: Boolean, val seqs: List<Long>)

    /** [made] found before the cursor, [spaced] if a space is after it there. */
    class Found(val made: Made, val spaced: Boolean) {
        /** How much before the cursor it takes: the word, its space too. */
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

    /**
     * [word] committed for [typed] by the commits [seqs], after [lead] ([Corrections.lead]): a
     * correction, for a backspace right after to take back, whether the field is [learning] or
     * not; kept to be taken back later by a tap only where it [learning], and where something of
     * the text before it is known: with nothing known, the same word anywhere would be taken for it.
     */
    fun made(typed: String, word: String, lead: Pair<String, Boolean>, seqs: List<Long>, learning: Boolean): Made? {
        if (word == typed) return null
        val m = Made(typed, word, lead.first, lead.second, seqs)
        if (learning && (lead.first.isNotEmpty() || lead.second)) add(m)
        return m
    }

    /** The newest correction right before the cursor, where the field has [before] before it. */
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
        /** How many are kept: a field's last few. */
        const val SIZE = 8

        /** How much of the text before a correction tells it apart. */
        const val LEAD = 16

        /**
         * The end of the field's text before the cursor as the session knows it ([recent], the field
         * cleared itself at [clearedAt] if not -1, its start known if [startKnown]), and whether it is
         * all of it: what tells a correction made after it apart. Not what a field that cleared itself had.
         */
        fun lead(recent: String, clearedAt: Int, startKnown: Boolean): Pair<String, Boolean> {
            val own = if (clearedAt >= 0) recent.substring(clearedAt) else recent
            val whole = (startKnown || clearedAt >= 0) && own.length <= LEAD
            return own.takeLast(LEAD) to whole
        }
    }
}
