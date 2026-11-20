/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.UnicodePunctuation

/**
 * The recognizer's n-best reranked with the pinyin model: of 恨佛系 and 很佛系, heard alike, it may prefer the one
 * nobody writes. Score: the recognizer's mean log-probability per token plus [WEIGHT] times the model's log10
 * probability per character. Only hypotheses differing in Chinese characters compete: a text model would rather
 * drop an English word it lacks.
 */
object VoiceRerank {

    data class Hypothesis(val text: String, val score: Float)

    /** No punctuation, no spaces; hypotheses alike in it are one. */
    fun bare(text: String): String = text.filterNot { it.isWhitespace() || it in UnicodePunctuation }

    /** One per [bare] text, the best scored: the beam spends half its room on the same words punctuated otherwise. */
    fun distinct(nbest: List<Hypothesis>): List<Hypothesis> = nbest.distinctBy { bare(it.text) }

    /** Those only Chinese characters set apart from the first (the recognizer's best). */
    fun candidates(hypotheses: List<Hypothesis>): List<Hypothesis> {
        val first = hypotheses.firstOrNull() ?: return hypotheses
        val rest = notHan(bare(first.text))
        return hypotheses.filter { notHan(bare(it.text)) == rest }
    }

    /** [logProbs]: each [bare] text's log10 probability under the pinyin model; the first if there are none. */
    fun best(candidates: List<Hypothesis>, logProbs: FloatArray?): Hypothesis {
        if (candidates.size < 2 || logProbs == null || logProbs.size != candidates.size) return candidates.first()
        return candidates.indices.maxBy { candidates[it].score + WEIGHT * logProbs[it] / maxOf(1, bare(candidates[it].text).length) }
            .let { candidates[it] }
    }

    private fun notHan(text: String) = text.replace(HAN, "")

    // a Chinese character as VoiceText and VoiceHotwords have it, 〇 and Extension A too
    private val HAN = Regex("\\p{IsHan}")

    private const val WEIGHT = 0.7f
}
