/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.KeyedRows
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.learn.rankView
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.CommitJournal
import org.fcitx.fcitx5.android.engine.session.FieldJournal
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Predictions
import org.fcitx.fcitx5.android.engine.session.RefineSlices
import org.fcitx.fcitx5.android.engine.session.Retype
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.table.PhraseRules
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
 * A sentence put in, while [learning], is learned into [habits] (U-29, PLAN §2.7, [KeyedRows]):
 * each character under what the ink of it was read as (the first three readings, in any order:
 * the same input, as no two hands write alike), no word before it; the last as picked, corrected
 * where the first shown was another, the ones before it used as they were read. The whole
 * sentence, by its words' text, after the word put in before it: for 联想, never for pinyin's
 * order (D28), never made a word. Not the layer prior (LayerPrior.learn), as pinyin's picks teach
 * it: a pick here puts the ink right, it says nothing of a word's layer. The word before is the
 * one put in, learned or not (a first sentence's last): the pick after it is the user's choice
 * either way.
 *
 * A sentence deleted as soon as it was put in is taken back, as pinyin's (U-03, [FieldJournal]); one
 * written again for it, a character in what the ink of the deleted one's was read as, is a pick
 * there instead, its character's first after two such running ([Retype.matchKeyed]). A 联想 pick
 * deleted is taken back too.
 *
 * With a [predictor], what may follow is offered once a sentence is put in, as pinyin offers it:
 * a word picked goes in and is learned (the user chose it), and what may follow it is offered in
 * turn; a stroke, backspace or any key takes the offer away. Not while nothing is [learning]. A
 * long press on a prediction forgets what was learned of it, or blocks it ([block]), as pinyin's;
 * one on a candidate does so to the character it shows (U-29, U-82): what was learned of it
 * forgotten everywhere ([forgotten]), or blocked in every reading, as the decoder leaves a blocked
 * character out; never pinned (C18: no code a pin would hold, a slip would put the wrong
 * character in on its own from then on).
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
    private val block: ((Entry) -> Unit)? = null,
    /** Told the text of a prediction or a candidate forgotten here ([Action.Forget]), to forget it everywhere. */
    private val forgotten: (String) -> Unit = {},
    /** The latest piece ended already: of a session this one is made in place of, see [latestPiece]. */
    ended: Int = NO_PIECE,
    /** The learning record what is put in is written to. */
    private val habits: Habits = Habits.OFF,
    /** Traditional text to simplified, as the field's text is compared with what was put in (the 繁体 output's). */
    simplify: (String) -> String = { it },
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

    // how the habits ranked [sentences], each one's, under [rankedUnder]: what 为什么排在这 says (U-81)
    private var ranks: List<HabitRanker.Ranked<HandwritingDecoder.Sentence>> = emptyList()
    private var rankedUnder: Code? = null

    // what may follow the text put in, offered in place of [sentences] while nothing is written;
    // the text before the cursor it goes on from, and the last word put in
    private val predictions = Predictions(predictor, words, user, block, forgotten, habits = habits) { learning }

    // the last decoding; the one [refiner] is still to weigh
    private var decoding: HandwritingDecoder.Decoding? = null
    private val unrefined = RefineSlices<HandwritingDecoder.Decoding>(refiner)

    private var page = 0

    // the field as this session wrote it (U-03); what each sentence of it was written as, a set of
    // what the ink of each character read as, by its record (seqs are given again after an undo)
    private val inked = ArrayList<Pair<Commit, List<Set<String>>>>()
    private val journal = FieldJournal({ habits }, simplify) { inked.clear() }

    // the deleted commit the last commit typed again in part, how many of its characters, and that
    // commit: once it is deleted too, what it typed is to be typed again (review F3124)
    private var retyped: Triple<Commit, Int, Commit>? = null

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
        // what a Backspace deleted whole taken back, now that what came next is not a context saying otherwise (U-03)
        journal.before(action, learning)
        return act(action)
    }

    // what changes neither the sentences shown nor the page: an Ink (see apply), the cursor, which
    // handwriting keeps none of, a prediction forgotten or blocked (a candidate's reads anew, see
    // pressed), what no handwriting does
    private fun keepsOrder(action: Action) = when (action) {
        Action.Refine, Action.Ink, Action.Glide, is Action.Cursor, is Action.Step, is Action.Forget, is Action.Block, is Action.Pin, is Action.Unpin, is Action.Syllable, is Action.Letter, is Action.NineKey -> true
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
            predictions.learnFrom(action)
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
        Action.Glide, is Action.Pin, is Action.Unpin, is Action.Syllable, is Action.Letter, is Action.NineKey -> snapshot()
    }

    override fun candidates(from: Int, count: Int): List<Choice> {
        val all = shown()
        return all.subList(minOf(from, all.size), minOf(from + count, all.size)).map { Choice(it) }
    }

    // while predicting, as pinyin's: see Predictions.offers
    override fun offers(index: Int): Set<Offer> = if (written.isEmpty()) predictions.offers(index) else writtenOffers(index)

    // what may follow says what it goes on from, for 不再联想 (U-72); the characters written say nothing yet
    override fun why(index: Int): Why? =
        if (written.isEmpty()) predictions.why(index) else ranks.getOrNull(index)?.let { Why(it.tier, it.uses, rankedUnder) }

    // what was written read anew, or what may follow offered anew (a prediction turned off back in
    // it): the session, its pad and what is written on it kept after an undo (review-int S1)
    override fun reread(): Snapshot? {
        // the undo may have unblocked a character the cached search left out (as pressed forgets it)
        decoder?.forgetSearch()
        if (written.isNotEmpty()) decode() else predict()
        return snapshot()
    }

    override val rereads get() = true

    override fun fieldFlags(flags: Int) {
        predictions.learnFlags = flags
    }

    // a candidate's, as the class says: forgotten where something is learned of it
    private fun writtenOffers(index: Int): Set<Offer> {
        val text = sentences.getOrNull(index)?.let { lastCharacter(it.text) }
        if (text == null || !learning) return emptySet()
        return buildSet {
            if (habits.knows(text)) add(Offer.FORGET)
            if (block != null && decoder?.alone(text) != null) add(Offer.BLOCK)
        }
    }

    override fun passedOn() {
        journal.passedOn()
        predictions.forgetBefore()
    }

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
        journal.backspace()
        predictions.deleted()
        return unoffer(handled = false)
    }

    // a key the app takes: the best sentence goes in before it, and what is written next goes on
    // from the key, not from the words before it, as a table's code does
    private fun key(c: Char): Snapshot {
        val put = if (written.isEmpty()) unoffer() else pick(0, offer = false)
        // the key goes into the field, not written here (U-03), as pinyin's leave(); the host passes it on too (passedOn)
        journal.unverify()
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
            sentences = byHabit(decoded.endings)
            return
        }
        val top = decoded.top[first]
        val replaced = decoded.endings.indexOfFirst { lastCharacter(it.text) == lastCharacter(top.text) }
        sentences = byHabit(listOf(top) + decoded.endings.filterIndexed { i, _ -> i != replaced })
    }

    // [read] reordered by the habits under the character last written, no word before (U-29, PLAN
    // §2.7): its first after two corrections running, then those used by uses, then as read. No pin
    private fun byHabit(read: List<HandwritingDecoder.Sentence>): List<HandwritingDecoder.Sentence> {
        ranks = emptyList()
        rankedUnder = null
        val h = habits
        if (!h.learns || written.isEmpty() || read.isEmpty()) return read
        val code = Code(Kinds.HANDWRITING, KeyedRows.handwriting(written.last().reading.texts))
        ranks = HabitRanker.rank(read.map { HabitRanker.Item(it, Words.of(lastCharacter(it.text)), single = true) }, code, "", view(h))
        rankedUnder = code
        return ranks.map { it.value }
    }

    // characters, no word of the user's or imported a tier, nothing pinned
    private fun view(h: Habits) = h.rankView(pins = false, mine = false, imports = false)

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
        val words = sentence.words
        learn(sentence, index)
        predictions.wrote(sentence.text, words?.lastOrNull())
        ended = maxOf(ended, written.last().piece)
        written.clear()
        sentences = emptyList()
        decoding = null
        unrefined.drop()
        if (offer) predict() else page = 0
        return snapshot(commit = sentence.text)
    }

    // [sentence], at [index] of those shown, learned as the class says, and kept in the journal
    private fun learn(sentence: HandwritingDecoder.Sentence, index: Int) {
        if (!learning || !habits.learns) return journal.committed(sentence.text, null, learning)
        val chars = PhraseRules.codePoints(sentence.text)
        val first = sentences.firstOrNull()?.let { lastCharacter(it.text) }.orEmpty()
        // a character for each one written, as the decoder reads them; else the last alone
        val each = chars.size == written.size
        val from = if (each) 0 else written.size - 1
        val picks = (from until written.size).map { i ->
            val keys = KeyedRows.handwriting(written[i].reading.texts)
            val last = i == written.size - 1
            val text = if (last) lastCharacter(sentence.text) else chars[i]
            KeyedRows.Pick(keys, text, if (last && index > 0) Row.HOW_PICKED else Row.HOW_FIRST, if (last) first else text)
        }
        // its words as the decoder read them, the reading it gives each (the dictionary's likeliest,
        // as 添加词 fills one in): a sentence that is no word, put in twice, is the user's word typed
        // so (U-06, UAT HW-20). Counted by their text alone, never for pinyin's order (D28, LearnState.heard)
        val read = sentence.words
        val whole = read?.map { Word(it.text, it.syllables.joinToString(" ") { s -> Syllables.spelling(s) }) } ?: listOf(Word(sentence.text, ""))
        val origin = read?.let { origin(sentence.text, it) } ?: Row.DICTIONARY
        val rows = KeyedRows.of(Kinds.HANDWRITING, picks, whole, predictions.wordBefore(), origin)
        // sentences deleted whole just before, written again by this one, a character for each written (U-03)
        val deleted = journal.pending
        val again = retype(deleted, each, chars, rows)
        val commit = habits.record(rows + again?.match?.rows.orEmpty(), flags = predictions.learnFlags)
        retyped = again?.takeIf { it.skip > 0 && commit != null }?.let { Triple(deleted[it.match.typedAgain], it.skip, commit!!) }
        if (commit != null && each) {
            inked += commit to written.map { it.reading.texts.toSet() }
            while (inked.size > CommitJournal.KEPT) inked.removeAt(0)
        }
        journal.committed(sentence.text, commit, learning, again?.match)
    }

    // what of [deleted] [chars] typed again, a character for each written ([each]); none where nothing was deleted
    private fun retype(deleted: List<Commit>, each: Boolean, chars: List<String>, rows: List<Row>): Retype.Keyed? = when {
        deleted.isEmpty() -> null
        !each -> Retype.Keyed(Retype.Match(emptyList(), 0, goesOn = false), 0)
        else -> {
            // what the last commit typed of the first deleted, unless it was deleted too (review F3124)
            val skip = retyped?.takeIf { r -> r.first === deleted.first() && deleted.none { it === r.third } }?.second ?: 0
            Retype.matchKeyed(deleted, skip, chars, rows, ::inCandidates, ::writtenBy)
        }
    }

    // how many characters [deleted] wrote, as its ink has them
    private fun writtenBy(deleted: Commit) = inked.firstOrNull { it.first === deleted }?.second?.size ?: 0

    // whether [text] is among what the ink of [deleted]'s [k]th character was read as
    private fun inCandidates(deleted: Commit, k: Int, text: String) = inked.firstOrNull { it.first === deleted }?.second?.getOrNull(k)?.contains(text) == true

    // where [text] read as [read] comes from, as pinyin files it (CommitRecorder.origin): none's only if no word of the model nor the user's
    private fun origin(text: String, read: List<Entry>): Int {
        val entry = Entry(text, read.flatMap { it.syllables.asList() }.toIntArray())
        val known = user?.origin(entry) ?: Row.NONE
        return if (known == Row.NONE && words?.id(text)?.takeIf { it != NO_WORD } != null) Row.DICTIONARY else known
    }

    // none there: kept while predicting, as pinyin keeps a pick past its candidates
    private fun pickPrediction(index: Int): Snapshot {
        val text = predictions.pick(index) ?: return snapshot(handled = predictions.offered.isNotEmpty())
        // deleted whole at once, the pick is taken back (U-03); it types nothing deleted again
        journal.committed(text, predictions.picked, learning)
        page = 0
        return snapshot(commit = text)
    }

    // the prediction at [index] forgotten or blocked, as the long press offered: what may follow
    // offered again; or the candidate's character, what is written read again without it
    private fun pressed(index: Int, offer: Offer): Snapshot {
        if (written.isEmpty()) {
            if (predictions.press(index, offer)) page = 0
            return snapshot()
        }
        if (offer !in writtenOffers(index)) return snapshot()
        val text = lastCharacter(sentences[index].text)
        if (offer == Offer.FORGET) forgotten(text) else decoder?.alone(text)?.let { block?.invoke(it) }
        // the words the last search found are as they were before: scored as learned, or not blocked
        decoder?.forgetSearch()
        decode()
        if (written.last().finished) refineLast()
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
            actionable = predicting || written.isNotEmpty(),
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
