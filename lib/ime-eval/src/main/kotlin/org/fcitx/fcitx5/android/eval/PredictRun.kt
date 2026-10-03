/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords

/** A prediction sample: what was written, and what came next. */
data class Continuation(val context: String, val next: String)

/**
 * What is offered after [Continuation.context] (联想), as a session offers it after a commit: the
 * model's words. An offer is a hit when the text that came next starts with it: picked, it saves
 * the keys of its characters. (A server's model was measured against them, on `--offers`'s output,
 * with dev/training/predict_exp.py: none was better.)
 */
class PredictRun(data: PinyinData) {
    private val predictor = Predictor(data.model, data.vocabulary, data.dictionary)
    private val textWords = TextWords(data.model, data.wordIndex)

    fun offers(context: String): List<String> {
        val words = textWords.lastTwo(context)
        val prev = words.lastOrNull() ?: NO_WORD
        val prev2 = if (words.size == 2) words[0] else NO_WORD
        return predictor.predict(prev2, prev).map { it.text }.distinct()
    }

    /** Hits among the first 1 and [SHOWN] offers, and the characters the first hit saves, summed. */
    data class Score(val samples: Int, val top1: Int, val shown: Int, val saved: Int) {
        override fun toString() =
            "samples $samples  top1 ${percent(top1)}  top$SHOWN ${percent(shown)}  chars saved per sample ${"%.2f".format(saved.toDouble() / samples)}"

        private fun percent(n: Int) = "%.1f%%".format(100.0 * n / samples)
    }

    companion object {
        const val SHOWN = 5

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
        fun parse(lines: Sequence<String>): List<Continuation> = lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapIndexed { i, line ->
            val f = line.split('\t')
            require(f.size == 2 && f[1].isNotEmpty()) { "line ${i + 1}: expected context<TAB>next, got \"$line\"" }
            Continuation(f[0], f[1])
        }.toList()
    }
}
