/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

/**
 * A backoff model's words and its n-grams by them, as [PinyinDataBuilder] and [EnglishDataBuilder]
 * take them from an ARPA file, checked alike: each word a unigram once, every word of an n-gram a
 * unigram, every probability finite. The words are numbered in the order added ([words]), and
 * [tables] writes the model.
 */
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

    /** [word]'s id, or null if it was not added. */
    operator fun get(word: String): Int? = ids[word]

    /** [word]'s id, which it must have. */
    fun id(word: String): Int = requireNotNull(ids[word]) { "\"$word\" is not a unigram" }

    /** [word]'s id, numbered after every word so far if it had none: a dictionary's word the model lacks. */
    fun add(word: String): Int = ids.getOrPut(word) { list.size.also { list += word } }

    // a NaN would poison the quantiser: it equals nothing, so it breaks every bin it lands in
    private inline fun finite(prob: Float, backoff: Float, ngram: () -> String) =
        require(prob.isFinite() && backoff.isFinite()) { "\"${ngram()}\": $prob $backoff" }
}
