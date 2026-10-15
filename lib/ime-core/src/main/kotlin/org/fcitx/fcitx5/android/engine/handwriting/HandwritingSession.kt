/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.TextWords
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
 */
class HandwritingSession(
    private val decoder: HandwritingDecoder?,
    private val words: TextWords?,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val user: UserModel? = null,
) : Session {

    private class Offered(val reading: InkReading, val piece: Int)

    private class Written(val piece: Int, var reading: InkReading)

    private val offered = ArrayDeque<Offered>()
    private val written = ArrayList<Written>()

    // what the characters written read as: the best sentence first, then the best for each other last character
    private var sentences: List<String> = emptyList()

    // each sentence's words, as [user] learns them: see [HandwritingDecoder.Decoding.words]
    private var sentenceWords: List<List<Entry>?> = emptyList()
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

    override fun reads(c: Char): Boolean = false

    override fun apply(action: Action): Snapshot = when (action) {
        Action.Ink -> show()
        Action.Backspace -> if (written.isEmpty()) snapshot(handled = false) else takeBack()
        is Action.Select -> if (action.index in 0 until pageSize) pick(page * pageSize + action.index) else snapshot(handled = written.isNotEmpty())
        is Action.Pick -> pick(action.index)
        Action.NextPage -> turn(page + 1)
        Action.PreviousPage -> turn(page - 1)
        Action.CommitRaw -> if (written.isEmpty()) snapshot(handled = false) else pick(0)
        // a key the app takes: the best sentence goes in before it
        is Action.Key -> (if (written.isEmpty()) snapshot() else pick(0)).copy(handled = false)
        is Action.Context -> {
            before = action.before.takeLast(KEPT)
            reset()
        }
        Action.Reset -> {
            before = ""
            reset()
        }
        is Action.Cursor -> snapshot(handled = false)
        Action.Refine, is Action.Forget, is Action.Pin, is Action.Unpin, is Action.Block, is Action.Syllable, is Action.Letter -> snapshot()
    }

    override fun candidates(from: Int, count: Int): List<Choice> {
        val all = lastCharacters()
        return all.subList(minOf(from, all.size), minOf(from + count, all.size)).map { Choice(it) }
    }

    override fun offers(index: Int): Set<Offer> = emptySet()

    override fun passedOn() {
        before = ""
    }

    private fun show(): Snapshot {
        var changed = false
        while (offered.isNotEmpty()) changed = take(offered.removeFirst()) || changed
        if (changed) decode()
        return snapshot()
    }

    // what [o] does to the characters written: whether it changed them
    private fun take(o: Offered): Boolean {
        latest = maxOf(latest, o.piece)
        if (o.piece <= ended) return false
        val at = written.indexOfFirst { it.piece == o.piece }
        val empty = o.reading.texts.isEmpty()
        when {
            // a piece written, read again; read as nothing, taken out: the pad writes on in it
            at >= 0 -> if (empty) written.removeAt(at) else written[at].reading = o.reading
            // a later piece: another character, once there is something to read
            (written.isEmpty() || o.piece > written.last().piece) && !empty -> written += Written(o.piece, o.reading)
            // a later piece's first dot, too little to read; or one taken out already
            else -> return false
        }
        return true
    }

    private fun decode() {
        page = 0
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
        sentences = decoded.sentences
        sentenceWords = decoded.words
    }

    // what the last character may be, as the candidates show it
    private fun lastCharacters(): List<String> = sentences.map { lastCharacter(it) }

    private fun pick(index: Int): Snapshot {
        val text = sentences.getOrNull(index) ?: return snapshot(handled = written.isNotEmpty())
        if (index > 0 && learning) sentenceWords[index]?.let { user?.learn(null, it) }
        before = (before + text).takeLast(KEPT)
        ended = maxOf(ended, written.last().piece)
        written.clear()
        sentences = emptyList()
        sentenceWords = emptyList()
        page = 0
        return snapshot(commit = text)
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
        page = 0
        return snapshot()
    }

    private fun turn(to: Int): Snapshot {
        if (to < 0 || to * pageSize >= sentences.size) return snapshot()
        page = to
        return snapshot()
    }

    private fun snapshot(commit: String = "", handled: Boolean = true): Snapshot {
        val all = lastCharacters()
        val from = page * pageSize
        return Snapshot(
            commit = commit,
            preedit = sentences.firstOrNull().orEmpty(),
            candidates = all.subList(minOf(from, all.size), minOf(from + pageSize, all.size)),
            page = page,
            hasPreviousPage = page > 0,
            hasNextPage = from + pageSize < all.size,
            handled = handled,
            predicting = false,
            total = all.size,
            first = from,
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 5

        /** No piece: the pad's are numbered from 0. */
        const val NO_PIECE = -1

        // the text before the cursor kept: enough for the last two words, as TextWords reads them
        private const val KEPT = 3 * TextWords.MAX_WORD
    }
}
