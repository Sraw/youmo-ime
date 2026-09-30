/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import kotlin.math.log10
import kotlin.math.pow

/**
 * One backoff model of two: [weight] × [base] + (1 − [weight]) × [chat], as SRILM's static
 * mix does it. The n-grams are [base]'s and the chat's counted at least [minBigram] and
 * [minTrigram] times, each given the mixture's probability; the backoff weights are then
 * worked out again, so each context's probabilities sum to one.
 */
class Mixer(
    private val base: ArpaModel,
    private val counts: ChatCounts,
    private val chat: KneserNey,
    private val weight: Double,
    private val minBigram: Int,
    private val minTrigram: Int,
) {
    init {
        require(weight in 0.0..1.0) { "weight $weight is not in 0..1" }
        // a trigram's context must be a bigram, to keep its backoff weight
        require(minBigram in 1..minTrigram) { "bigram cutoff $minBigram, trigram cutoff $minTrigram" }
    }

    private val size = base.size

    private fun mix(baseLog10: Double, chatP: Double) = weight * 10.0.pow(baseLog10) + (1 - weight) * chatP

    fun mix(): ArpaModel {
        val p1 = DoubleArray(size) { w -> mix(base.unigramProb[w], chat.p1(w)) }

        val bigrams = LongIndex(base.bigrams.size * 2)
        for (i in 0 until base.bigrams.size) bigrams.add(base.bigrams.keyAt(i))
        for (i in 0 until counts.bigrams.size) {
            val key = counts.bigrams.keyAt(i)
            if (counts.bigramCount[i] >= minBigram && NgramKey.word(key, 2, 0) != counts.start) bigrams.add(key)
        }
        val p2 = DoubleColumn(bigrams.size)
        val sum2 = DoubleArray(size)
        val lower2 = DoubleArray(size)
        for (i in 0 until bigrams.size) {
            val key = bigrams.keyAt(i)
            val v = NgramKey.word(key, 2, 0)
            val w = NgramKey.word(key, 2, 1)
            p2[i] = mix(base.log10(v, w), chat.p2(v, w))
            sum2[v] += p2[i]
            lower2[v] += p1[w]
        }
        val backoff1 = DoubleArray(size) { v -> backoff(sum2[v], lower2[v]) { base.words[v] } }

        val trigrams = LongIndex(base.trigrams.size * 2)
        for (i in 0 until base.trigrams.size) trigrams.add(base.trigrams.keyAt(i))
        for (i in 0 until counts.trigrams.size) {
            val key = counts.trigrams.keyAt(i)
            if (counts.trigramCount[i] >= minTrigram && NgramKey.word(key, 3, 0) != counts.start) trigrams.add(key)
        }
        val p3 = DoubleColumn(trigrams.size)
        val sum3 = DoubleColumn(bigrams.size)
        val lower3 = DoubleColumn(bigrams.size)
        for (i in 0 until trigrams.size) {
            val key = trigrams.keyAt(i)
            val u = NgramKey.word(key, 3, 0)
            val v = NgramKey.word(key, 3, 1)
            val w = NgramKey.word(key, 3, 2)
            p3[i] = mix(base.log10(u, v, w), chat.p3(u, v, w))
            val context = bigrams.indexOf(NgramKey.of(u, v))
            check(context >= 0) { "trigram with no bigram for its context" }
            sum3.add(context, p3[i])
            // what the mixture gives w after v alone
            val lower = bigrams.indexOf(NgramKey.of(v, w))
            lower3.add(context, if (lower >= 0) p2[lower] else backoff1[v] * p1[w])
        }

        val bigramProb = DoubleColumn(bigrams.size)
        val bigramBackoff = DoubleColumn(bigrams.size)
        for (i in 0 until bigrams.size) {
            bigramProb[i] = log10(p2[i])
            bigramBackoff[i] = log10(
                backoff(sum3[i], lower3[i]) { (0..1).joinToString(" ") { base.words[NgramKey.word(bigrams.keyAt(i), 2, it)] } },
            )
        }
        val trigramProb = DoubleColumn(trigrams.size)
        for (i in 0 until trigrams.size) trigramProb[i] = log10(p3[i])
        return ArpaModel(
            base.words,
            DoubleArray(size) { log10(p1[it]) },
            DoubleArray(size) { log10(backoff1[it]) },
            bigrams, bigramProb, bigramBackoff, trigrams, trigramProb,
        )
    }

    private companion object {
        // what is left to share, never quite nothing: rounding can take a full context past one
        const val LEAST = 1e-9

        // what reading the probabilities as floats can leave of a context with every word
        const val FULL = 1e-6

        // what rounding the base's six digits can take a full context's sum from one
        const val SLACK = 1e-4

        /**
         * The weight that makes the words a context has no n-gram for share what is left of it.
         * Where it has one for every word, nothing is left for them to share: its own must then
         * take it all, or the weight would blow up what little the others have.
         */
        inline fun backoff(explicit: Double, lower: Double, context: () -> String): Double {
            if (explicit == 0.0) return 1.0
            if (1 - lower <= FULL) {
                require(1 - explicit <= SLACK) { "\"${context()}\" has an n-gram for every word, together only $explicit" }
                return 1.0
            }
            return maxOf(1 - explicit, LEAST) / (1 - lower)
        }
    }
}
