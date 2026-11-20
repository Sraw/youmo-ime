/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import kotlin.math.ln

fun interface SentencePicker {
    /** Index in [readings] to put first, 0 to keep the order; [scores] are the decoder's log10, [context] the text before. */
    fun pick(context: String, readings: List<String>, scores: List<Float>): Int
}

/** A [SentencePicker] whose work may be spread over calls while the user pauses. */
fun interface SentenceRefiner {
    /** As [SentencePicker.pick], or null if it needs more than [budget]; the work is kept and resumed on a call with the same arguments. [NONE]: no opinion. */
    fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int?

    /** Calls at most per input; past them the order shown stands. */
    val slices: Int get() = SLICES

    companion object {
        const val NONE = -1

        // a reorder much after the key would move what the user is already reaching for
        const val SLICES = 64
    }
}

/**
 * Reorders the decoder's best readings by adding a [SentenceModel]'s score (with the text before) to the
 * decoder's; added, not replacing, so the decoder's margin holds where the dictionary has evidence and the
 * model decides where it is thin (简拼, pieced-together sentences).
 */
class Reranker(
    model: SentenceModel,
    private val weight: Float = WEIGHT,
    private val limit: Int = LIMIT,
    /** Positions run and denominators taken at most per keystroke; past it, the decoder's order stands. */
    private val budget: Int = BUDGET,
) : SentencePicker, SentenceRefiner {

    private val scorer = model.Scorer()

    override fun pick(context: String, readings: List<String>, scores: List<Float>): Int = refine(context, readings, scores, budget) ?: 0

    override fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int? {
        if (readings.size < 2) return 0
        val compared = readings.take(limit)
        val model = scorer.within(context, compared, relative = true, budget = budget) ?: return null
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
        /** The model's log-probability weight against the decoder's, both in nats. */
        const val WEIGHT = 0.4f
        /** How many of the decoder's best readings are compared. */
        const val LIMIT = 2
        const val BUDGET = 4
        /** How many readings a refiner weighs, given time. */
        const val REFINE_LIMIT = 5
        private val LN_10 = ln(10f)
    }
}
