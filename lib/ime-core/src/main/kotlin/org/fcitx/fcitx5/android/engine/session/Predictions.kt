/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

/**
 * What may follow the text put in (联想), for the input methods other than pinyin (handwriting, the
 * tables), and the text before the cursor it goes on from: one copy, as each kept its own and they
 * drifted apart. [offered] after [predict], from [before] as far as it is words; none without a
 * [predictor] or [words], nor while nothing is [learning]. A prediction picked is learned by [user]
 * after [lastEntry], as pinyin learns one: it comes up after that word again. A long press on one
 * forgets what was learned of it at the reading it was put in at, and the tables' copy ([forgotten]),
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
    private val learning: () -> Boolean,
) {
    var offered: List<Candidate> = emptyList()
        private set

    /** The text before the cursor, as far as it is known: the field's, and what was put in since. */
    var before = ""
        private set

    /** The last word put in, as the user model has it: what is picked next is learned after it. */
    var lastEntry: Entry? = null

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
    }

    /** Nothing known before the cursor: another field, or Enter between. */
    fun forgetBefore() {
        before = ""
        lastEntry = null
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

    fun predict() {
        val p = predictor
        val w = words
        offered = if (p == null || w == null || !learning()) emptyList() else TextWords.previous(w.lastTwo(before)).let { (prev2, prev) -> p.predict(prev2, prev) }
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

    /** Puts in the prediction at [index], learned, and offers what may follow it: its text, null for none there. */
    fun pick(index: Int): String? {
        val c = offered.getOrNull(index) ?: return null
        val entry = entry(index)
        if (learning() && entry != null) user?.learn(lastEntry, listOf(entry))
        wrote(c.text, entry)
        predict()
        return c.text
    }

    // what was learned of a prediction may be forgotten, any blocked; not where nothing is to be kept
    // (a password), as forgetting and blocking record the word
    fun offers(index: Int): Set<Offer> {
        val u = user
        if (!learning() || u == null) return emptySet()
        val word = offered.getOrNull(index)?.words?.singleOrNull() ?: return emptySet()
        val entry = entry(index) ?: return emptySet()
        return buildSet {
            if (u.probability(NO_WORD, word) > 0f) add(Offer.FORGET)
            if (block != null && blockable(entry.text)) add(Offer.BLOCK)
        }
    }

    /** The prediction at [index] forgotten or blocked, as [offers] has it, and what may follow offered again: whether it was. */
    fun press(index: Int, offer: Offer): Boolean {
        val entry = entry(index)
        if (entry == null || offer !in offers(index)) return false
        if (offer == Offer.FORGET) {
            user?.forget(listOf(entry))
            forgotten(entry.text)
        } else {
            block?.invoke(entry)
        }
        // or the next pick would learn it again, as the word before it
        if (lastEntry == entry) lastEntry = null
        predict()
        return true
    }

    /** Whether page [to] of [pageSize] has predictions on it. */
    fun hasPage(to: Int, pageSize: Int) = to >= 0 && to * pageSize < offered.size

    // as much as a sentence model reads (not from the middle of a pair), only the last words where nothing is learned
    private fun kept(text: String) = if (learning()) PinyinSession.tail(text) else text.takeLast(WORDS_KEPT).let {
        if (it.isNotEmpty() && Character.isLowSurrogate(it[0])) it.substring(1) else it
    }

    companion object {
        // the text before the cursor kept where nothing is learned: the last two words, as TextWords reads them
        const val WORDS_KEPT = 3 * TextWords.MAX_WORD
    }
}
