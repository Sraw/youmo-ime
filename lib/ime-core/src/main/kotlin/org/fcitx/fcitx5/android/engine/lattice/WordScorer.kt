/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel

/**
 * log10 P(word | prev2 prev) over vocabulary ids. Either context may be [NgramModel.NO_WORD]:
 * no word there (the start of input, or text that is no word), which shortens the history.
 *
 * [NgramModel] is the scorer the engine ships; this is the seam for another (a model with user
 * words mixed in, a neural one). Scores must be log probabilities, so never above 0: the
 * decoder prunes on that.
 */
fun interface WordScorer {
    fun score(prev2: Int, prev: Int, word: Int): Float

    companion object {
        /** [model], held to 0: a backoff sum from quantised values could come out a hair above it. */
        fun of(model: NgramModel) = WordScorer { prev2, prev, word -> minOf(0f, model.score(prev2, prev, word)) }
    }
}
