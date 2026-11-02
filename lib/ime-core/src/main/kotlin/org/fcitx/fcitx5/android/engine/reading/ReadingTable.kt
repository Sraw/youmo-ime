/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

/**
 * Which tone each character of a word has, read a given way: what the candidates' readings show
 * and what a typed tone is checked against. There is one table now, [MAINLAND] (the mainland's
 * standard readings, compiled into pinyin data); a keyboard may one day choose another (Taiwan's),
 * hence a name and an interface rather than the data's class.
 */
interface ReadingTable {

    val name: String

    /**
     * The tone of each code point of [text] read as [syllables] (ids of
     * [org.fcitx.fcitx5.android.engine.pinyin.Syllables], one a code point), [ToneMark.NONE] for one
     * not known; [word] is its id in the data, or -1 for a word the data has not got (the user's),
     * which goes by its characters alone. Null if [syllables] are not one a code point.
     */
    fun tones(text: String, syllables: IntArray, word: Int = -1): ByteArray?

    /** Whether [codePoint] read as [syllable] may have [tone]; true for a character the table does not know. */
    fun allows(codePoint: Int, syllable: Int, tone: Int): Boolean

    companion object {
        const val MAINLAND = "mainland"
    }
}
