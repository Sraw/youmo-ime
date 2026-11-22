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
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.CommitJournal
import org.fcitx.fcitx5.android.engine.session.FieldJournal
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.OfferRules
import org.fcitx.fcitx5.android.engine.session.Pager
import org.fcitx.fcitx5.android.engine.session.Predictions
import org.fcitx.fcitx5.android.engine.session.Retype
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.table.PhraseRules
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

/**
 * Handwriting written a character at a time and read as a sentence by [decoder]: the best sentence is the
 * preedit, candidates are what the last character may be, each as the best sentence ending with it.
 *
 * The pad recognizes off the fcitx thread, hands the reading over with [offer], then sends [Action.Ink] so it
 * applies in order with keys. Readings are per piece, numbered upwards: a reading for an existing piece
 * replaces it, a later piece's adds a character, and one for a piece already [ended] is dropped.
 *
 * While [learning], a sentence put in is learned into [habits]: each character under its ink's first three
 * readings (no two hands write alike), the whole sentence by text for 联想 only. Never pinned: a slip would
 * then put the wrong character in on its own.
 */
class HandwritingSession(
    private val decoder: HandwritingDecoder?,
    private val words: TextWords?,
    pageSize: Int = DEFAULT_PAGE_SIZE,
    private val user: UserModel? = null,
    private val predictor: Predictor? = null,
    private val reranker: SentencePicker? = null,
    private val block: ((Entry) -> Unit)? = null,
    private val forgotten: (String) -> Unit = {},
    ended: Int = NO_PIECE,
    private val habits: Habits = Habits.OFF,
    /** Traditional to simplified, to compare the field's text with what was put in (繁体 output). */
    simplify: (String) -> String = { it },
) : Session {

    private class Offered(val reading: InkReading?, val piece: Int)

    private class Written(val piece: Int, var reading: InkReading)

    private val offered = ArrayDeque<Offered>()
    private val written = ArrayList<Written>()

    private var sentences: List<HandwritingDecoder.Sentence> = emptyList()

    private var ranks: List<HabitRanker.Ranked<HandwritingDecoder.Sentence>> = emptyList()
    private var rankedUnder: Code? = null

    private val predictions = Predictions(predictor, words, user, block, forgotten, habits = habits) { learning }


    private val pager = Pager(pageSize)

    // keyed by record: seqs are reissued after an undo
    private val inked = ArrayList<Pair<Commit, List<Set<String>>>>()
    private val journal = FieldJournal({ habits }, simplify) { inked.clear() }

    // once the partly retyped commit is deleted too, what it typed is to be retyped
    private var retyped: Triple<Commit, Int, Commit>? = null

    private var latest = NO_PIECE

    /** The latest piece ended other than by a later piece's reading; its readings still to come are dropped. */
    var ended = ended
        private set

    /** A replacement session (settings changed) starts with this [ended]: the pad keeps numbering and late readings may still come. */
    val latestPiece: Int get() = maxOf(ended, latest, offered.maxOfOrNull { it.piece } ?: NO_PIECE)

    override var learning = true

    fun hold(ink: Action.Ink) {
        offered.addLast(Offered(ink.reading, ink.piece))
    }

    /** [ink] written over the keys while another input is typed (InkOver): taken and ended, so the pad wipes it. */
    fun drop(ink: Action.Ink) {
        lastInk = ink
        ended = maxOf(ended, ink.piece)
    }

    private var lastInk: Action.Ink? = null

    fun took(ink: Action.Ink): Boolean = ink === lastInk

    override fun reads(c: Char): Boolean = false

    override fun apply(action: Action): Snapshot {
        journal.before(action, learning)
        return if (action == Action.DropPredictions && written.isEmpty()) unoffer() else act(action)
    }

    private fun act(action: Action): Snapshot = when (action) {
        is Action.Ink -> show(action)
        Action.Backspace -> if (written.isEmpty()) deleted() else takeBack()
        is Action.Select -> pager.at(action.index).let { if (it >= 0) pick(it) else snapshot(handled = written.isNotEmpty()) }
        is Action.Pick -> pick(action.index)
        Action.NextPage -> turn(pager.page + 1)
        Action.PreviousPage -> turn(pager.page - 1)
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
        is Action.Forget -> pressed(action.index, Offer.FORGET)
        is Action.Block -> pressed(action.index, Offer.BLOCK)
        is Action.Glide, is Action.Stroke, is Action.Pin, is Action.Unpin, is Action.Syllable, is Action.Letter, is Action.NineKey, Action.LookUp, is Action.Remember, is Action.Unremember, is Action.OfferShown,
        Action.DropPredictions -> snapshot()
    }

    override fun candidates(from: Int, count: Int): List<Choice> {
        val all = shown()
        return all.subList(minOf(from, all.size), minOf(from + count, all.size)).map { Choice(it) }
    }

    override fun offers(index: Int): Set<Offer> = if (written.isEmpty()) predictions.offers(index) else writtenOffers(index)

    override fun why(index: Int): Why? =
        if (written.isEmpty()) predictions.why(index) else ranks.getOrNull(index)?.let { Why(it.tier, it.uses, rankedUnder) }

    override fun lookedUp(index: Int): String? {
        if (written.isEmpty()) return null
        val text = sentences.getOrNull(index)?.text ?: return null
        return text.removeSuffix(lastCharacter(text))
    }

    override fun reread(): Snapshot? {
        // the undo may have unblocked a character the cached search left out
        decoder?.forgetSearch()
        if (written.isNotEmpty()) decode() else predict()
        return snapshot()
    }

    override val rereads get() = true

    override fun fieldFlags(flags: Int) {
        predictions.learnFlags = flags
    }

    private fun writtenOffers(index: Int): Set<Offer> {
        val text = sentences.getOrNull(index)?.let { lastCharacter(it.text) }
        if (text == null) return emptySet()
        return OfferRules.offers(learning, forgets = { habits.knows(text) }, blocks = { block != null && decoder?.alone(text) != null })
    }

    override fun passedOn() {
        journal.passedOn()
        predictions.forgetBefore()
    }

    private fun show(ink: Action.Ink): Snapshot {
        lastInk = ink
        hold(ink)
        var changed = false
        while (offered.isNotEmpty()) {
            val o = offered.removeFirst()
            val reading = o.reading ?: continue
            changed = take(o, reading) || changed
        }
        if (changed) decode()
        return snapshot()
    }

    private fun unoffer(handled: Boolean = true): Snapshot {
        predictions.unoffer()
        pager.reset()
        return snapshot(handled = handled)
    }

    // what the app deletes is no longer context, as a table has it
    private fun deleted(): Snapshot {
        journal.backspace()
        predictions.deleted()
        return unoffer(handled = false)
    }

    // what is written next continues from the key, not the words before it, as a table's code does
    private fun key(c: Char): Snapshot {
        val put = if (written.isEmpty()) unoffer() else pick(0, offer = false)
        // the key goes into the field, not written here; the host passes it on too (passedOn)
        journal.unverify()
        predictions.wrote(c.toString(), null)
        return put.copy(handled = false)
    }

    private fun predict() {
        pager.reset()
        predictions.predict()
    }

    private fun take(o: Offered, reading: InkReading): Boolean {
        latest = maxOf(latest, o.piece)
        if (o.piece <= ended) return false
        val at = written.indexOfFirst { it.piece == o.piece }
        val empty = reading.texts.isEmpty()
        when {
            at >= 0 -> if (empty) written.removeAt(at) else written[at].reading = reading
            (written.isEmpty() || o.piece > written.last().piece) && !empty -> written += Written(o.piece, reading)
            else -> return false
        }
        return true
    }

    private fun decode() {
        pager.reset()
        predictions.unoffer()
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
    }

    private fun place(decoded: HandwritingDecoder.Decoding, first: Int) {
        if (first <= 0 || first >= decoded.top.size) {
            sentences = byHabit(decoded.endings)
            return
        }
        val top = decoded.top[first]
        val replaced = decoded.endings.indexOfFirst { lastCharacter(it.text) == lastCharacter(top.text) }
        sentences = byHabit(listOf(top) + decoded.endings.filterIndexed { i, _ -> i != replaced })
    }

    // its first after two corrections in a row, then by uses, then as read; no pin
    private fun byHabit(read: List<HandwritingDecoder.Sentence>): List<HandwritingDecoder.Sentence> {
        ranks = emptyList()
        rankedUnder = null
        val h = habits
        val last = readWritten().lastOrNull()
        if (!h.learns || last == null || read.isEmpty()) return read
        val code = Code(Kinds.HANDWRITING, KeyedRows.handwriting(last.reading.texts))
        ranks = HabitRanker.rank(read.map { HabitRanker.Item(it, Words.of(lastCharacter(it.text)), single = true) }, code, "", view(h))
        rankedUnder = code
        return ranks.map { it.value }
    }

    // a fully held-back position (生僻字屏蔽) is as if unwritten, so no habit is kept under its ink
    private fun readWritten(): List<Written> = decoder?.let { d -> written.filter { d.reads(it.reading) } } ?: written

    private fun view(h: Habits) = h.rankView(pins = false, mine = false, imports = false)

    private fun shown(): List<String> = if (written.isEmpty()) predictions.offered.map { it.text } else sentences.map { lastCharacter(it.text) }

    private fun pick(index: Int, offer: Boolean = true): Snapshot {
        if (written.isEmpty()) return pickPrediction(index)
        val sentence = sentences.getOrNull(index) ?: return snapshot(handled = true)
        val words = sentence.words
        learn(sentence, index)
        predictions.wrote(sentence.text, words?.lastOrNull())
        ended = maxOf(ended, written.last().piece)
        written.clear()
        sentences = emptyList()
        if (offer) predict() else pager.reset()
        return snapshot(commit = sentence.text)
    }

    private fun learn(sentence: HandwritingDecoder.Sentence, index: Int) {
        if (!learning || !habits.learns) return journal.committed(sentence.text, null, learning)
        val chars = PhraseRules.codePoints(sentence.text)
        val first = sentences.firstOrNull()?.let { lastCharacter(it.text) }.orEmpty()
        val inks = readWritten()
        val each = chars.size == inks.size
        val from = if (each) 0 else inks.size - 1
        val picks = (from until inks.size).map { i ->
            val keys = KeyedRows.handwriting(inks[i].reading.texts)
            val last = i == inks.size - 1
            val text = if (last) lastCharacter(sentence.text) else chars[i]
            KeyedRows.Pick(keys, text, if (last && index > 0) Row.HOW_PICKED else Row.HOW_FIRST, if (last) first else text)
        }
        // each word at its dictionary-likeliest reading, so a non-word sentence put in twice becomes the user's word;
        // counted by text alone, never for pinyin's order
        val read = sentence.words
        val whole = read?.map { Word(it.text, it.spelled()) } ?: listOf(Word(sentence.text, ""))
        val origin = read?.let { origin(sentence.text, it) } ?: Row.DICTIONARY
        val rows = KeyedRows.of(Kinds.HANDWRITING, picks, whole, predictions.wordBefore(), origin)
        val deleted = journal.pending
        val again = retype(deleted, each, chars, rows)
        val commit = habits.record(rows + again?.match?.rows.orEmpty(), flags = predictions.learnFlags)
        retyped = again?.takeIf { it.skip > 0 && commit != null }?.let { Triple(deleted[it.match.typedAgain], it.skip, commit!!) }
        if (commit != null && each) {
            inked += commit to inks.map { it.reading.texts.toSet() }
            while (inked.size > CommitJournal.KEPT) inked.removeAt(0)
        }
        journal.committed(sentence.text, commit, learning, again?.match)
    }

    private fun retype(deleted: List<Commit>, each: Boolean, chars: List<String>, rows: List<Row>): Retype.Keyed? = when {
        deleted.isEmpty() -> null
        !each -> Retype.Keyed(Retype.Match(emptyList(), 0, goesOn = false), 0)
        else -> {
            val skip = retyped?.takeIf { r -> r.first === deleted.first() && deleted.none { it === r.third } }?.second ?: 0
            Retype.matchKeyed(deleted, skip, chars, rows, ::inCandidates, ::writtenBy)
        }
    }

    private fun writtenBy(deleted: Commit) = inked.firstOrNull { it.first === deleted }?.second?.size ?: 0

    private fun inCandidates(deleted: Commit, k: Int, text: String) = inked.firstOrNull { it.first === deleted }?.second?.getOrNull(k)?.contains(text) == true

    private fun origin(text: String, read: List<Entry>): Int {
        val entry = Entry(text, read.flatMap { it.syllables.asList() }.toIntArray())
        val known = user?.origin(entry) ?: Row.NONE
        return if (known == Row.NONE && words?.id(text)?.takeIf { it != NO_WORD } != null) Row.DICTIONARY else known
    }

    private fun pickPrediction(index: Int): Snapshot {
        val text = predictions.pick(index) ?: return snapshot(handled = predictions.offered.isNotEmpty())
        journal.committed(text, predictions.picked, learning)
        pager.reset()
        return snapshot(commit = text)
    }

    private fun pressed(index: Int, offer: Offer): Snapshot {
        if (written.isEmpty()) {
            if (predictions.press(index, offer)) pager.reset()
            return snapshot()
        }
        if (offer !in writtenOffers(index)) return snapshot()
        val text = lastCharacter(sentences[index].text)
        if (offer == Offer.FORGET) forgotten(text) else decoder?.alone(text)?.let { block?.invoke(it) }
        decoder?.forgetSearch()
        decode()
        return snapshot()
    }

    private fun takeBack(): Snapshot {
        ended = maxOf(ended, written.removeAt(written.size - 1).piece)
        decode()
        return snapshot()
    }

    private fun reset(): Snapshot {
        latest = maxOf(latest, offered.maxOfOrNull { it.piece } ?: NO_PIECE)
        offered.clear()
        ended = maxOf(ended, latest)
        written.clear()
        sentences = emptyList()
        predictions.unoffer()
        pager.reset()
        return snapshot()
    }

    private fun turn(to: Int): Snapshot {
        pager.turn(to, shown().size)
        return snapshot()
    }

    private fun snapshot(commit: String = "", handled: Boolean = true): Snapshot {
        val all = shown()
        val predicting = written.isEmpty() && predictions.offered.isNotEmpty()
        return Snapshot(
            commit = commit,
            preedit = if (written.isEmpty()) "" else sentences.firstOrNull()?.text.orEmpty(),
            candidates = pager.of(all),
            page = pager.page,
            hasPreviousPage = pager.hasPrevious,
            hasNextPage = pager.hasNext(all.size),
            handled = handled,
            predicting = predicting,
            actionable = predicting || written.isNotEmpty(),
            total = all.size,
            first = pager.from,
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 5

        const val NO_PIECE = -1
    }
}
