/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Predictions
import org.fcitx.fcitx5.android.engine.session.RefineSlices
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
 * in. Enter or a key the app takes puts the best in, what is written next following the key;
 * backspace takes the last character back. With no [decoder] (no pinyin data), each character is
 * its first reading.
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
 * A sentence picked other than the first, while [learning], is learned by [user], after the word
 * put in before it: the user chose it over what the ink read best. Of several words, at most
 * [UserModel.MAX_PHRASE] characters, it is one phrase, as pinyin learns a pick it corrected, where
 * each word's reading is sure ([HandwritingDecoder.sure]); else its words as the decoder read them. The first, put in by space, enter or a tap alike, is not: the readings are often wrong,
 * and one not noticed, learned, would come back. Nor is the layer prior (LayerPrior.learn), as
 * pinyin's picks teach it: a pick here puts the ink right, it says nothing of a word's layer.
 * The word before is the one put in, learned or not (a first sentence's last): the pick after
 * it is the user's choice either way.
 *
 * With a [predictor], what may follow is offered once a sentence is put in, as pinyin offers it:
 * a word picked goes in and is learned (the user chose it), and what may follow it is offered in
 * turn; a stroke, backspace or any key takes the offer away. Not while nothing is [learning]. A
 * long press on a prediction forgets what was learned of it, or blocks it ([block]), as pinyin's;
 * one on a candidate offers nothing: it shows one character of the sentence a pick puts in, and
 * neither is what the menu would say it acts on.
 *
 * The 性能档位, as pinyin has it: a [reranker] weighs the decoder's best sentences again by how they
 * read after the text before, as each stroke is read; a [refiner], larger and slower, does so once
 * a character is [finished] (the pen up long enough), in slices while the user pauses
 * ([Action.Refine]): a character half written is no sentence to weigh, as pinyin weighs none ending
 * in half a syllable. The last character taken back, the one before it is weighed again if it was
 * finished; what changes neither the sentences nor the page (the cursor, an Ink that read nothing
 * new) leaves the weighing be. Most of the gain is in the sentence shown as it is written (CASIA:
 * see ime-eval handwriting).
 */
class HandwritingSession(
    private val decoder: HandwritingDecoder?,
    private val words: TextWords?,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val user: UserModel? = null,
    private val predictor: Predictor? = null,
    private val reranker: SentencePicker? = null,
    private val refiner: SentenceRefiner? = null,
    /** Keeps a prediction the user blocked ([Action.Block]), blocked in [user] too; none offered without. */
    block: ((Entry) -> Unit)? = null,
    /** Told the text of a prediction forgotten here ([Action.Forget]), for the input methods that count their own (the tables) to forget it too. */
    forgotten: (String) -> Unit = {},
    /** The latest piece ended already: of a session this one is made in place of, see [latestPiece]. */
    ended: Int = NO_PIECE,
) : Session {

    // a reading of a piece, or none: the piece finished (the pen up long enough)
    private class Offered(val reading: InkReading?, val piece: Int)

    private class Written(val piece: Int, var reading: InkReading) {
        // the pen up long enough after it: weighed by the refiner while it is the last
        var finished = false
    }

    private val offered = ArrayDeque<Offered>()
    private val written = ArrayList<Written>()

    // what the characters written read as: the best sentence first, then the best for each other last character
    private var sentences: List<HandwritingDecoder.Sentence> = emptyList()

    // what may follow the text put in, offered in place of [sentences] while nothing is written;
    // the text before the cursor it goes on from, and the last word put in
    private val predictions = Predictions(predictor, words, user, block, forgotten) { learning }

    // the last decoding; the one [refiner] is still to weigh
    private var decoding: HandwritingDecoder.Decoding? = null
    private val unrefined = RefineSlices<HandwritingDecoder.Decoding>(refiner)

    private var page = 0

    // the latest piece offered
    private var latest = NO_PIECE

    /**
     * The latest piece ended other than by a later piece's reading: put in, taken back, the cursor
     * moved. The pad wipes a piece's ink once it is ended; its readings still to come are dropped.
     */
    var ended = ended
        private set

    /**
     * The latest piece this session heard of, ended or not: a session made in its place (the
     * settings changed) starts with it [ended], every piece read so far ended as a moved cursor
     * ends them, for the pad numbers its pieces on and their readings may still be on the way.
     */
    val latestPiece: Int get() = maxOf(ended, latest, offered.maxOfOrNull { it.piece } ?: NO_PIECE)

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
        // the order shown is the one to weigh: what may change it, or the page, drops the
        // weighing, as pinyin has it; an Ink drops it only if it reads something new (decode)
        if (!keepsOrder(action)) unrefined.drop()
        return act(action)
    }

    // what changes neither the sentences shown nor the page: an Ink (see apply), the cursor, which
    // handwriting keeps none of, a prediction forgotten or blocked, what no handwriting does
    private fun keepsOrder(action: Action) = when (action) {
        Action.Refine, Action.Ink, Action.Glide, is Action.Cursor, is Action.Step, is Action.Forget, is Action.Block, is Action.Pin, is Action.Unpin, is Action.Syllable, is Action.Letter -> true
        else -> false
    }

    private fun act(action: Action): Snapshot = when (action) {
        Action.Ink -> show()
        // with nothing written the app's: an offer goes away
        Action.Backspace -> if (written.isEmpty()) deleted() else takeBack()
        is Action.Select -> if (action.index in 0 until pageSize) pick(page * pageSize + action.index) else snapshot(handled = written.isNotEmpty())
        is Action.Pick -> pick(action.index)
        Action.NextPage -> turn(page + 1)
        Action.PreviousPage -> turn(page - 1)
        // as pinyin's enter: put in, nothing offered after it
        Action.CommitRaw -> if (written.isEmpty()) unoffer(handled = false) else pick(0, offer = false)
        is Action.Key -> key(action.char)
        is Action.Context -> {
            predictions.goOnFrom(action.before)
            predictions.lastEntry = null
            reset()
        }
        Action.Reset -> {
            predictions.forgetBefore()
            reset()
        }
        is Action.Cursor, is Action.Step -> snapshot(handled = false)
        Action.Refine -> refine()
        is Action.Forget -> pressed(action.index, Offer.FORGET)
        is Action.Block -> pressed(action.index, Offer.BLOCK)
        Action.Glide, is Action.Pin, is Action.Unpin, is Action.Syllable, is Action.Letter -> snapshot()
    }

    override fun candidates(from: Int, count: Int): List<Choice> {
        val all = shown()
        return all.subList(minOf(from, all.size), minOf(from + count, all.size)).map { Choice(it) }
    }

    // while predicting, as pinyin's: see Predictions.offers
    override fun offers(index: Int): Set<Offer> = if (written.isNotEmpty()) emptySet() else predictions.offers(index)

    override fun passedOn() = predictions.forgetBefore()

    private fun show(): Snapshot {
        var changed = false
        var finished = false
        while (offered.isNotEmpty()) {
            val o = offered.removeFirst()
            val reading = o.reading
            if (reading == null) finished = finish(o.piece) || finished else changed = take(o, reading) || changed
        }
        if (changed) decode()
        // the last character finished, its sentence as now read: weighed again. Told in the same
        // Ink as the last reading of it, or a later one's adding a character, as much as told alone
        if ((changed || finished) && written.lastOrNull()?.finished == true) refineLast()
        return snapshot()
    }

    // the character of [piece] finished: whether it is the last
    private fun finish(piece: Int): Boolean {
        val w = written.lastOrNull { it.piece == piece } ?: return false
        w.finished = true
        return w === written.last()
    }

    private fun unoffer(handled: Boolean = true): Snapshot {
        predictions.unoffer()
        page = 0
        return snapshot(handled = handled)
    }

    // nothing written: the app deletes, and the offer goes; what it deletes is no longer before, as a table has it
    private fun deleted(): Snapshot {
        predictions.deleted()
        return unoffer(handled = false)
    }

    // a key the app takes: the best sentence goes in before it, and what is written next goes on
    // from the key, not from the words before it, as a table's code does
    private fun key(c: Char): Snapshot {
        val put = if (written.isEmpty()) unoffer() else pick(0, offer = false)
        predictions.wrote(c.toString(), null)
        return put.copy(handled = false)
    }

    // what may follow the text before, as far as it is words
    private fun predict() {
        page = 0
        predictions.predict()
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
        predictions.unoffer()
        // read anew: what the refiner was to weigh is not what shows
        unrefined.drop()
        val d = decoder
        val decoded = when {
            written.isEmpty() -> HandwritingDecoder.Decoding(emptyList())
            d == null -> {
                val head = written.dropLast(1).joinToString("") { it.reading.texts.first() }
                HandwritingDecoder.Decoding(written.last().reading.texts.map { HandwritingDecoder.Sentence(head + it) })
            }
            else -> {
                val (prev2, prev) = words?.let { TextWords.previous(it.lastTwo(predictions.before)) } ?: (NO_WORD to NO_WORD)
                d.decode(written.map { it.reading }, prev2, prev)
            }
        }
        place(decoded, if (d == null) 0 else reranker?.pick(predictions.context, decoded.best, decoded.bestScores) ?: 0)
        decoding = decoded
    }

    // [decoded]'s sentences, its best at [first] put first in place of the one ending as it does
    private fun place(decoded: HandwritingDecoder.Decoding, first: Int) {
        if (first <= 0 || first >= decoded.top.size) {
            sentences = decoded.endings
            return
        }
        val top = decoded.top[first]
        val replaced = decoded.endings.indexOfFirst { lastCharacter(it.text) == lastCharacter(top.text) }
        sentences = listOf(top) + decoded.endings.filterIndexed { i, _ -> i != replaced }
    }

    // the last character finished: its sentence for the refiner to weigh while the user pauses
    private fun refineLast() {
        unrefined.start(decoding, decoding?.top?.size ?: 0)
    }

    private fun refine(): Snapshot {
        unrefined.slice(predictions.context, { it.best }, { it.bestScores }, ::place)
        return snapshot()
    }

    // the candidates: what may follow while offered, else what the last character may be
    private fun shown(): List<String> = if (written.isEmpty()) predictions.offered.map { it.text } else sentences.map { lastCharacter(it.text) }

    /** Puts in the sentence or the offer at [index]; with [offer], what may follow it is offered then. */
    private fun pick(index: Int, offer: Boolean = true): Snapshot {
        if (written.isEmpty()) return pickPrediction(index)
        val sentence = sentences.getOrNull(index) ?: return snapshot(handled = true)
        // after the word put in before it, as pinyin learns what is picked: it comes up after that word
        // again; words of several, short enough, as one phrase (the user corrected the reading to get it),
        // if each word's reading is sure: a character of several readings (行, 长) read as the decoder
        // guessed would make a word pinyin offers at the wrong reading and lacks at the right one
        val words = sentence.words?.let { w ->
            val sure = index > 0 && w.size > 1 && w.all { decoder?.sure(it) == true }
            (if (sure) UserModel.phrase(sentence.text, w)?.let(::listOf) else null) ?: w
        }
        if (index > 0 && learning) words?.let { user?.learn(predictions.lastEntry, it) }
        predictions.wrote(sentence.text, words?.lastOrNull())
        ended = maxOf(ended, written.last().piece)
        written.clear()
        sentences = emptyList()
        decoding = null
        unrefined.drop()
        if (offer) predict() else page = 0
        return snapshot(commit = sentence.text)
    }

    // none there: kept while predicting, as pinyin keeps a pick past its candidates
    private fun pickPrediction(index: Int): Snapshot {
        val text = predictions.pick(index) ?: return snapshot(handled = predictions.offered.isNotEmpty())
        page = 0
        return snapshot(commit = text)
    }

    // the prediction at [index] forgotten or blocked, as the long press offered: what may follow offered again
    private fun pressed(index: Int, offer: Offer): Snapshot {
        if (written.isEmpty() && predictions.press(index, offer)) page = 0
        return snapshot()
    }

    // the last character taken back
    private fun takeBack(): Snapshot {
        ended = maxOf(ended, written.removeAt(written.size - 1).piece)
        decode()
        // the one now last was finished: weighed again, as before the one taken back was written
        if (written.lastOrNull()?.finished == true) refineLast()
        return snapshot()
    }

    // the cursor moved, or another field: every piece read so far ended, those waiting too
    private fun reset(): Snapshot {
        latest = maxOf(latest, offered.maxOfOrNull { it.piece } ?: NO_PIECE)
        offered.clear()
        ended = maxOf(ended, latest)
        written.clear()
        sentences = emptyList()
        decoding = null
        unrefined.drop()
        predictions.unoffer()
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
        val predicting = written.isEmpty() && predictions.offered.isNotEmpty()
        return Snapshot(
            commit = commit,
            preedit = if (written.isEmpty()) "" else sentences.firstOrNull()?.text.orEmpty(),
            candidates = all.subList(minOf(from, all.size), minOf(from + pageSize, all.size)),
            page = page,
            hasPreviousPage = page > 0,
            hasNextPage = from + pageSize < all.size,
            handled = handled,
            predicting = predicting,
            // a prediction alone: see offers
            actionable = predicting,
            refines = unrefined.refines,
            total = all.size,
            first = from,
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 5

        /** No piece: the pad's are numbered from 0. */
        const val NO_PIECE = -1
    }
}
