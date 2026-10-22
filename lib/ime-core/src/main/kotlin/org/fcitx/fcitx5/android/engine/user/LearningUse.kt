/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.lattice.WordScorer

/**
 * How what is learned reaches the pinyin decoder's scores: for measuring (`ime-eval learn`,
 * `learn-ux`), not a setting. The app runs [DEFAULT]; the others take a channel away, or put one
 * back, to see what it is worth (dev/design/learning-ux/impl/PLAN.md §2.3).
 */
data class LearningUse(
    val counts: Counts = Counts.MULTI,
    /** No counts over a syllable typed short (简拼): what is typed in full never moves it (D32, impl/design-1b.md S4). */
    val shortGuard: Boolean = true,
) {
    /** Where the user's counts ([UserScorer]) enter the decoder's scores. */
    enum class Counts {
        /** Every word. */
        FULL,

        /** None: the model alone, the user's words scored as the model's words they are ([UserModel.modelWord]). */
        NONE,

        /** Only words that do not read the whole input alone: a word typed by itself is ranked as the model ranks it. */
        MULTI,
    }

    /** The pinyin decoder's scorer: [model], with [user]'s counts as [counts] has them, through [prior]. */
    fun scorer(model: NgramModel, user: UserModel?, prior: LayerPrior?): WordScorer =
        scorer(model, user, prior, if (counts == Counts.NONE) 0f else UserScorer.DEFAULT_WEIGHT)

    /** The scorer of a word reading all the input alone, where it is not [scorer]'s: [Counts.MULTI]'s, without the counts. */
    fun whole(model: NgramModel, user: UserModel?, prior: LayerPrior?): WordScorer? =
        if (counts == Counts.MULTI && user != null) scorer(model, user, prior, 0f) else null

    /** The scorer of a word over a syllable typed short, where it is not [scorer]'s: without the counts ([shortGuard]). */
    fun uncounted(model: NgramModel, user: UserModel?, prior: LayerPrior?): WordScorer? =
        if (shortGuard && counts != Counts.NONE && user != null) scorer(model, user, prior, 0f) else null

    // weight 0: no counts, the user's words still the model's words they are (UserScorer)
    private fun scorer(model: NgramModel, user: UserModel?, prior: LayerPrior?, weight: Float): WordScorer =
        (if (user == null) WordScorer.of(model) else UserScorer(model, user, weight)).let { if (prior == null) it else prior.scorer(it) }

    companion object {
        val DEFAULT = LearningUse()
    }
}
