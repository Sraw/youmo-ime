/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import java.util.BitSet

/**
 * Words no one wants offered unasked: sexual slang, porn terms, hard insults (`coarse.txt` beside this
 * class); the web-trained models find them common (小穴 before 小雪 for `xiaoxue`). Such a word is never
 * predicted; typed, it is [PENALTY] lower in the decoder, so another reading wins where there is one and
 * it falls past the first page. English never offers or corrects to one. Once the user types one it is theirs.
 */
class CoarseWords private constructor(private val ids: BitSet, private val texts: Set<String>) {

    /** [word] is an id of the vocabulary this was made for. */
    fun has(word: Int): Boolean = word >= 0 && ids[word]

    /** By text: user words lacking from the vocabulary have no id here. */
    fun has(text: String): Boolean = text in texts

    val longest: Int = texts.maxOfOrNull { it.length } ?: 0

    /** Listed and never typed by the user; [word] in a vocabulary of [vocabularySize], or the [user]'s past it. */
    fun unasked(word: Int, user: UserWords?, vocabularySize: Int): Boolean {
        // asked for every lattice arc, nearly none listed: the list first, counts after. Merged-dictionary words by
        // id, looked up once: a String per arc would be garbage on every key
        val listed = has(word) || word >= vocabularySize && user != null &&
            if (user.isMerged(word)) mergedOf(user).binarySearch(word) >= 0 else has(user.text(word))
        return listed && (user == null || user.count(word) <= 0f)
    }

    // the merged dictionaries' ids of the listed texts, sorted, for the [StartWords] they came from: searched,
    // not a set of boxed ids (past 2^27 none is a cached Integer)
    private var mergedFor: StartWords? = null
    private var mergedIds = IntArray(0)

    private fun mergedOf(user: UserWords): IntArray {
        val merged = user.mergedWords
        if (merged !== mergedFor) {
            val out = ArrayList<Int>()
            texts.forEach { text -> user.forEachMergedOfText(text) { out += it } }
            mergedIds = out.toIntArray().apply { sort() }
            mergedFor = merged
        }
        return mergedIds
    }

    companion object {
        /** 10,000 times less likely: at -1.5 the model, with 小穴 often after 的, still read `mamadexiaoxue` so. */
        const val PENALTY = -4f

        val NONE = CoarseWords(BitSet(), emptySet())

        /** A word per line, `#` starting a comment. */
        val LIST: List<String> by lazy { listLines(CoarseWords::class.java, "coarse.txt") }

        /** The trimmed lines of resource [name] beside [owner], minus blanks and `#` comments. */
        internal fun listLines(owner: Class<*>, name: String): List<String> {
            val stream = requireNotNull(owner.getResourceAsStream(name)) { name }
            return stream.bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            }
        }

        /** [find] gives an id, or -1. */
        fun of(find: (String) -> Int, words: List<String> = LIST): CoarseWords {
            val ids = BitSet()
            for (word in words) find(word).takeIf { it >= 0 }?.let { ids.set(it) }
            return CoarseWords(ids, words.toHashSet())
        }
    }
}
