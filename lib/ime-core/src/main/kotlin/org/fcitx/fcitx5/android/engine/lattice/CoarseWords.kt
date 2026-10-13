/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import java.util.BitSet

/**
 * Words no one wants offered unasked: sexual slang, porn's words, hard insults (the list
 * `coarse.txt` beside this class). The models learned them from the web, where they are common:
 * 小穴 was predicted after 妈妈的, and came before 小雪 for `xiaoxue`, 射精 first for `shejing`. Such
 * a word ([unasked]) is never predicted; typed, it is [PENALTY] lower in the decoder, so another
 * reading is read where there is one, and a candidate with one comes after the first page, still
 * there to be typed. English never offers or corrects to one. Once the user typed one it is theirs,
 * as any other word: their choice.
 */
class CoarseWords private constructor(private val ids: BitSet, private val texts: Set<String>) {

    /** Whether [word], an id of the vocabulary this was made for, is one of the list's. */
    fun has(word: Int): Boolean = word >= 0 && ids[word]

    /** Whether [text] is one of the list's: a word the vocabulary lacks, the user's, has no id here. */
    fun has(text: String): Boolean = text in texts

    /** The length of the longest of the list's words. */
    val longest: Int = texts.maxOfOrNull { it.length } ?: 0

    /**
     * Whether [word] (of a vocabulary of [vocabularySize], or the [user]'s past it) is one of the
     * list's the user never typed.
     */
    fun unasked(word: Int, user: UserWords?, vocabularySize: Int): Boolean {
        // asked of every arc of a lattice, nearly none of the list: the list first, the counts after
        val listed = has(word) || word >= vocabularySize && user != null && has(user.text(word))
        return listed && (user == null || user.count(word) <= 0f)
    }

    companion object {
        /**
         * Added to a coarse word's score in the decoder, as [Penalties] are: 10,000 times less
         * likely, so 妈妈的小学 is read for `mamadexiaoxue`, where at -1.5 the model, which has 小穴
         * often after 的, kept it.
         */
        const val PENALTY = -4f

        val NONE = CoarseWords(BitSet(), emptySet())

        /** The list, a word a line, `#` starting a comment. */
        val LIST: List<String> by lazy { listLines(CoarseWords::class.java, "coarse.txt") }

        /** The lines of the resource [name] beside [owner], trimmed, those blank or a comment (`#`) left out. */
        internal fun listLines(owner: Class<*>, name: String): List<String> {
            val stream = requireNotNull(owner.getResourceAsStream(name)) { name }
            return stream.bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            }
        }

        /** The list's words that [find] has an id for (none: -1). */
        fun of(find: (String) -> Int, words: List<String> = LIST): CoarseWords {
            val ids = BitSet()
            for (word in words) find(word).takeIf { it >= 0 }?.let { ids.set(it) }
            return CoarseWords(ids, words.toHashSet())
        }
    }
}
