/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.user.UserModel

/**
 * What learning gains 联想: the sentences of a set split into words, and after each word but the
 * last, whether what follows starts with what is offered, as [PredictRun] scores it. The
 * [Halves.TUNE] half before anything is learned and after it was typed once (a user's own
 * phrases, typed again), the [Halves.HELD_OUT] half after it (text not typed before, which
 * learning must not make worse), each with the user's pairs at [weight].
 */
class PredictLearning(private val data: PinyinData, private val weight: Float) {
    private val textWords = TextWords(data.model, data.wordIndex)

    fun measure(samples: List<Sample>): List<Pair<String, PredictRun.Score>> {
        val tune = Halves.select(samples, Halves.TUNE)
        val heldOut = Halves.select(samples, Halves.HELD_OUT)
        val user = UserModel(data.dictionary, data.vocabulary)
        val predictor = Predictor(data.model, data.vocabulary, data.dictionary, user, weight)
        val rows = mutableListOf("tune, nothing learned" to score(tune, predictor), "held-out, nothing learned" to score(heldOut, predictor))
        val typist = KeystrokeRun(PinyinSession(data, PinyinSegmenter(), user = user, prediction = false))
        tune.forEach { typist.type(it) }
        rows += "tune, typed once" to score(tune, predictor)
        rows += "held-out, tune learned" to score(heldOut, predictor)
        return rows
    }

    private fun score(samples: List<Sample>, predictor: Predictor): PredictRun.Score {
        val continuations = continuations(samples)
        val offers = continuations.map { c ->
            val words = textWords.lastTwo(c.context)
            val prev = words.lastOrNull() ?: NO_WORD
            val prev2 = if (words.size == 2) words[0] else NO_WORD
            predictor.predict(prev2, prev).map { it.text }.distinct()
        }
        return PredictRun.score(continuations, offers)
    }

    /** After each word of each sample's text but the last, the text to it and what follows. */
    fun continuations(samples: List<Sample>): List<Continuation> = samples.flatMap { sample ->
        val text = sample.expected
        textWords.boundaries(text).drop(1).dropLast(1).map { b -> Continuation(text.substring(0, b), text.substring(b)) }
    }
}
