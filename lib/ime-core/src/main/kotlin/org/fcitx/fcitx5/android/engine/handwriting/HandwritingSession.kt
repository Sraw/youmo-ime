/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

/**
 * Handwriting as an input method, written a character at a time and read as a sentence, as pinyin
 * reads syllables: each character written joins the ones before it, not yet put in, and
 * [decoder] reads them together (a later character can put an earlier one right). The best
 * sentence shows as the preedit; the candidates are what the last character may be, each as the
 * best sentence ending with it has it, and picking one (a tap, space, a digit) puts that sentence
 * in. Enter or a key the app takes puts the best in; backspace takes the last character back. With
 * no [decoder] (no pinyin data), each character is its first reading.
 *
 * The pad recognizes off the fcitx thread and hands its reading over with [offer], then sends
 * [Action.Ink] to have it shown: a key, so it comes in order with the rest. Each reading is of a
 * piece the pad numbered (a character), later pieces higher. Here, in order with the keys, it is
 * decided what each reading does: one of a piece written already reads it again (more strokes, or
 * fewer: none takes it out); a later piece's adds a character, unless read as nothing yet (a first
 * dot); one of a piece already [ended], or taken out, comes too late and is dropped.
 *
 * The sentence goes on from the text before the cursor ([Action.Context]) and what was put in
 * since, read as words by [words].
 *
 * A sentence picked other than the first, while [learning], is learned by [user], its words as the
 * decoder read them: the user chose it over what the ink read best. The first, put in by space,
 * enter or a tap alike, is not: the readings are often wrong, and one not noticed, learned, would
 * come back.
 *
 * With a [predictor], what may follow is offered once a sentence is put in, as pinyin offers it:
 * a word picked goes in and is learned (the user chose it), and what may follow it is offered in
 * turn; a stroke, backspace or any key takes the offer away. Not while nothing is [learning].
 *
 * The 性能档位, as pinyin has it: a [reranker] weighs the decoder's best sentences again by how they
 * read after the text before, as each stroke is read; a [refiner], larger and slower, does so once
 * a character is [finished] (the pen up long enough), in slices while the user pauses
 * ([Action.Refine]): a character half written is no sentence to weigh, as pinyin weighs none ending
 * in half a syllable. Most of the gain is in the sentence shown as it is written (CASIA: see
 * ime-eval handwriting).
 */
class HandwritingSession(
    private val decoder: HandwritingDecoder?,
    private val words: TextWords?,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val user: UserModel? = null,
    private val predictor: Predictor? = null,
    private val reranker: SentencePicker? = null,
    private val refiner: SentenceRefiner? = null,
) : Session {

    // a reading of a piece, or none: the piece finished (the pen up long enough)
    private class Offered(val reading: InkReading?, val piece: Int)

    private class Written(val piece: Int, var reading: InkReading)

    private val offered = ArrayDeque<Offered>()
    private val written = ArrayList<Written>()

    // what the characters written read as: the best sentence first, then the best for each other last character
    private var sentences: List<String> = emptyList()

    // each sentence's words, as [user] learns them: see [HandwritingDecoder.Decoding.words]
    private var sentenceWords: List<List<Entry>?> = emptyList()

    // what may follow the text put in, offered in place of [sentences] while nothing is written
    private var predictions: List<Candidate> = emptyList()

    // the last decoding; the one [refiner] is still to weigh, and the slices it took so far
    private var decoding: HandwritingDecoder.Decoding? = null
    private var unrefined: HandwritingDecoder.Decoding? = null
    private var refined = 0

    // the last word put in, as the user model has it: what a prediction picked is learned after
    private var lastEntry: Entry? = null
    private var page = 0

    // the latest piece offered
    private var latest = NO_PIECE

    /**
     * The latest piece ended other than by a later piece's reading: put in, taken back, the cursor
     * moved. The pad wipes a piece's ink once it is ended; its readings still to come are dropped.
     */
    var ended = NO_PIECE
        private set

    // the text before the cursor, as far as it is known: the field's, and what was put in since
    private var before = ""

    override var learning = true

    /** What the pad read of its [piece], to be shown at the next [Action.Ink]: those offered by then, in turn. */
    fun offer(reading: InkReading, piece: Int) {
        offered.addLast(Offered(reading, piece))
    }

    /** The pad's [piece] finished, after its readings: told at the next [Action.Ink], in turn with them. */
    fun finished(piece: Int) {
        offered.addLast(Offered(null, piece))
    }

    override fun reads(c: Char): Boolean = false

    override fun apply(action: Action): Snapshot {
        // the order shown is the one to weigh: anything but a slice of the weighing changes it, or
        // the page, as pinyin has it
        if (action != Action.Refine) unrefined = null
        return act(action)
    }

    private fun act(action: Action): Snapshot = when (action) {
        Action.Ink -> show()
        // with nothing written the app's: an offer goes away
        Action.Backspace -> if (written.isEmpty()) unoffer(handled = false) else takeBack()
        is Action.Select -> if (action.index in 0 until pageSize) pick(page * pageSize + action.index) else snapshot(handled = written.isNotEmpty())
        is Action.Pick -> pick(action.index)
        Action.NextPage -> turn(page + 1)
        Action.PreviousPage -> turn(page - 1)
        // as pinyin's enter: put in, nothing offered after it
        Action.CommitRaw -> if (written.isEmpty()) unoffer(handled = false) else pick(0, offer = false)
        // a key the app takes: the best sentence goes in before it
        is Action.Key -> (if (written.isEmpty()) unoffer() else pick(0, offer = false)).copy(handled = false)
        is Action.Context -> {
            before = kept(action.before)
            lastEntry = null
            reset()
        }
        Action.Reset -> {
            before = ""
            lastEntry = null
            reset()
        }
        is Action.Cursor -> snapshot(handled = false)
        Action.Refine -> refine()
        is Action.Forget, is Action.Pin, is Action.Unpin, is Action.Block, is Action.Syllable, is Action.Letter -> snapshot()
    }

    override fun candidates(from: Int, count: Int): List<Choice> {
        val all = shown()
        return all.subList(minOf(from, all.size), minOf(from + count, all.size)).map { Choice(it) }
    }

    override fun offers(index: Int): Set<Offer> = emptySet()

    override fun passedOn() {
        before = ""
        lastEntry = null
    }

    private fun show(): Snapshot {
        var changed = false
        var finished = false
        while (offered.isNotEmpty()) {
            val o = offered.removeFirst()
            if (o.reading == null) finished = o.piece == written.lastOrNull()?.piece else changed = take(o, o.reading) || changed
        }
        if (changed) decode()
        // the last character finished, and nothing written since: its sentence weighed again
        if (finished && !changed) {
            refined = 0
            unrefined = decoding.takeIf { refiner != null && written.isNotEmpty() && (it?.best?.size ?: 0) > 1 }
        }
        return snapshot()
    }

    // the text before the cursor as kept: as much as a sentence model reads, only the last words where nothing is learned
    private fun kept(text: String) = text.takeLast(if (learning) KEPT else WORDS_KEPT)

    private fun unoffer(handled: Boolean = true): Snapshot {
        predictions = emptyList()
        lastEntry = null
        page = 0
        return snapshot(handled = handled)
    }

    // what may follow [before], as far as it is words
    private fun predict() {
        page = 0
        val p = predictor
        val w = words
        predictions = if (p == null || w == null || !learning) emptyList() else TextWords.previous(w.lastTwo(before)).let { (prev2, prev) -> p.predict(prev2, prev) }
    }

    // what [o], read as [reading], does to the characters written: whether it changed them
    private fun take(o: Offered, reading: InkReading): Boolean {
        latest = maxOf(latest, o.piece)
        if (o.piece <= ended) return false
        val at = written.indexOfFirst { it.piece == o.piece }
        val empty = reading.texts.isEmpty()
        when {
            // a piece written, read again; read as nothing, taken out: the pad writes on in it
            at >= 0 -> if (empty) written.removeAt(at) else written[at].reading = reading
            // a later piece: another character, once there is something to read
            (written.isEmpty() || o.piece > written.last().piece) && !empty -> written += Written(o.piece, reading)
            // a later piece's first dot, too little to read; or one taken out already
            else -> return false
        }
        return true
    }

    private fun decode() {
        page = 0
        predictions = emptyList()
        val d = decoder
        val decoded = when {
            written.isEmpty() -> HandwritingDecoder.Decoding(emptyList())
            d == null -> {
                val head = written.dropLast(1).joinToString("") { it.reading.texts.first() }
                HandwritingDecoder.Decoding(written.last().reading.texts.map { head + it })
            }
            else -> {
                val (prev2, prev) = words?.let { TextWords.previous(it.lastTwo(before)) } ?: (NO_WORD to NO_WORD)
                d.decode(written.map { it.reading }, prev2, prev)
            }
        }
        place(decoded, if (d == null) 0 else reranker?.pick(context(), decoded.best, decoded.bestScores) ?: 0)
        decoding = decoded
    }

    // [decoded]'s sentences, its best at [first] put first in place of the one ending as it does
    private fun place(decoded: HandwritingDecoder.Decoding, first: Int) {
        if (first <= 0 || first >= decoded.best.size) {
            sentences = decoded.sentences
            sentenceWords = decoded.words
            return
        }
        val top = decoded.best[first]
        val replaced = decoded.sentences.indexOfFirst { lastCharacter(it) == lastCharacter(top) }
        sentences = listOf(top) + decoded.sentences.filterIndexed { i, _ -> i != replaced }
        sentenceWords = listOf(decoded.bestWords[first]) + decoded.words.filterIndexed { i, _ -> i != replaced }
    }

    // what a sentence model reads on from: nothing where the user learns nothing, as pinyin keeps it
    private fun context() = if (learning) before else ""

    private fun refine(): Snapshot {
        val decoded = unrefined ?: return snapshot()
        if (refiner == null || refined++ >= refiner.slices) {
            unrefined = null
            return snapshot()
        }
        val picked = refiner.refine(context(), decoded.best, decoded.bestScores, REFINE_BUDGET) ?: return snapshot()
        unrefined = null
        if (picked != SentenceRefiner.NONE) place(decoded, picked)
        return snapshot()
    }

    // the candidates: what may follow while offered, else what the last character may be
    private fun shown(): List<String> = if (written.isEmpty()) predictions.map { it.text } else sentences.map { lastCharacter(it) }

    /** Puts in the sentence or the offer at [index]; with [offer], what may follow it is offered then. */
    private fun pick(index: Int, offer: Boolean = true): Snapshot {
        if (written.isEmpty()) return pickPrediction(index)
        val text = sentences.getOrNull(index) ?: return snapshot(handled = true)
        val words = sentenceWords[index]
        if (index > 0 && learning) words?.let { user?.learn(null, it) }
        lastEntry = words?.lastOrNull()
        before = kept(before + text)
        ended = maxOf(ended, written.last().piece)
        written.clear()
        sentences = emptyList()
        sentenceWords = emptyList()
        decoding = null
        unrefined = null
        if (offer) predict() else page = 0
        return snapshot(commit = text)
    }

    private fun pickPrediction(index: Int): Snapshot {
        val c = predictions.getOrNull(index) ?: return snapshot(handled = false)
        // after the word before it, as pinyin learns a prediction: it comes up after that word again
        val entry = c.words.singleOrNull()?.let { w -> predictor?.reading(w)?.let { Entry(c.text, it) } }
        if (learning && entry != null) user?.learn(lastEntry, listOf(entry))
        lastEntry = entry
        before = kept(before + c.text)
        predict()
        return snapshot(commit = c.text)
    }

    // the last character taken back
    private fun takeBack(): Snapshot {
        ended = maxOf(ended, written.removeAt(written.size - 1).piece)
        decode()
        return snapshot()
    }

    // the cursor moved, or another field: every piece read so far ended, those waiting too
    private fun reset(): Snapshot {
        latest = maxOf(latest, offered.maxOfOrNull { it.piece } ?: NO_PIECE)
        offered.clear()
        ended = maxOf(ended, latest)
        written.clear()
        sentences = emptyList()
        sentenceWords = emptyList()
        decoding = null
        unrefined = null
        predictions = emptyList()
        page = 0
        return snapshot()
    }

    private fun turn(to: Int): Snapshot {
        if (to < 0 || to * pageSize >= shown().size) return snapshot()
        page = to
        return snapshot()
    }

    private fun snapshot(commit: String = "", handled: Boolean = true): Snapshot {
        val all = shown()
        val from = page * pageSize
        return Snapshot(
            commit = commit,
            preedit = if (written.isEmpty()) "" else sentences.firstOrNull().orEmpty(),
            candidates = all.subList(minOf(from, all.size), minOf(from + pageSize, all.size)),
            page = page,
            hasPreviousPage = page > 0,
            hasNextPage = from + pageSize < all.size,
            handled = handled,
            predicting = written.isEmpty() && predictions.isNotEmpty(),
            refines = unrefined != null,
            total = all.size,
            first = from,
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 5

        /** No piece: the pad's are numbered from 0. */
        const val NO_PIECE = -1

        // the text before the cursor kept: the last two words, as TextWords reads them, and the
        // sentence a sentence model reads on from, as much as pinyin gives it (PinyinSession.RECENT);
        // the words alone where nothing is learned
        private const val KEPT = 128
        private const val WORDS_KEPT = 3 * TextWords.MAX_WORD

        // the refiner's work per Action.Refine: as pinyin's (PinyinSession.REFINE_BUDGET)
        private const val REFINE_BUDGET = 1
    }
}
