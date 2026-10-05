/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import java.io.BufferedReader
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/** Word ids side by side in a long, [BITS] bits each: an n-gram's key in a [LongIndex]. */
object NgramKey {
    const val BITS = 20
    const val MAX_WORDS = 1 shl BITS
    private const val MASK = MAX_WORDS - 1L

    fun of(a: Int, b: Int) = (a.toLong() shl BITS) or b.toLong()
    fun of(a: Int, b: Int, c: Int) = (a.toLong() shl (2 * BITS)) or (b.toLong() shl BITS) or c.toLong()

    /** The [i]th word of the [order] words packed in [key], the first 0. */
    fun word(key: Long, order: Int, i: Int) = ((key ushr ((order - 1 - i) * BITS)) and MASK).toInt()
}

/**
 * A trigram backoff model held whole, log10 as ARPA has it, for computing a new one from it
 * (see [Mixer]). Ids are the unigrams' order; n-grams are found through their [NgramKey]s.
 */
class ArpaModel(
    val words: List<String>,
    val unigramProb: DoubleArray,
    val unigramBackoff: DoubleArray,
    val bigrams: LongIndex,
    val bigramProb: DoubleColumn,
    val bigramBackoff: DoubleColumn,
    val trigrams: LongIndex,
    val trigramProb: DoubleColumn,
) {
    val size get() = words.size

    /** log10 P([w] | [v]), backing off as ARPA does. */
    fun log10(v: Int, w: Int): Double {
        val bigram = bigrams.indexOf(NgramKey.of(v, w))
        return if (bigram >= 0) bigramProb[bigram] else unigramBackoff[v] + unigramProb[w]
    }

    /** log10 P([w] | [u] [v]). */
    fun log10(u: Int, v: Int, w: Int): Double {
        val trigram = trigrams.indexOf(NgramKey.of(u, v, w))
        if (trigram >= 0) return trigramProb[trigram]
        val context = bigrams.indexOf(NgramKey.of(u, v))
        return (if (context >= 0) bigramBackoff[context] else 0.0) + log10(v, w)
    }

    /**
     * This model with those of [added]'s words it lacks as unigrams, at the log10 probabilities
     * given, the old unigrams scaled to leave them room; the first of a word given holds. They have
     * no n-grams: a mix splits text into them too and counts theirs, so a word the model never had
     * gets a context.
     */
    fun withUnigrams(added: List<Pair<String, Double>>): ArpaModel {
        val known = HashSet(words)
        val more = added.filter { (word, _) -> known.add(word) }
        // one id more for ChatCounts' start
        require(size + more.size < NgramKey.MAX_WORDS) { "${size + more.size} words leave no id for the start" }
        val mass = more.sumOf { (_, p) -> 10.0.pow(p) }
        require(mass < 1) { "the words added take ${"%.3f".format(Locale.ROOT, mass)} of the probability" }
        val scale = log10(1 - mass)
        return ArpaModel(
            words + more.map { it.first },
            DoubleArray(size + more.size) { if (it < size) unigramProb[it] + scale else more[it - size].second },
            unigramBackoff.copyOf(size + more.size),
            bigrams, bigramProb, bigramBackoff, trigrams, trigramProb,
        )
    }

    /** Writes the model as ARPA text: what [ArpaReader] and the `pinyin` command read. */
    fun write(out: Appendable) {
        out.appendLine("\\data\\")
        out.appendLine("ngram 1=$size")
        out.appendLine("ngram 2=${bigrams.size}")
        out.appendLine("ngram 3=${trigrams.size}")
        out.appendLine().appendLine("\\1-grams:")
        for (w in 0 until size) out.appendLine("${number(unigramProb[w])}\t${words[w]}\t${number(unigramBackoff[w])}")
        out.appendLine().appendLine("\\2-grams:")
        for (i in 0 until bigrams.size) {
            val key = bigrams.keyAt(i)
            out.append(number(bigramProb[i])).append('\t')
                .append(words[NgramKey.word(key, 2, 0)]).append(' ').append(words[NgramKey.word(key, 2, 1)])
                .append('\t').appendLine(number(bigramBackoff[i]))
        }
        out.appendLine().appendLine("\\3-grams:")
        for (i in 0 until trigrams.size) {
            val key = trigrams.keyAt(i)
            out.append(number(trigramProb[i])).append('\t')
                .append(words[NgramKey.word(key, 3, 0)]).append(' ')
                .append(words[NgramKey.word(key, 3, 1)]).append(' ')
                .appendLine(words[NgramKey.word(key, 3, 2)])
        }
        out.appendLine().appendLine("\\end\\")
    }

    companion object {
        // as many digits as the engine's quantisation could tell apart, and then some
        private fun number(x: Double) = String.format(Locale.ROOT, "%.6g", x)

        fun read(reader: BufferedReader, source: String): ArpaModel {
            val words = ArrayList<String>()
            val ids = HashMap<String, Int>()
            val uniProb = ArrayList<Double>()
            val uniBackoff = ArrayList<Double>()
            val bigrams = LongIndex()
            val biProb = DoubleColumn()
            val biBackoff = DoubleColumn()
            val trigrams = LongIndex()
            val triProb = DoubleColumn()
            fun id(word: String) = requireNotNull(ids[word]) { "\"$word\" is in an n-gram but no unigram" }
            ArpaReader.read(reader, source) { ngram, prob, backoff ->
                when (ngram.size) {
                    1 -> {
                        require(ids.put(ngram[0], words.size) == null) { "unigram \"${ngram[0]}\" twice" }
                        require(words.size < NgramKey.MAX_WORDS) { "more than ${NgramKey.MAX_WORDS} words" }
                        words += ngram[0]
                        uniProb += prob.toDouble()
                        uniBackoff += backoff.toDouble()
                    }
                    2 -> {
                        val before = bigrams.size
                        val i = bigrams.add(NgramKey.of(id(ngram[0]), id(ngram[1])))
                        require(bigrams.size > before) { "bigram \"${ngram.joinToString(" ")}\" twice" }
                        biProb[i] = prob.toDouble()
                        biBackoff[i] = backoff.toDouble()
                    }
                    else -> {
                        val before = trigrams.size
                        val i = trigrams.add(NgramKey.of(id(ngram[0]), id(ngram[1]), id(ngram[2])))
                        require(trigrams.size > before) { "trigram \"${ngram.joinToString(" ")}\" twice" }
                        triProb[i] = prob.toDouble()
                    }
                }
            }
            return ArpaModel(
                words, uniProb.toDoubleArray(), uniBackoff.toDoubleArray(),
                bigrams, biProb, biBackoff, trigrams, triProb,
            )
        }
    }
}
