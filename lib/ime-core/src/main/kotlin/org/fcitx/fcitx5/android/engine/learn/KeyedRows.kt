/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

/**
 * The rows a commit of a table or handwriting is learned by (PLAN §2.7): each [Pick] used under
 * its keys alone, no word before it (U-28, U-29: those learn by what was typed or written, not by
 * 前文), corrected where the first shown was another; and the [whole] text put in after [prev],
 * filed under no keys, for what follows a word (联想) and how often the word before a pair was
 * used. Their words have no reading: what they choose does not count for pinyin's order (D28,
 * user/HabitCounts), nor makes a word.
 */
object KeyedRows {

    /** [text] put in for [keys], [how] (A first, B another), the first shown there [shown]; [own] the table's own first there (F2739). */
    class Pick(val keys: String, val text: String, val how: Char, val shown: String, val own: Boolean = false)

    fun of(kind: String, picks: List<Pick>, whole: List<Word>, prev: String, origin: Int = Row.DICTIONARY): List<Row> {
        // no kind (a table of ime-eval's, no input method's): nothing to file it under
        if (kind.isEmpty()) return emptyList()
        val rows = picks.mapTo(ArrayList()) { p ->
            val corrected = if (p.shown.isNotEmpty() && p.shown != p.text) Row.CORRECTED else 0
            val own = if (corrected != 0 && p.own) Row.OWN_FIRST else 0
            Row(Row.PICK or corrected or own, Code(kind, p.keys), "", Words.of(p.text), p.how, Row.DICTIONARY, p.shown)
        }
        if (whole.isNotEmpty()) rows += Row(Row.WHOLE, Code(kind, ""), prev, Words(whole), picks.lastOrNull()?.how ?: Row.HOW_FIRST, origin, "")
        return rows
    }

    /**
     * Handwriting's keys for a character written (impl/design-1a.md §1 `hw`): the first [n] texts
     * [texts] the recognizer read it as, by code point, joined: the same three characters read
     * in any order are the same input. With no separator: each reading is one character (the
     * recognizer's classes are), so no two sets join alike (review C N5).
     */
    fun handwriting(texts: List<String>, n: Int = HANDWRITING_KEYS): String = texts.take(n).sortedWith(BY_CODE_POINT).joinToString("")

    const val HANDWRITING_KEYS = 3

    private val BY_CODE_POINT = Comparator<String> { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return@Comparator x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        (a.length - i).compareTo(b.length - j)
    }
}
