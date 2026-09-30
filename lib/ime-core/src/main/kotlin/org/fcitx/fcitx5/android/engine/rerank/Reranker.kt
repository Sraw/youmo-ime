/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import kotlin.math.ln

/** Which of a decoder's readings to put first. */
fun interface SentencePicker {
    /**
     * The index in [readings] to put first: 0 to keep the decoder's order. [scores] are the
     * decoder's, log10 as it keeps them; [context] is the text before.
     */
    fun pick(context: String, readings: List<String>, scores: List<Float>): Int
}

/**
 * Reorders the decoder's best whole-input readings by adding what a [SentenceModel] thinks of
 * each, after what the user wrote before, to the decoder's own score. The decoder's n-gram sees
 * two words at a time; the model sees the whole sentence.
 *
 * Added, not replacing: where the dictionary has the evidence (a word read whole, a frequent
 * phrase) the decoder's margin holds, and the model decides where it is thin (简拼, sentences
 * put together from pieces). Replacing the decoder's order with the model's lost 2.2 points of
 * top1 on the evaluation set; adding it gained 3 to 5 (see dev/ENGINE-DESIGN.md 2.6b).
 */
class Reranker(
    model: SentenceModel,
    private val weight: Float = WEIGHT,
    private val limit: Int = LIMIT,
    /** Positions run and denominators taken at most per keystroke; past it, the decoder's order stands. */
    private val budget: Int = BUDGET,
) : SentencePicker {

    private val scorer = model.Scorer()

    override fun pick(context: String, readings: List<String>, scores: List<Float>): Int {
        if (readings.size < 2) return 0
        val compared = readings.take(limit)
        val model = scorer.within(context, compared, relative = true, budget = budget) ?: return 0
        var best = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in compared.indices) {
            val combined = scores[i] * LN_10 + weight * model[i]
            if (combined > bestScore) {
                best = i
                bestScore = combined
            }
        }
        return best
    }

    companion object {
        /** The model's log-probability counted at this weight against the decoder's, both in nats. */
        const val WEIGHT = 0.4f
        /** How many of the decoder's best readings are compared: the first two hold most of the gain. */
        const val LIMIT = 2
        /**
         * A position or a denominator is 0.5 to 1 ms on a phone. With what is left over carried to
         * the next keystroke, 4 of them lose 1 sample of 548 against no bound, and take the
         * 95th percentile of a keystroke on an emulator from 8.1 to 5.7 ms.
         */
        const val BUDGET = 4
        private val LN_10 = ln(10f)
    }
}
