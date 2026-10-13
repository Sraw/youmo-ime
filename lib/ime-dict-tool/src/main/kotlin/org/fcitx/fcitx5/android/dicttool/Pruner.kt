/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * [model] less the n-grams that tell it little, by relative entropy (Stolcke, "Entropy-based
 * pruning of backoff language models", 1998; SRILM's `-prune`): an n-gram goes when the model
 * without it, backing off instead, strays from the model with it by less than [threshold], the
 * difference weighted by how likely its context is. Each n-gram is judged against the whole model,
 * trigrams and then the bigrams no trigram keeps as its context; the backoff weights are then
 * worked out again, as [Mixer] does. Unigrams all stay.
 *
 * A context's likelihood is its words' chained through the model. The start of a sentence [start]
 * has no probability of its own in an ARPA model (it is never predicted), so it is given
 * [startProb], how often a word is the first of its sentence. SRILM gives it 1 instead, which weighs
 * the n-grams that start a sentence more; and it compares 10^ΔH − 1, its change in perplexity, where
 * this takes ΔH in nats, the same to first order at the thresholds that are used.
 */
class Pruner(
    private val model: ArpaModel,
    private val threshold: Double,
    private val start: Int,
    private val startProb: Double,
) {
    init {
        require(threshold >= 0) { "threshold $threshold" }
        require(startProb > 0 && startProb < 1) { "start probability $startProb" }
    }

    private val size = model.size
    private val p1 = DoubleArray(size) { 10.0.pow(model.unigramProb[it]) }

    /** How likely [v] is as the context of a bigram. */
    private fun context(v: Int) = if (v == start) startProb else p1[v]

    fun prune(): ArpaModel {
        val bigrams = model.bigrams
        val trigrams = model.trigrams

        // what each bigram context gives its trigrams, and what the bigrams alone would give them
        val sum3 = DoubleColumn(bigrams.size)
        val lower3 = DoubleColumn(bigrams.size)
        val context3 = IntArray(trigrams.size)
        for (i in 0 until trigrams.size) {
            val key = trigrams.keyAt(i)
            val u = NgramKey.word(key, 3, 0)
            val v = NgramKey.word(key, 3, 1)
            val w = NgramKey.word(key, 3, 2)
            val c = bigrams.indexOf(NgramKey.of(u, v))
            require(c >= 0) { "a trigram's context is no bigram" }
            context3[i] = c
            sum3.add(c, 10.0.pow(model.trigramProb[i]))
            lower3.add(c, 10.0.pow(model.log10(v, w)))
        }
        val keepTrigram = BooleanArray(trigrams.size)
        val isContext = BooleanArray(bigrams.size)
        for (i in 0 until trigrams.size) {
            val key = trigrams.keyAt(i)
            val u = NgramKey.word(key, 3, 0)
            val v = NgramKey.word(key, 3, 1)
            val w = NgramKey.word(key, 3, 2)
            val c = context3[i]
            val history = context(u) * 10.0.pow(model.bigramProb[c])
            if (history * loss(10.0.pow(model.trigramProb[i]), 10.0.pow(model.log10(v, w)), sum3[c], lower3[c]) >= threshold) {
                keepTrigram[i] = true
                isContext[c] = true
            }
        }

        val sum2 = DoubleArray(size)
        val lower2 = DoubleArray(size)
        for (i in 0 until bigrams.size) {
            val key = bigrams.keyAt(i)
            sum2[NgramKey.word(key, 2, 0)] += 10.0.pow(model.bigramProb[i])
            lower2[NgramKey.word(key, 2, 0)] += p1[NgramKey.word(key, 2, 1)]
        }
        val keepBigram = BooleanArray(bigrams.size) { i ->
            val key = bigrams.keyAt(i)
            val v = NgramKey.word(key, 2, 0)
            val w = NgramKey.word(key, 2, 1)
            isContext[i] || context(v) * loss(10.0.pow(model.bigramProb[i]), p1[w], sum2[v], lower2[v]) >= threshold
        }
        return rebuild(keepBigram, keepTrigram)
    }

    /**
     * What dropping one n-gram of probability [p] costs its context in relative entropy (nats): its
     * word backs off to [lower] times the context's new weight, and the words that backed off
     * already change by the new weight against the old. The context's n-grams sum to [explicit],
     * their lower order's probabilities to [lowerSum].
     */
    private fun loss(p: Double, lower: Double, explicit: Double, lowerSum: Double): Double {
        val left = maxOf(1 - explicit, LEAST)
        val lowerLeft = 1 - lowerSum
        // a context with every word: nothing to back off to, so nothing of it goes
        if (lowerLeft <= LEAST) return Double.POSITIVE_INFINITY
        val weight = left / lowerLeft
        val newWeight = (left + p) / (lowerLeft + lower)
        return p * (ln(p) - ln(lower) - ln(newWeight)) + left * (ln(weight) - ln(newWeight))
    }

    private fun rebuild(keepBigram: BooleanArray, keepTrigram: BooleanArray): ArpaModel {
        val bigramCount = keepBigram.count { it }
        val bigrams = LongIndex(bigramCount)
        val p2 = DoubleColumn(bigramCount)
        val sum2 = DoubleArray(size)
        val lower2 = DoubleArray(size)
        for (i in keepBigram.indices) {
            if (!keepBigram[i]) continue
            val key = model.bigrams.keyAt(i)
            val b = bigrams.add(key)
            p2[b] = model.bigramProb[i]
            sum2[NgramKey.word(key, 2, 0)] += 10.0.pow(p2[b])
            lower2[NgramKey.word(key, 2, 0)] += p1[NgramKey.word(key, 2, 1)]
        }
        val backoff1 = DoubleArray(size) { v -> Mixer.backoff(sum2[v], lower2[v]) { model.words[v] } }

        val trigramCount = keepTrigram.count { it }
        val trigrams = LongIndex(trigramCount)
        val p3 = DoubleColumn(trigramCount)
        val sum3 = DoubleColumn(bigrams.size)
        val lower3 = DoubleColumn(bigrams.size)
        for (i in keepTrigram.indices) {
            if (!keepTrigram[i]) continue
            val key = model.trigrams.keyAt(i)
            val t = trigrams.add(key)
            p3[t] = model.trigramProb[i]
            val v = NgramKey.word(key, 3, 1)
            val w = NgramKey.word(key, 3, 2)
            val c = bigrams.indexOf(NgramKey.of(NgramKey.word(key, 3, 0), v))
            sum3.add(c, 10.0.pow(p3[t]))
            val lower = bigrams.indexOf(NgramKey.of(v, w))
            lower3.add(c, if (lower >= 0) 10.0.pow(p2[lower]) else backoff1[v] * p1[w])
        }
        val bigramBackoff = DoubleColumn(bigrams.size)
        for (b in 0 until bigrams.size) {
            bigramBackoff[b] = log10(Mixer.backoff(sum3[b], lower3[b]) { (0..1).joinToString(" ") { model.words[NgramKey.word(bigrams.keyAt(b), 2, it)] } })
        }
        return ArpaModel(
            model.words, model.unigramProb, DoubleArray(size) { log10(backoff1[it]) },
            bigrams, p2, bigramBackoff, trigrams, p3,
        )
    }

    private companion object {
        // as Mixer's: what is left to share, never quite nothing
        const val LEAST = 1e-9
    }
}
