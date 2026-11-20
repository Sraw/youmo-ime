/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

/** ARPA words and n-grams, checked: each word a unigram once, every n-gram word a unigram, probabilities finite. */
internal class NgramWords {
    private val ids = HashMap<String, Int>()
    private val list = ArrayList<String>()

    val words: List<String> get() = list

    val tables = NgramTables { list[it] }

    fun unigram(word: String, prob: Float, backoff: Float) {
        finite(prob, backoff) { word }
        require(ids.put(word, list.size) == null) { "unigram \"$word\" added twice" }
        list += word
        tables.unigram(prob, backoff)
    }

    fun bigram(prev: String, word: String, prob: Float, backoff: Float) {
        finite(prob, backoff) { "$prev $word" }
        tables.bigram(id(prev), id(word), prob, backoff)
    }

    fun trigram(prev2: String, prev: String, word: String, prob: Float) {
        finite(prob, 0f) { "$prev2 $prev $word" }
        tables.trigram(id(prev2), id(prev), id(word), prob)
    }

    operator fun get(word: String): Int? = ids[word]

    fun id(word: String): Int = requireNotNull(ids[word]) { "\"$word\" is not a unigram" }

    fun add(word: String): Int = ids.getOrPut(word) { list.size.also { list += word } }

    // a NaN would poison the quantiser: it equals nothing, so it breaks every bin it lands in
    private inline fun finite(prob: Float, backoff: Float, ngram: () -> String) =
        require(prob.isFinite() && backoff.isFinite()) { "\"${ngram()}\": $prob $backoff" }
}
