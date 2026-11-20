/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

/**
 * The rows a table or handwriting commit learns: each [Pick] under its keys alone, no word before it
 * (they learn by what was typed or written, not by 前文), corrected where another was shown first; and the
 * [whole] text after [prev], under no keys, for 联想 and pair counts. Their words have no reading: their
 * choices neither count for pinyin's order nor make a word.
 */
object KeyedRows {

    /** [text] put in for [keys], [how] (A first, B another), [shown] the first shown there; [own] the table's own first there. */
    class Pick(val keys: String, val text: String, val how: Char, val shown: String, val own: Boolean = false)

    fun of(kind: String, picks: List<Pick>, whole: List<Word>, prev: String, origin: Int = Row.DICTIONARY): List<Row> {
        // no kind (an ime-eval table, no input method's): nothing to file it under
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
     * Handwriting's keys for a written character: the recognizer's first [n] readings sorted by code point
     * and joined, so the same characters in any order are the same input. No separator needed: each reading
     * is one character, so no two sets join alike.
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
