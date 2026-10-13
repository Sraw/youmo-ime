/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.rerank.SentenceModel
import java.util.Locale
import kotlin.math.ln

/** A prediction sample: what was written, and what came next. */
data class Continuation(val context: String, val next: String)

/**
 * What is offered after [Continuation.context] (联想), as a session offers it after a commit: the
 * model's words. An offer is a hit when the text that came next starts with it: picked, it saves
 * the keys of its characters. (A server's model was measured against them, on `--offers`'s output,
 * with dev/training/predict_exp.py: none was better.) With `--rerank`, the offers reordered by a
 * sentence model as well, as the reranker weighs readings: on this set and on `predict-set`'s of
 * the chat set, top1 within ±0.5 point and top5 up 1 to 2, not worth a model run every commit.
 */
class PredictRun(data: PinyinData, model: SentenceModel? = null, private val weight: Float = 0f) {
    private val scorer = model?.Scorer()

    // as the app's session has it
    private val predictor = Predictor(data.model, data.vocabulary, data.dictionary, coarse = CoarseWords.of({ data.wordIndex.find(it) }))
    private val textWords = TextWords(data.model, data.wordIndex)

    fun offers(context: String): List<String> {
        val words = textWords.lastTwo(context)
        val (prev2, prev) = TextWords.previous(words)
        val offered = predictor.predict(prev2, prev)
        val scorer = scorer ?: return offered.map { it.text }.distinct()
        if (offered.isEmpty()) return emptyList()
        val lm = scorer.score(context, offered.map { it.text })
        return offered.indices.sortedByDescending { offered[it].score * LN_10 + weight * lm[it] }.map { offered[it].text }.distinct()
    }

    /** Hits among the first 1 and [SHOWN] offers, and the characters the first hit saves, summed. */
    data class Score(val samples: Int, val top1: Int, val shown: Int, val saved: Int) {
        override fun toString() =
            "samples $samples  top1 ${percent(top1)}  top$SHOWN ${percent(shown)}  chars saved per sample ${perSample("%.2f", saved.toDouble())}"

        private fun percent(n: Int) = perSample("%.1f%%", 100.0 * n)

        // `-` for no samples rather than NaN, as the other reports print it
        private fun perSample(format: String, total: Double) =
            if (samples == 0) "-" else String.format(Locale.ROOT, format, total / samples)
    }

    companion object {
        const val SHOWN = 5
        private val LN_10 = ln(10f)

        fun score(samples: List<Continuation>, offers: List<List<String>>): Score {
            var top1 = 0
            var shown = 0
            var saved = 0
            samples.zip(offers).forEach { (sample, offered) ->
                val hit = offered.take(SHOWN).firstOrNull { it.isNotEmpty() && sample.next.startsWith(it) }
                if (offered.firstOrNull()?.let { it.isNotEmpty() && sample.next.startsWith(it) } == true) top1++
                if (hit != null) {
                    shown++
                    saved += hit.length
                }
            }
            return Score(samples.size, top1, shown, saved)
        }

        /** `context<TAB>next` a line; `#` starts a comment. */
        fun parse(lines: Sequence<String>): List<Continuation> = lines.mapIndexedNotNull { i, line ->
            if (line.isBlank() || line.startsWith("#")) return@mapIndexedNotNull null
            val f = line.split('\t')
            require(f.size == 2 && f[1].isNotEmpty()) { "line ${i + 1}: expected context<TAB>next, got \"$line\"" }
            Continuation(f[0], f[1])
        }.toList()
    }
}
