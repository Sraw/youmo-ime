/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.lattice.TextWords

/** Bigrams and trigrams for [KneserNey] and [Mixer]: word ids below [start], a virtual start no n-gram is kept from. */
interface NgramCounts {
    val start: Int
    val bigrams: LongIndex
    val bigramCount: IntColumn
    val trigrams: LongIndex
    val trigramCount: IntColumn

    /** The model's `<s>`: its bigrams count as seen, as KenLM has it, having no continuation counts. -1 for none. */
    val sentenceStart: Int get() = -1
}

/** N-gram counts of chat text split into [model]'s likeliest words, each run counted from a virtual [start]. */
class ChatCounts(private val model: ArpaModel) : NgramCounts {

    private val ids = WordIds(model.words)
    // as the engine reads text (TextWords): a few names are longer
    private val longest = minOf(TextWords.MAX_WORD, model.words.maxOf { it.length })

    init {
        // the start's id must fit a key as well, and not wrap round to word 0
        require(model.size < NgramKey.MAX_WORDS) { "${model.size} words leave no id for the start" }
    }

    override val start = model.size
    override val bigrams = LongIndex(1 shl 20)
    override val bigramCount = IntColumn(1 shl 20)
    override val trigrams = LongIndex(1 shl 22)
    override val trigramCount = IntColumn(1 shl 22)

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
        var i = 0
        while (i < chars.length) {
            // a char past U+FFFF (dict_extb's) is two: a word by itself whole, either half alone none
            val width = Character.charCount(chars.codePointAt(i))
            if (ids.find(chars, i, i + width) < 0) {
                if (i > begin) count(chars, begin, i)
                begin = i + width
            }
            i += width
        }
        if (chars.length > begin) count(chars, begin, chars.length)
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
                val id = ids.find(text, begin + e - length, begin + e)
                if (id < 0) continue
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
}

/** [words]' ids found from the chars where they lie, not from strings made of them, as ime-core's WordIndex. */
private class WordIds(private val words: List<String>) {

    // id + 1 of the word hashed there, 0 for none; at most two thirds full
    private val slots = IntArray(Integer.highestOneBit(maxOf(words.size, 1) * 3 / 2) shl 1)
    private val mask = slots.size - 1
    private val shift = Integer.SIZE - Integer.numberOfTrailingZeros(slots.size)

    init {
        for (id in words.indices) {
            val word = words[id]
            var slot = hash(word, 0, word.length)
            while (slots[slot] != 0 && words[slots[slot] - 1] != word) slot = (slot + 1) and mask
            if (slots[slot] == 0) slots[slot] = id + 1
        }
    }

    /** The id of [text]'s chars from [from] to [to], or -1. */
    fun find(text: String, from: Int, to: Int): Int {
        var slot = hash(text, from, to)
        while (true) {
            val id = slots[slot] - 1
            if (id < 0) return -1
            val word = words[id]
            if (word.length == to - from && text.regionMatches(from, word, 0, word.length)) return id
            slot = (slot + 1) and mask
        }
    }

    private fun hash(text: String, from: Int, to: Int): Int {
        var h = to - from
        for (i in from until to) h = h * MULTIPLIER + text[i].code
        // the high bits of a Fibonacci hash: CJK chars are consecutive code points
        return (h * FIBONACCI) ushr shift
    }

    private companion object {
        const val MULTIPLIER = 31
        const val FIBONACCI = -0x61c88647 // 2^32 / φ
    }
}

/** Interpolated Kneser-Ney over [counts], one discount an order; unseen words share what the unigrams' discount leaves. */
class KneserNey(private val counts: NgramCounts) {

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
            // a bigram from the virtual start has no word before it: no continuation count
            val c = count2(i, v)
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
        val c = if (bigram >= 0) count2(bigram, v) else 0
        return (maxOf(c - discount2, 0.0) + discount2 * after2[v] * p1(w)) / total2[v]
    }

    fun p3(u: Int, v: Int, w: Int): Double {
        val context = counts.bigrams.indexOf(NgramKey.of(u, v))
        if (context < 0 || total3[context] == 0) return p2(v, w)
        val trigram = counts.trigrams.indexOf(NgramKey.of(u, v, w))
        val c = if (trigram >= 0) counts.trigramCount[trigram] else 0
        return (maxOf(c - discount3, 0.0) + discount3 * after3[context] * p2(v, w)) / total3[context]
    }

    // a bigram's count: how many different words came before it, or, nothing ever before it, how often it was seen
    private fun count2(i: Int, v: Int) = if (v == counts.sentenceStart) counts.bigramCount[i] else before2[i]

    private companion object {
        const val FALLBACK = 0.5

        fun discount(of: IntArray): Double =
            if (of[1] + 2 * of[2] == 0 || of[1] == 0) FALLBACK else of[1].toDouble() / (of[1] + 2 * of[2])
    }
}
