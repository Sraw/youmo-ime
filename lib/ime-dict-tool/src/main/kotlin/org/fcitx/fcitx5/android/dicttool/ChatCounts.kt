/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

/**
 * N-gram counts of text split into [model]'s words: what people write to each other, which the
 * model (news and the like) has little of.
 *
 * Each run of text between chars that are no word by themselves (a letter, a digit, an emoji, a
 * char only ever inside longer words) is split as the
 * model finds likeliest, the sum of its words' unigram scores, and counted from a virtual start,
 * [start]: counted only for how many different words come before a word (Kneser-Ney's
 * continuation counts), never an n-gram of its own, as the model has no sentence start.
 */
class ChatCounts(private val model: ArpaModel) {

    private val ids = HashMap<String, Int>(model.size * 2).apply { model.words.forEachIndexed { id, w -> put(w, id) } }
    private val longest = minOf(MAX_WORD, model.words.maxOf { it.length })

    init {
        // the start's id must fit a key as well, and not wrap round to word 0
        require(model.size < NgramKey.MAX_WORDS) { "${model.size} words leave no id for the start" }
    }

    val start = model.size
    val bigrams = LongIndex(1 shl 20)
    val bigramCount = IntColumn(1 shl 20)
    val trigrams = LongIndex(1 shl 22)
    val trigramCount = IntColumn(1 shl 22)

    var tokens = 0L
        private set

    // reused from run to run
    private var best = DoubleArray(64)
    private var from = IntArray(64)
    private var word = IntArray(64)
    private var run = IntArray(64)

    /** Counts [text]; spaces in it are dropped, as a corpus split into words has them. */
    fun add(text: String) {
        val chars = text.replace(" ", "")
        var begin = 0
        for (i in 0..chars.length) {
            if (i == chars.length || chars[i].toString() !in ids) {
                if (i > begin) count(chars, begin, i)
                begin = i + 1
            }
        }
    }

    private fun count(text: String, begin: Int, end: Int) {
        val n = end - begin
        if (best.size <= n) {
            best = DoubleArray(n + 1)
            from = IntArray(n + 1)
            word = IntArray(n + 1)
            run = IntArray(n + 1)
        }
        best[0] = 0.0
        for (e in 1..n) {
            best[e] = Double.NEGATIVE_INFINITY
            for (length in 1..minOf(longest, e)) {
                val id = ids[text.substring(begin + e - length, begin + e)] ?: continue
                val score = best[e - length] + model.unigramProb[id]
                if (score > best[e]) {
                    best[e] = score
                    from[e] = e - length
                    word[e] = id
                }
            }
        }
        // the words from the last back
        var words = 0
        var e = n
        while (e > 0) {
            run[words++] = word[e]
            e = from[e]
        }
        var prev2 = -1
        var prev = start
        for (i in words - 1 downTo 0) {
            val w = run[i]
            bigramCount.add(bigrams.add(NgramKey.of(prev, w)), 1)
            if (prev2 >= 0) trigramCount.add(trigrams.add(NgramKey.of(prev2, prev, w)), 1)
            prev2 = prev
            prev = w
        }
        tokens += words
    }

    private companion object {
        // as the engine reads text (TextWords): a few names are longer
        const val MAX_WORD = 8
    }
}

/**
 * Interpolated Kneser-Ney over [counts], one discount an order (n1 / (n1 + 2 n2) of that order's
 * counts): the highest order by what was counted, the lower ones by how many different words
 * came before. Every word of the model has a probability: each, seen or not, gets an equal share
 * of what the unigrams' discount leaves.
 */
class KneserNey(private val counts: ChatCounts) {

    private val vocabulary = counts.start
    // continuation counts: how many different words came before
    private val before1 = IntArray(vocabulary + 1)
    private val before2 = IntColumn(counts.bigrams.size)
    // what follows a context: count summed, and how many different words
    private val total2 = IntArray(vocabulary + 1)
    private val after2 = IntArray(vocabulary + 1)
    private val total3 = IntColumn(counts.bigrams.size)
    private val after3 = IntColumn(counts.bigrams.size)
    private val total1: Long
    private val seen1: Int
    private val discount1: Double
    private val discount2: Double
    private val discount3: Double

    init {
        val trigrams = counts.trigrams
        val of3 = IntArray(3)
        for (i in 0 until trigrams.size) {
            val key = trigrams.keyAt(i)
            val u = NgramKey.word(key, 3, 0)
            val v = NgramKey.word(key, 3, 1)
            val w = NgramKey.word(key, 3, 2)
            before2.add(counts.bigrams.indexOf(NgramKey.of(v, w)), 1)
            val context = counts.bigrams.indexOf(NgramKey.of(u, v))
            val c = counts.trigramCount[i]
            total3.add(context, c)
            after3.add(context, 1)
            if (c <= 2) of3[c]++
        }
        val of2 = IntArray(3)
        for (i in 0 until counts.bigrams.size) {
            val key = counts.bigrams.keyAt(i)
            val v = NgramKey.word(key, 2, 0)
            val w = NgramKey.word(key, 2, 1)
            before1[w]++
            // a bigram from the start has no word before it: no continuation count
            val c = before2[i]
            if (c > 0) {
                total2[v] += c
                after2[v]++
                if (c <= 2) of2[c]++
            }
        }
        val of1 = IntArray(3)
        var total = 0L
        var seen = 0
        for (w in 0 until vocabulary) {
            val c = before1[w]
            if (c == 0) continue
            total += c
            seen++
            if (c <= 2) of1[c]++
        }
        total1 = total
        seen1 = seen
        discount1 = discount(of1)
        discount2 = discount(of2)
        discount3 = discount(of3)
    }

    fun p1(w: Int): Double {
        if (total1 == 0L) return 1.0 / vocabulary
        return (maxOf(before1[w] - discount1, 0.0) + discount1 * seen1 / vocabulary) / total1
    }

    fun p2(v: Int, w: Int): Double {
        if (total2[v] == 0) return p1(w)
        val bigram = counts.bigrams.indexOf(NgramKey.of(v, w))
        val c = if (bigram >= 0) before2[bigram] else 0
        return (maxOf(c - discount2, 0.0) + discount2 * after2[v] * p1(w)) / total2[v]
    }

    fun p3(u: Int, v: Int, w: Int): Double {
        val context = counts.bigrams.indexOf(NgramKey.of(u, v))
        if (context < 0 || total3[context] == 0) return p2(v, w)
        val trigram = counts.trigrams.indexOf(NgramKey.of(u, v, w))
        val c = if (trigram >= 0) counts.trigramCount[trigram] else 0
        return (maxOf(c - discount3, 0.0) + discount3 * after3[context] * p2(v, w)) / total3[context]
    }

    private companion object {
        const val FALLBACK = 0.5

        fun discount(of: IntArray): Double =
            if (of[1] + 2 * of[2] == 0 || of[1] == 0) FALLBACK else of[1].toDouble() / (of[1] + 2 * of[2])
    }
}
