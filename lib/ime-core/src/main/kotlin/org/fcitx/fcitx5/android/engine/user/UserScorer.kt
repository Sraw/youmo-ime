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
 */
class UserScorer(
    private val model: NgramModel,
    private val user: UserModel,
    private val weight: Float = DEFAULT_WEIGHT,
) : WordScorer {
    override fun score(prev2: Int, prev: Int, word: Int) = scoreAfter(context(prev2, prev), word)

    override fun context(prev2: Int, prev: Int) = model.context(prev2, prev)

    override fun scoreAfter(context: Long, word: Int): Float {
        val base = minOf(0f, model.scoreAfter(context, word))
        val p = user.probability(model.prevOf(context), word)
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
