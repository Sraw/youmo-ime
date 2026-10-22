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
 * [model]'s scores with [user]'s counts added in: log10(P_model + [weight] · P_user). Adding
 * rather than interpolating leaves every word the user never typed as the model scores it, so
 * learning moves only what was learned; the sum is held to 0, as a score must be.
 *
 * A word the model lacks that a word pack scored ([UserModel.score]) takes that score in place
 * of the unknown word's, under the same backoff from the context: only under ime-eval, evaluating
 * a candidate pack, as the app lists no scored words; for it the look-up is one map miss for a
 * word the model lacks. One of the user's whose text the
 * model has (a word of the dictionary's at a reading of the user's) is that word to the model
 * ([UserModel.modelWord]), scored and as context: one rule for every input method.
 */
class UserScorer(
    private val model: NgramModel,
    private val user: UserModel,
    private val weight: Float = DEFAULT_WEIGHT,
) : WordScorer {
    override fun score(prev2: Int, prev: Int, word: Int) = scoreAfter(context(prev2, prev), word)

    // the model's context of the words as it has them, with prev as the user has it, for its counts
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
        }
        base = minOf(0f, base)
        // weight 0: the model's score as it is, not through 10^x and back
        if (weight == 0f) return base
        val p = user.decoderProbability(prev, word)
        if (p == 0f) return base
        return minOf(0f, log10(10f.pow(base) + weight * p))
    }

    companion object {
        // of 0.03, 0.1 and 0.3 under `ime-eval learn`, the best on text not typed before (held-out
        // top1 81.4% learning nothing, 85.9 / 87.7 / 85.1% learning), and near the best on text
        // typed again (97.1 / 98.2 / 98.6%)
        const val DEFAULT_WEIGHT = 0.1f
    }
}
