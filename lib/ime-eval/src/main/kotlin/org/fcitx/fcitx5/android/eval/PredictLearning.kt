/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.PredictionOrder
import org.fcitx.fcitx5.android.engine.user.Continuations

/**
 * What learning gains 联想: the sentences of a set split into words, and after each word but the
 * last, whether what follows starts with what is offered, as [PredictRun] scores it, ranked by the
 * user's habits as a session ranks them ([PredictionOrder]). The [Halves.TUNE] half before anything
 * is learned, after it was typed once and after three times (a user's own phrases, typed again:
 * a pair counts from its third time, U-71), the [Halves.HELD_OUT] half after that (text not typed
 * before, which learning must not make worse), each with the user's pairs at [weight]; the whole
 * phrases typed three times going on after their start ([withPhrases], U-73) or not. After a word
 * the model does not know, the session offers the phrases going on after what was put in last; here
 * that is the context's last word, as a set typed a word at a time puts it in.
 */
class PredictLearning(private val data: PinyinData, private val weight: Float, private val withPhrases: Boolean = true) {
    private val textWords = TextWords(data.model, data.wordIndex)

    fun measure(samples: List<Sample>): List<Pair<String, PredictRun.Score>> {
        val tune = Halves.select(samples, Halves.TUNE)
        val heldOut = Halves.select(samples, Halves.HELD_OUT)
        val user = EvalUser(data)
        // as the app's session has it
        val predictor = Predictor(data.model, data.vocabulary, data.dictionary, user.model, weight, coarse = CoarseWords.of({ data.wordIndex.find(it) }))
        val order = PredictionOrder(predictor) { user.model.idsOfText(it).firstOrNull() ?: data.wordIndex.find(it) }
        val score = { set: List<Sample> -> score(set, order, predictor, user) }
        val rows = mutableListOf("tune, nothing learned" to score(tune), "held-out, nothing learned" to score(heldOut))
        val typist = KeystrokeRun(PinyinSession(data, PinyinSegmenter(), user = user.model, prediction = false, learnTo = user.learnTo()))
        tune.forEach { typist.type(it) }
        rows += "tune, typed once" to score(tune)
        repeat(2) { tune.forEach { typist.type(it) } }
        rows += "tune, typed three times" to score(tune)
        rows += "held-out, tune learned" to score(heldOut)
        return rows
    }

    private fun score(samples: List<Sample>, order: PredictionOrder, predictor: Predictor, user: EvalUser): PredictRun.Score {
        val continuations = continuations(samples)
        val offers = continuations.map { c ->
            val words = textWords.lastTwo(c.context)
            val (prev2, prev) = TextWords.previous(words)
            // the word before as Predictions.wordBefore has it (the user's too), else what was put in last
            val last = if (prev == NO_WORD) lastWord(c.context) else predictor.textOf(prev)
            order.offer(user.habits, prev2, prev, continued = phrases(c.context, last, user), last = last).candidates.map { it.text }.distinct()
        }
        return PredictRun.score(continuations, offers)
    }

    // the whole phrases [context] ends a start of, after its last word [last], as Predictions.continued finds them
    private fun phrases(context: String, last: String, user: EvalUser): List<Continuations.Found> {
        if (!withPhrases || last.isEmpty()) return emptyList()
        val tail = Continuations.tail(context)
        return user.model.continuationsAfter(tail, textWords.boundaries(tail), last)
    }

    // the last of [context]'s words, as typed a word at a time
    private fun lastWord(context: String): String = textWords.boundaries(context).let { b -> if (b.size < 2) "" else context.substring(b[b.size - 2]) }

    /** After each word of each sample's text but the last, the text to it and what follows. */
    fun continuations(samples: List<Sample>): List<Continuation> = samples.flatMap { sample ->
        val text = sample.expected
        textWords.boundaries(text).drop(1).dropLast(1).map { b -> Continuation(text.substring(0, b), text.substring(b)) }
    }
}
