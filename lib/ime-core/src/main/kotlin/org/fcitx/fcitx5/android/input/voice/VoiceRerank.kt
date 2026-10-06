/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * The recognizer's hypotheses (its n-best) ranked again with what the pinyin engine knows of
 * Chinese: the recognizer hears sounds, and of 恨佛系 and 很佛系, heard alike, it may prefer the
 * one nobody writes. Each is scored by the recognizer's own score (a mean log-probability per
 * token) plus [WEIGHT] times the pinyin model's log10 probability of its text per character.
 *
 * Only Chinese heard otherwise: a hypothesis is a candidate if, but for its Chinese characters,
 * it reads as the recognizer's best. A text model would rather drop an English word it lacks
 * than keep it (accident happen → 时间); what was said in English is the recognizer's.
 *
 * Measured on ~/voice-eval (rerank.py, rr_ng.py: 1155 utterances, our sherpa-onnx's n-best with
 * the app's hotwords, the weight by two-fold cross-validation), mixed error rate with a beam of
 * 16 (VoiceEngine's), as heard → ranked: new words 10.94% → 9.04% (328 of 405 written, from
 * 308), aishell 3.99 → 3.74, fleurs 4.81 → 4.55, ascend (Chinese and English mixed) 9.29 → 9.29;
 * with the beam of 4 before, as heard: 13.12% (293), 3.96, 4.85, 9.48. Any hypothesis a
 * candidate, ascend went to 10.09. The large sentence model on top won no more, for six times
 * the work.
 */
object VoiceRerank {

    /** A hypothesis as the recognizer has it: its text and the score it was ranked by. */
    data class Hypothesis(val text: String, val score: Float)

    /** What is read of [text]: no punctuation, no spaces. Hypotheses alike in it are one. */
    fun bare(text: String): String = text.filterNot { it.isWhitespace() || Character.getType(it) in PUNCTUATION }

    /**
     * [nbest] (best first), one of each [bare] text, the best scored of each: the recognizer's
     * beam spends half its room on the same words punctuated otherwise.
     */
    fun distinct(nbest: List<Hypothesis>): List<Hypothesis> = nbest.distinctBy { bare(it.text) }

    /**
     * Of [hypotheses] (as [distinct] gives them, the first the recognizer's best), the candidates
     * to rank: those only Chinese characters set apart from the first.
     */
    fun candidates(hypotheses: List<Hypothesis>): List<Hypothesis> {
        val first = hypotheses.firstOrNull() ?: return hypotheses
        val rest = notHan(bare(first.text))
        return hypotheses.filter { notHan(bare(it.text)) == rest }
    }

    /**
     * The best of [candidates] (as [candidates] gives them), given the log10 probability of each
     * [bare] text under the pinyin model ([logProbs]); the first if there are none.
     */
    fun best(candidates: List<Hypothesis>, logProbs: FloatArray?): Hypothesis {
        if (candidates.size < 2 || logProbs == null || logProbs.size != candidates.size) return candidates.first()
        return candidates.indices.maxBy { candidates[it].score + WEIGHT * logProbs[it] / maxOf(1, bare(candidates[it].text).length) }
            .let { candidates[it] }
    }

    private fun notHan(text: String) = text.filterNot { it in '一'..'鿿' }

    private val PUNCTUATION = setOf(
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
    ).map { it.toInt() }.toSet()

    private const val WEIGHT = 0.7f
}
