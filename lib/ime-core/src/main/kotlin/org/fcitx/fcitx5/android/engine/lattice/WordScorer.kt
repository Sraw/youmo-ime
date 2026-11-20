/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel

/**
 * log10 P(word | prev2 prev) over vocabulary ids; either context may be [NgramModel.NO_WORD] (start of
 * input, or non-word text), which shortens the history.
 *
 * The seam for scorers besides [NgramModel] (user words mixed in, a neural model). Scores must be log
 * probabilities, never above 0: the decoder prunes on that. A decoder caches scores across keys, so a
 * scorer whose scores change must be followed by [PinyinDecoder.reset].
 */
fun interface WordScorer {
    fun score(prev2: Int, prev: Int, word: Int): Float

    /** (prev2, prev) prepared for [scoreAfter]: a scorer that looks the context up (the n-gram's bigram) does it here once. */
    fun context(prev2: Int, prev: Int): Long = (prev2.toLong() shl Int.SIZE_BITS) or (prev.toLong() and 0xffffffffL)

    /** [score] after a [context] this scorer made. */
    fun scoreAfter(context: Long, word: Int): Float = score((context shr Int.SIZE_BITS).toInt(), context.toInt(), word)

    /** What [scoreAfter] looks up of [context] per word (the trigram run), once per context; [NgramModel.Trigrams.NONE] if nothing. */
    fun trigrams(context: Long): NgramModel.Trigrams = NgramModel.Trigrams.NONE

    /** [trigrams] must be this scorer's [trigrams] of [context]. */
    fun scoreAfter(context: Long, trigrams: NgramModel.Trigrams, word: Int): Float = scoreAfter(context, word)

    companion object {
        /** Held to 0: a backoff sum of quantised values can land a hair above it. */
        fun of(model: NgramModel): WordScorer = object : WordScorer {
            override fun score(prev2: Int, prev: Int, word: Int) = minOf(0f, model.score(prev2, prev, word))
            override fun context(prev2: Int, prev: Int) = model.context(prev2, prev)
            override fun scoreAfter(context: Long, word: Int) = minOf(0f, model.scoreAfter(context, word))
            override fun trigrams(context: Long) = model.trigrams(context)
            override fun scoreAfter(context: Long, trigrams: NgramModel.Trigrams, word: Int) = minOf(0f, model.scoreAfter(context, trigrams, word))
        }
    }
}
