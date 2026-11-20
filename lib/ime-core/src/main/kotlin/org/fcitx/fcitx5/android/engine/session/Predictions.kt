/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.user.Continuations
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

/**
 * 联想 and the text before the cursor it goes on from, shared by the input methods: [offered] after [predict] in
 * the user's order ([PredictionOrder]); none without a [predictor] or while not [learning]. Pinyin knows its words
 * by id and calls [offer] itself. A pick is learned into [habits] by its text alone; a long press forgets or blocks.
 */
class Predictions(
    private val predictor: Predictor?,
    private val words: TextWords?,
    private val user: UserModel?,
    private val block: ((Entry) -> Unit)? = null,
    /** Told a forgotten prediction's text, for the tables that count their own. */
    private val forgotten: (String) -> Unit = {},
    /** Whether a prediction's text may be blocked as the session's own candidates: a block is by text in every reading, so a table's character would go from pinyin and strokes too. */
    private val blockable: (String) -> Boolean = { true },
    private val habits: Habits = Habits.OFF,
    /** Traditional text to simplified, as the [predictor] reads it. */
    private val simplify: (String) -> String = { it },
    private val learning: () -> Boolean,
) {
    var offered: List<Candidate> = emptyList()
        private set

    private val order = predictor?.let { p -> PredictionOrder(p) { text -> user?.idsOfText(text)?.firstOrNull() ?: words?.id(text) ?: NO_WORD } }

    internal var after = ""
        private set

    private var ranks: Map<Candidate, HabitRanker.Ranked<Candidate>> = emptyMap()

    private var phrases: Map<Candidate, Continuations.Found> = emptyMap()

    // the field cleared itself: [before] still scores, but no pair is learned with it
    var cleared = false
        private set

    var before = ""
        private set

    var lastEntry: Entry? = null

    // the text put in last while [before] ends with it: the word a whole phrase goes on after where the predictor has none
    private var written = ""

    var learnFlags = 0

    fun learnFrom(context: Action.Context) {
        context.learnFlags?.let { learnFlags = it }
        cleared = context.cleared
    }

    /** What a sentence model reads on from: nothing without learning (a password). */
    val context: String get() = if (learning()) before else ""

    fun goOnFrom(text: String) {
        before = kept(text)
        written = ""
    }

    fun wrote(text: String, entry: Entry?) {
        before = kept(before + text)
        lastEntry = entry
        written = if (learning()) text else ""
        cleared = false
    }

    fun forgetBefore() {
        before = ""
        lastEntry = null
        written = ""
        cleared = false
    }

    /** The app deleted the last character before the cursor: the last word put in no longer precedes the next pick. */
    fun deleted() {
        val drop = if (before.length >= 2 && Character.isLowSurrogate(before.last())) 2 else 1
        before = before.dropLast(drop)
        lastEntry = null
        written = ""
    }

    /** [words] under [code], after the last word, written as one whole commit; a table's and handwriting's words are read by text, none made a word. */
    fun record(code: Code, words: List<Word>, how: Char = 'A', first: String = "", prev: String = wordBefore()): Commit? {
        if (!learning() || !habits.learns || words.isEmpty()) return null
        val w = Words(words)
        val corrected = if (first.isNotEmpty() && first != w.text) Row.CORRECTED else 0
        return habits.record(listOf(Row(Row.PICK or Row.WHOLE or corrected, code, prev, w, how, Row.DICTIONARY, first)), flags = learnFlags)
    }

    /** The word a commit now follows, as 联想 will look it up: the predictor's last word of [before], so a pair learned is one offered. */
    fun wordBefore(): String {
        if (cleared) return ""
        val p = predictor
        val w = words
        if (p == null || w == null) return lastEntry?.text.orEmpty()
        val prev = TextWords.previous(w.lastTwo(simplify(before))).second
        return if (prev == NO_WORD) "" else p.textOf(prev)
    }

    fun predict() {
        val o = order
        val w = words
        // nothing without learning, nor in a search field
        val shows = learning() && learnFlags and Commit.NO_PREDICT == 0
        show(
            if (o == null || w == null || !shows) {
                PredictionOrder.Offer.NONE
            } else {
                val text = simplify(before)
                val last = wordBefore().ifEmpty { simplify(written).takeIf { !cleared && text.endsWith(it) }.orEmpty() }
                TextWords.previous(w.lastTwo(text)).let { (prev2, prev) -> o.offer(habits, prev2, prev, continued = continued(text, last), last = last) }
            },
        )
    }

    /** The rests of whole phrases [text] ends a start of, after [last]; none without learning or after no word. */
    internal fun continued(text: String, last: String): List<Continuations.Found> {
        val u = user
        val w = words
        if (u == null || w == null || u.continuations.isEmpty()) return emptyList()
        if (last.isEmpty() || !learning() || learnFlags and Commit.NO_PREDICT != 0) return emptyList()
        val tail = Continuations.tail(text)
        return u.continuationsAfter(tail, w.boundaries(tail), last)
    }

    /** What may follow [prev] after [prev2] for a session that knows its words by id (pinyin), as [PredictionOrder.offer] has it. */
    internal fun offer(
        prev2: Int,
        prev: Int,
        learnedAfter: Int = prev,
        tail: () -> Int = { NO_WORD },
        continued: List<Continuations.Found> = emptyList(),
        last: String = "",
    ) = show(order?.offer(habits, prev2, prev, learnedAfter, tail, continued, last) ?: PredictionOrder.Offer.NONE)

    private fun show(offer: PredictionOrder.Offer) {
        offered = offer.candidates
        ranks = offer.ranks
        after = offer.after
        phrases = offer.phrases
    }

    internal fun phraseOf(c: Candidate): Continuations.Found? = phrases[c]

    /** The offer taken away; [lastEntry] stays, as the text before still ends with it. */
    fun unoffer() {
        offered = emptyList()
    }

    fun entry(index: Int): Entry? = offered.getOrNull(index)?.let(::entryOf)

    internal fun entryOf(c: Candidate): Entry? = c.words.singleOrNull()?.let { w -> predictor?.reading(w)?.let { Entry(c.text, it) } }

    /** The record of the last prediction picked: what the journal takes back if it is deleted. */
    var picked: Commit? = null
        private set

    fun pick(index: Int): String? {
        val c = offered.getOrNull(index) ?: return null
        val entry = entry(index)
        val first = offered.firstOrNull()?.text.orEmpty()
        val phrase = phrases[c]
        picked = when {
            after.isEmpty() -> null
            phrase == null -> record(PredictionOrder.code(after), PredictionOrder.words(c.text).list, 'D', first, after)
            !learning() || !habits.learns -> null
            else -> habits.recordContinued(listOf(PredictionOrder.row(c.text, after, first)), learnFlags, phrase.whole)
        }
        wrote(c.text, entry)
        predict()
        return c.text
    }

    // none without learning, as forgetting and blocking record the word
    fun offers(index: Int): Set<Offer> {
        val u = user
        if (!learning() || u == null) return emptySet()
        if (offered.getOrNull(index)?.let(phrases::get) != null) return setOf(Offer.FORGET)
        val c = offered.getOrNull(index)?.takeIf { it.words.size == 1 } ?: return emptySet()
        return OfferRules.offers(
            learning = true,
            forgets = { u.probability(NO_WORD, c.words[0]) > 0f || habits.knows(c.text) },
            blocks = { block != null && blockable(c.text) && entry(index) != null },
        )
    }

    fun press(index: Int, offer: Offer): Boolean {
        val c = offered.getOrNull(index)
        if (c == null || offer !in offers(index)) return false
        val phrase = phrases[c]
        if (phrase != null) {
            // a whole phrase: its count alone, here and in the tables
            habits.forgetPhrase(phrase.whole)
        } else if (offer == Offer.FORGET) {
            forgotten(c.text)
        } else {
            entry(index)?.let { block?.invoke(it) }
        }
        // or the next pick would learn it again, as the word before it
        if (lastEntry?.text == c.text) lastEntry = null
        predict()
        return true
    }

    /** The prediction's code: after the word the predictor went on from, the one its turn-offs are filed under. */
    fun why(index: Int): Why? = predictor?.takeIf { index in offered.indices }?.let {
        val r = ranks[offered[index]]
        val phrase = phrases[offered[index]]
        // the offer's own word, not the predictor's last (a later candidate() call moves that)
        if (phrase != null) Why(r?.tier, phrase.count, Code(Kinds.PREDICTION, after), phrase.whole) else Why(r?.tier, r?.uses ?: 0, Code(Kinds.PREDICTION, after))
    }

    internal fun reread(pager: Pager) {
        predict()
        pager.keep(pager.page, offered.size)
    }

    // as much as a sentence model reads; only the last words where nothing is learned
    private fun kept(text: String) = if (learning()) recentTail(text) else text.takeLast(WORDS_KEPT).let {
        if (it.isNotEmpty() && Character.isLowSurrogate(it[0])) it.substring(1) else it
    }

    companion object {
        const val WORDS_KEPT = 3 * TextWords.MAX_WORD
    }
}
