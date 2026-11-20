/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import kotlin.math.log10
import kotlin.math.pow

/**
 * [model]'s scores with [user]'s counts added: log10(P_model + [weight] · P_user), capped at 0. Adding rather than
 * interpolating leaves every word the user never typed as the model scores it. A pack-scored unknown word takes
 * the pack's score (ime-eval only); a user word whose text the model has is that model word ([UserModel.modelWord]).
 */
class UserScorer(
    private val model: NgramModel,
    private val user: UserModel,
    private val weight: Float = DEFAULT_WEIGHT,
) : WordScorer {
    override fun score(prev2: Int, prev: Int, word: Int) = scoreAfter(context(prev2, prev), word)

    override fun context(prev2: Int, prev: Int): Long {
        val bigram = model.context(user.modelWord(prev2), user.modelWord(prev))
        return (prev.toLong() shl Int.SIZE_BITS) or (bigram and 0xffffffffL)
    }

    override fun trigrams(context: Long) = model.trigrams(context)

    override fun scoreAfter(context: Long, word: Int) = scoreAfter(context, model.trigrams(context), word)

    override fun scoreAfter(context: Long, trigrams: NgramModel.Trigrams, word: Int): Float {
        val prev = model.prevOf(context)
        val modelContext = (user.modelWord(prev).toLong() shl Int.SIZE_BITS) or (context and 0xffffffffL)
        val same = user.modelWord(word)
        var base = model.scoreAfter(modelContext, trigrams, same)
        if (same >= model.vocabularySize) {
            val packed = user.score(word)
            if (!packed.isNaN()) base += packed - model.unknownScore
            // a merged word not yet the user's goes below the unknown word as its dictionary ranks it, commoner homophones first
            val merged = user.mergedScore(word)
            if (!merged.isNaN()) base += MERGED_SPAN * merged
        }
        base = minOf(0f, base)
        // weight 0: the model's score as it is, not through 10^x and back
        if (weight == 0f) return base
        val p = user.decoderProbability(prev, word)
        if (p == 0f) return base
        return minOf(0f, log10(10f.pow(base) + weight * p))
    }

    companion object {
        // the best of 0.03, 0.1 and 0.3 under `ime-eval learn` on held-out text, near the best on retyped text
        const val DEFAULT_WEIGHT = 0.1f

        // how much of a merged dictionary's log10 spread a score keeps: rime-frost's rarest go 1.5 below the unknown word
        const val MERGED_SPAN = 0.25f
    }
}
