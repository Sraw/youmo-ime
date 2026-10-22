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
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

/**
 * What may follow the text put in (联想), for the input methods other than pinyin (handwriting, the
 * tables), and the text before the cursor it goes on from: one copy, as each kept its own and they
 * drifted apart. [offered] after [predict], from [before] as far as it is words, in the user's order
 * ([PredictionOrder]); none without a [predictor] or [words], nor while nothing is [learning]. A
 * prediction picked is learned into [habits] after the word it follows, as pinyin learns one, by its
 * text alone: it comes up after that word again, and moves nothing of pinyin's. So is what the session
 * commits ([record]), after [lastEntry]. A long press on one
 * forgets what was learned of it, and the tables' copy ([forgotten]),
 * or blocks it ([block]), as pinyin's ([offers], [press]): any word, as pinyin's predictions, where
 * the input method could block it as a candidate ([blockable]).
 *
 * The page shown stays the session's: it pages its own candidates the same way.
 */
class Predictions(
    private val predictor: Predictor?,
    private val words: TextWords?,
    private val user: UserModel?,
    /** Keeps a prediction the user blocked, blocked in [user] too; none offered without. */
    private val block: ((Entry) -> Unit)? = null,
    /** Told the text of a prediction forgotten, for the input methods that count their own (the tables) to forget it too. */
    private val forgotten: (String) -> Unit = {},
    /**
     * Whether the text of a prediction may be blocked: as the session's own candidates, or a text
     * blocked here could be one its candidates cannot (a table's single character: the lexicon blocks
     * a text in every reading, so it would go from pinyin and strokes too).
     */
    private val blockable: (String) -> Boolean = { true },
    /** The learning record what is put in is written to. */
    private val habits: Habits = Habits.OFF,
    /** Traditional text to simplified, as the [predictor] reads the text before (a traditional table's). */
    private val simplify: (String) -> String = { it },
    private val learning: () -> Boolean,
) {
    var offered: List<Candidate> = emptyList()
        private set

    private val order = predictor?.let { p -> PredictionOrder(p) { text -> user?.idsOfText(text)?.firstOrNull() ?: words?.id(text) ?: NO_WORD } }

    // the word [offered] follow: their code
    private var after = ""

    // how the habits ranked [offered]: what 为什么排在这 says (U-81)
    private var ranks: Map<Candidate, HabitRanker.Ranked<Candidate>> = emptyMap()

    // the field cleared itself (a chat's message sent, [Action.Context.cleared]): [before] still
    // scores, but no word of it is one the next pick learns a pair with (USER-DECISIONS 2026-10-20)
    var cleared = false
        private set

    /** The text before the cursor, as far as it is known: the field's, and what was put in since. */
    var before = ""
        private set

    /** The last word put in, as the user model has it: what is picked next is learned after it. */
    var lastEntry: Entry? = null

    /** What the field's commits are not learned for ([Commit.NO_PAIRS], [Commit.NO_PREDICT], U-91): as the session's last [Action.Context] said. */
    var learnFlags = 0

    /** [learnFlags] as [context] says them, kept where it says none; whether it [cleared][Action.Context.cleared] the field. */
    fun learnFrom(context: Action.Context) {
        context.learnFlags?.let { learnFlags = it }
        cleared = context.cleared
    }

    /** What a sentence model reads on from: nothing where the user learns nothing (a password). */
    val context: String get() = if (learning()) before else ""

    /** The text before the cursor is [text], as kept. */
    fun goOnFrom(text: String) {
        before = kept(text)
    }

    /** [text] went into the editor, [entry] its word as learned (or none). */
    fun wrote(text: String, entry: Entry?) {
        before = kept(before + text)
        lastEntry = entry
        cleared = false
    }

    /** Nothing known before the cursor: another field, or Enter between. */
    fun forgetBefore() {
        before = ""
        lastEntry = null
        cleared = false
    }

    /**
     * The app deleted what was before the cursor, nothing typed: its last character goes, and the
     * last word put in is no longer the one before (the next pick would learn a pair with it).
     */
    fun deleted() {
        val drop = if (before.length >= 2 && Character.isLowSurrogate(before.last())) 2 else 1
        before = before.dropLast(drop)
        lastEntry = null
    }

    /**
     * [words] put in under [code], after the last word put in, written to the learning record as one
     * whole commit (impl/design-1b.md §4): what the decoder's counts and 联想 go by. A table's and
     * handwriting's words have no reading, read by their text; nothing of them is made a word.
     */
    fun record(code: Code, words: List<Word>, how: Char = 'A', first: String = "", prev: String = wordBefore()): Commit? {
        if (!learning() || !habits.learns || words.isEmpty()) return null
        val w = Words(words)
        val corrected = if (first.isNotEmpty() && first != w.text) Row.CORRECTED else 0
        return habits.record(listOf(Row(Row.PICK or Row.WHOLE or corrected, code, prev, w, how, Row.DICTIONARY, first)), flags = learnFlags)
    }

    /**
     * The word what is put in now follows, as 联想 will look it up after it ([predict]): the
     * predictor's last word of [before], so a pair learned is one offered (H review S3); without a
     * predictor, [lastEntry]'s.
     */
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
        // nothing where nothing is learned (a password), nor in a search field (U-91)
        val shows = learning() && learnFlags and Commit.NO_PREDICT == 0
        val offer = if (o == null || w == null || !shows) PredictionOrder.Offer.NONE else TextWords.previous(w.lastTwo(simplify(before))).let { (prev2, prev) -> o.offer(habits, prev2, prev) }
        offered = offer.candidates
        ranks = offer.ranks
        after = offer.after
    }

    /**
     * The offer taken away. [lastEntry] stays, as pinyin keeps it: the text before still ends with
     * that word, and what the key that took the offer away does to it is the session's to say.
     */
    fun unoffer() {
        offered = emptyList()
    }

    /** The prediction at [index] as the user model has it, read as it likeliest is; null for none. */
    fun entry(index: Int): Entry? {
        val c = offered.getOrNull(index) ?: return null
        return c.words.singleOrNull()?.let { w -> predictor?.reading(w)?.let { Entry(c.text, it) } }
    }

    /** The record of the last prediction [pick]ed, null for none: what the session's journal takes back if it is deleted (U-03). */
    var picked: Commit? = null
        private set

    /** Puts in the prediction at [index], learned, and offers what may follow it: its text, null for none there. */
    fun pick(index: Int): String? {
        val c = offered.getOrNull(index) ?: return null
        val entry = entry(index)
        picked = if (after.isEmpty()) null else record(PredictionOrder.code(after), PredictionOrder.words(c.text).list, 'D', offered.firstOrNull()?.text.orEmpty(), after)
        wrote(c.text, entry)
        predict()
        return c.text
    }

    // what was learned of a prediction may be forgotten, any blocked; not where nothing is to be kept
    // (a password), as forgetting and blocking record the word
    fun offers(index: Int): Set<Offer> {
        val u = user
        if (!learning() || u == null) return emptySet()
        val c = offered.getOrNull(index)?.takeIf { it.words.size == 1 } ?: return emptySet()
        return buildSet {
            // picked here, it is counted by its text (see the class), in the record alone
            if (u.probability(NO_WORD, c.words[0]) > 0f || habits.knows(c.text)) add(Offer.FORGET)
            if (block != null && blockable(c.text) && entry(index) != null) add(Offer.BLOCK)
        }
    }

    /** The prediction at [index] forgotten or blocked, as [offers] has it, and what may follow offered again: whether it was. */
    fun press(index: Int, offer: Offer): Boolean {
        val c = offered.getOrNull(index)
        if (c == null || offer !in offers(index)) return false
        if (offer == Offer.FORGET) {
            forgotten(c.text)
        } else {
            entry(index)?.let { block?.invoke(it) }
        }
        // or the next pick would learn it again, as the word before it
        if (lastEntry?.text == c.text) lastEntry = null
        predict()
        return true
    }

    /**
     * The prediction at [index]'s code: [Kinds.PREDICTION] after the word the predictor went on
     * from, the one it filters what was turned off after (U-72, review-E-engine S10).
     */
    fun why(index: Int): Why? = predictor?.takeIf { index in offered.indices }?.let {
        val r = ranks[offered[index]]
        // the offer's own word, not the predictor's last (a later candidate() call moves that)
        Why(r?.tier, r?.uses ?: 0, Code(Kinds.PREDICTION, after))
    }

    /** What may follow offered anew (a prediction turned off, an undo): the page [shown] of [pageSize] if it still has one, else the first. */
    fun reread(shown: Int, pageSize: Int): Int {
        predict()
        return if (hasPage(shown, pageSize)) shown else 0
    }

    /** Whether page [to] of [pageSize] has predictions on it. */
    fun hasPage(to: Int, pageSize: Int) = to >= 0 && to * pageSize < offered.size

    // as much as a sentence model reads (not from the middle of a pair), only the last words where nothing is learned
    private fun kept(text: String) = if (learning()) recentTail(text) else text.takeLast(WORDS_KEPT).let {
        if (it.isNotEmpty() && Character.isLowSurrogate(it[0])) it.substring(1) else it
    }

    companion object {
        // the text before the cursor kept where nothing is learned: the last two words, as TextWords reads them
        const val WORDS_KEPT = 3 * TextWords.MAX_WORD
    }
}
