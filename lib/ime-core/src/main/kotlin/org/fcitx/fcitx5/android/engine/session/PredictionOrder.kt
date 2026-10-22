/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.Han
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.learn.rankView

/**
 * 联想 in the user's order (U-70, PLAN §2.7), for every input method that offers it: the
 * [predictor]'s best [RANKED] after the word before are the original order, ranked by
 * [HabitRanker] under that word's code ([code]) and after it, nothing before it counting. A word
 * picked there that is no longer among them is put in, so one picked once stays ahead of those
 * never picked.
 *
 * 联想 has no keys: its words go by their text alone ([words]), and are filed so, whatever input
 * method offered them. So one list is learned for them all, and a pick moves neither pinyin's
 * order nor the decoder's counts, as a table's or handwriting's words do not (review-C S1).
 */
class PredictionOrder(
    private val predictor: Predictor,
    /** The id of a text, the model's word of it else the user's: a word picked here is filed by its text alone. */
    private val idOf: (String) -> Int,
) {
    /** What is offered [after] a word: [candidates] in order, each with its tier ([ranks]). */
    class Offer(val after: String, val candidates: List<Candidate>, val ranks: Map<Candidate, HabitRanker.Ranked<Candidate>>) {
        companion object {
            val NONE = Offer("", emptyList(), emptyMap())
        }
    }

    /** What may follow [prev] (after [prev2]; see [Predictor.predict] for [tail] and [learnedAfter]), as [habits] rank it. */
    fun offer(habits: Habits, prev2: Int, prev: Int, learnedAfter: Int = prev, tail: () -> Int = { NO_WORD }): Offer {
        if (prev == NO_WORD) return Offer.NONE
        val offered = predictor.predict(prev2, prev, RANKED, learnedAfter, tail)
        // the word as put in (你好, not its 好): the habits, 不再联想 and 为什么 filed under it (review-uat-fixes S11)
        val after = predictor.after
        val code = code(after)
        val present = offered.mapTo(HashSet()) { it.text }
        // picked here once and gone from the best since (they moved on): back among them
        // after the word as put in too: candidate() files its 不再联想 check under the word it is given (F2651)
        val learned = if (learnedAfter == NO_WORD) prev else learnedAfter
        val back = habits.used(code).mapNotNull { w ->
            if (!present.add(w.text)) null else idOf(w.text).takeIf { it != NO_WORD }?.let { predictor.candidate(learned, it) }
        }
        if (offered.isEmpty() && back.isEmpty()) return Offer(after, emptyList(), emptyMap())
        val items = offered.map { item(it, injected = false) } + back.map { item(it, injected = true) }
        val ranked = HabitRanker.rank(items, code, after, view(habits))
        return Offer(after, ranked.map { it.value }, ranked.associateBy { it.value })
    }

    private fun item(c: Candidate, injected: Boolean) =
        HabitRanker.Item(c, words(c.text), single = c.text.codePointCount(0, c.text.length) == 1 && Han.all(c.text), injected = injected)

    private fun view(habits: Habits) = habits.rankView(imports = false)

    companion object {
        /** The predictions ranked: two pages and more; one never in them is seldom picked (impl/streams/H-predict.md). */
        const val RANKED = 30

        /** The code of what is offered after the word [after]: the word itself, its text. */
        fun code(after: String) = Code(Kinds.PREDICTION, after)

        /** A prediction of [text] as learned: by its text, of no reading. */
        fun words(text: String) = Words.of(text)

        /** The record of [text] picked [after] the word it follows, [first] shown first (none: not known), as a session files it. */
        fun row(text: String, after: String, first: String): Row {
            val corrected = if (first.isNotEmpty() && first != text) Row.CORRECTED else 0
            // the model's word or the user's: never one to form
            return Row(Row.PICK or Row.WHOLE or corrected, code(after), after, words(text), 'D', Row.DICTIONARY, first)
        }
    }
}
