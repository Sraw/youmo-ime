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
import org.fcitx.fcitx5.android.engine.user.Continuations

/**
 * 联想 in the user's order, for every input method: the [predictor]'s best [RANKED] after the word before, ranked
 * by [HabitRanker] under that word's code; a word picked there that dropped out is put back in. Filed by text
 * alone, so one list is learned for all input methods, and a pick moves neither pinyin's order nor the decoder's counts.
 */
class PredictionOrder(
    private val predictor: Predictor,
    private val idOf: (String) -> Int,
) {
    /** What is offered [after] a word: [candidates] in order with their [ranks]; [phrases] those that are a whole phrase's rest. */
    class Offer(
        val after: String,
        val candidates: List<Candidate>,
        val ranks: Map<Candidate, HabitRanker.Ranked<Candidate>>,
        val phrases: Map<Candidate, Continuations.Found> = emptyMap(),
    ) {
        companion object {
            val NONE = Offer("", emptyList(), emptyMap())
        }
    }

    /** What may follow [prev], as [habits] rank it: whole phrases' rests first, then the words, unless one is pinned or put first; after no model word, the phrases alone after [last]. */
    fun offer(
        habits: Habits,
        prev2: Int,
        prev: Int,
        learnedAfter: Int = prev,
        tail: () -> Int = { NO_WORD },
        continued: List<Continuations.Found> = emptyList(),
        last: String = "",
    ): Offer {
        val phrases = continued.associateBy { Candidate(it.rest, 0, 0f, IntArray(0), IntArray(0)) }
        if (prev == NO_WORD) return if (phrases.isEmpty() || last.isEmpty()) Offer.NONE else phrasesAlone(habits, last, phrases)
        val rests = continued.mapTo(HashSet()) { it.rest }
        val offered = predictor.predict(prev2, prev, RANKED, learnedAfter, tail).filter { it.text !in rests }
        // the word as put in (你好, not its 好): the habits and turn-offs are filed under it
        val after = predictor.after
        val code = code(after)
        val present = offered.mapTo(HashSet()) { it.text }.apply { addAll(rests) }
        // picked here once and gone from the best since: back among them
        val learned = if (learnedAfter == NO_WORD) prev else learnedAfter
        val back = habits.used(code).mapNotNull { w ->
            if (!present.add(w.text)) null else idOf(w.text).takeIf { it != NO_WORD }?.let { predictor.candidate(learned, it) }
        }
        if (offered.isEmpty() && back.isEmpty() && phrases.isEmpty()) return Offer(after, emptyList(), emptyMap())
        val items = offered.map { item(it, injected = false) } + back.map { item(it, injected = true) }
        val view = view(habits)
        val byHabit = HabitRanker.rank(items, code, after, view)
        val whole = ranked(phrases.keys, code, view)
        val ahead = byHabit.firstOrNull()?.takeIf { it.tier == HabitRanker.Tier.PINNED || it.tier == HabitRanker.Tier.FIRST_HERE }
        val ranked = listOfNotNull(ahead) + whole + byHabit.drop(if (ahead == null) 0 else 1)
        return Offer(after, ranked.map { it.value }, ranked.associateBy { it.value }, phrases)
    }

    private fun phrasesAlone(habits: Habits, after: String, phrases: Map<Candidate, Continuations.Found>): Offer {
        val ranked = ranked(phrases.keys, code(after), view(habits))
        return Offer(after, ranked.map { it.value }, ranked.associateBy { it.value }, phrases)
    }

    private fun ranked(rests: Collection<Candidate>, code: Code, view: HabitRanker.View) =
        rests.map { c -> HabitRanker.Ranked(item(c, injected = false), HabitRanker.Tier.DICTIONARY, view.uses(code, words(c.text))) }

    private fun item(c: Candidate, injected: Boolean) =
        HabitRanker.Item(c, words(c.text), single = c.text.codePointCount(0, c.text.length) == 1 && Han.all(c.text), injected = injected)

    private fun view(habits: Habits) = habits.rankView(imports = false)

    companion object {
        /** Two pages and more; one never in them is seldom picked. */
        const val RANKED = 30

        fun code(after: String) = Code(Kinds.PREDICTION, after)

        fun words(text: String) = Words.of(text)

        /** The record of [text] picked [after] the word it follows, [first] shown first. */
        fun row(text: String, after: String, first: String): Row {
            val corrected = if (first.isNotEmpty() && first != text) Row.CORRECTED else 0
            // the model's word or the user's: never one to form
            return Row(Row.PICK or Row.WHOLE or corrected, code(after), after, words(text), 'D', Row.DICTIONARY, first)
        }
    }
}
