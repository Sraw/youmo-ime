/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.lattice.WordScorer

/** How learning reaches the pinyin decoder's scores: for measuring (`ime-eval learn`), not a setting. The app runs [DEFAULT]; the others drop or add a channel. */
data class LearningUse(
    val counts: Counts = Counts.MULTI,
    /** No counts over a syllable typed short (简拼): what is typed in full never moves it. */
    val shortGuard: Boolean = true,
) {
    enum class Counts {
        FULL,

        /** None: the model alone, user words scored as the model's words they are ([UserModel.modelWord]). */
        NONE,

        /** Only words not reading the whole input alone: a word typed by itself is ranked as the model ranks it. */
        MULTI,
    }

    fun scorer(model: NgramModel, user: UserModel?, prior: LayerPrior?): WordScorer =
        scorer(model, user, prior, if (counts == Counts.NONE) 0f else UserScorer.DEFAULT_WEIGHT)

    fun whole(model: NgramModel, user: UserModel?, prior: LayerPrior?): WordScorer? =
        if (counts == Counts.MULTI && user != null) scorer(model, user, prior, 0f) else null

    fun uncounted(model: NgramModel, user: UserModel?, prior: LayerPrior?): WordScorer? =
        if (shortGuard && counts != Counts.NONE && user != null) scorer(model, user, prior, 0f) else null

    private fun scorer(model: NgramModel, user: UserModel?, prior: LayerPrior?, weight: Float): WordScorer =
        (if (user == null) WordScorer.of(model) else UserScorer(model, user, weight)).let { if (prior == null) it else prior.scorer(it) }

    companion object {
        val DEFAULT = LearningUse()
    }
}
