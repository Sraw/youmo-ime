/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.CommitRows
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LastWord
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

/**
 * What a [PinyinSession] writes to the learning record ([habits], impl/design-1a.md §3), beside
 * what its user model learns: the keys [pressed] for its input, filed under [kind] ([multiTapKind]
 * where the nine keys' 连按 chose a letter, full pinyin where a glide typed them), after the word
 * [lastWord] finds before them. In 1a nothing ranks by it.
 */
internal class CommitRecorder(
    to: LearnTo,
    /** The nine keys' letters, if the session's keys are theirs: their layout's kind. */
    letters: NineKeyLetters?,
    /** The user model, as `UserLexicon.user()` builds it: where a word comes from ([UserModel.origin]). */
    private val user: UserModel?,
    /** A text the dictionary has, or the user added by hand. */
    private val isWord: (String) -> Boolean,
    /** The session's keys read as syllables (its segmenter): what a commit typed again is matched by; null, none is. */
    private val segment: ((String) -> SyllableGraph)? = null,
    /** How the model reads a text into words (`TextWords.boundaries`), as 联想 does: the 前文 is its last ([LastWord]). */
    split: ((String) -> IntArray)? = null,
    /** A text the user added by hand, one word wherever [split] puts its pieces. */
    isAdded: (String) -> Boolean = { false },
) {
    val habits = to.habits
    private val layout = letters?.tag()
    private val kind = layout?.let(Kinds::nineKeys) ?: to.kind
    private val multiTapKind = layout?.let(Kinds::nineKeysMultiTap)
    private val simplify = to.simplify
    private val lastWord = LastWord({ isWord(it) || habits.isWord(it) }, simplify, split) { isAdded(it) || habits.isWord(it) }
    val pressed = PressedKeys()

    /** The field as this session wrote it: what a Backspace the app took deletes, and a commit types again (U-03). */
    private val journal = FieldJournal({ habits }, simplify)

    // how a deleted commit is typed again: by its keys read as syllables, this session's kind by its
    // segmenter, a glide's (full pinyin, its words' pinyin) by full pinyin's; none of a prediction or
    // another kind (the nine keys' letters pressed in turn, a table's codes): the run of retyping ends there
    private fun matcherOf(row: Row): Retype.Matcher? {
        val segment = segment ?: return null
        return when (row.code.kind) {
            kind -> Retype.byReading(segment(row.code.keys))
            Kinds.FULL -> Retype.byReading(fullPinyin.segment(row.code.keys))
            else -> null
        }
    }

    private val fullPinyin by lazy(LazyThreadSafetyMode.NONE) { PinyinSegmenter() }

    // the record of the commit being committed, and what it typed again: for the journal
    private var recorded: Commit? = null
    private var typedAgain: Retype.Match? = null

    /** The record of the last commit, null for none: what a session over this one (a table's lookup) keeps in its journal. */
    var lastRecorded: Commit? = null
        private set

    /** What the journal deleted whole, taken back now: see FieldJournal.settle. */
    fun settle() = journal.settle()

    /** A Backspace the app took, nothing typed: see CommitJournal.backspace. */
    fun backspace() = journal.backspace()

    /** Something went into the field this session did not write. */
    fun unverify() = journal.unverify()

    /** A key went to the app: what was deleted stands, and what the key does there is the app's (U-03). */
    fun passedOn() = journal.passedOn()

    /** [c] told the session: see FieldJournal.told. */
    fun told(c: Action.Context, learning: Boolean) = journal.told(c, learning)

    /** [text] committed by a session over this one (a character looked up): nothing of it to take back; nothing kept but [learning]. */
    fun followed(text: String, learning: Boolean) = journal.followed(text, learning)

    /** A context the session's engine gave itself, [before] the cursor: see CommitJournal.own. */
    fun ownContext(before: String) = journal.ownContext(before)

    /** The editor's text is a field's that cleared itself (Action.Context): no word before the next commit. */
    var cleared = false

    /** The text before the cursor, as far as kept: the app's, then what was committed after it. */
    var text = ""

    /** What the field's commits are not learned for ([Commit.NO_PAIRS], [Commit.NO_PREDICT]): as the last [Action.Context] said. */
    var learnFlags = 0

    /** The first candidate shown when a syllable was first taken: what an 'H' pick is weighed against. */
    var firstAtTake: String? = null

    val learns get() = habits.learns

    /** [text] forgotten everywhere (U-84). */
    fun forget(text: String) = safely { habits.forget(text) }

    /** [block]'s value, or [otherwise]: a fault of building the rows is learning's, never the commit's (see Habits.failed). */
    inline fun <T> safely(otherwise: T, block: () -> T): T {
        if (!learns) return otherwise
        return try {
            block()
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            habits.failed(e)
            otherwise
        }
    }

    private inline fun safely(block: () -> Unit) = safely(Unit, block)

    fun code(from: Int, to: Int, words: List<Word>): Code = when {
        // a glide's letters are no keys: its words as full pinyin, whatever the keyboard
        pressed.glided(from, to) -> Code(Kinds.FULL, words.joinToString("") { it.reading.replace(" ", "") })
        multiTapKind != null && pressed.multiTap(from, to) -> Code(multiTapKind, pressed.keys(from, to))
        else -> Code(kind, pressed.keys(from, to))
    }

    /** The app's [text] before the cursor, as what the next commit follows: [cleared] (Action.Context); none kept but [learning]. */
    fun followText(text: String, cleared: Boolean, learning: Boolean) {
        this.cleared = cleared
        this.text = if (learning) recentTail(text) else ""
    }

    /** [text] committed: what the next commit follows. */
    fun committed(text: String, learning: Boolean) {
        cleared = false
        this.text = if (learning) recentTail(this.text + text) else ""
        // where nothing is learned there is nothing to take back, and no text to keep
        journal.committed(text, recorded, learning, typedAgain)
        lastRecorded = recorded
        recorded = null
        typedAgain = null
    }

    /** The word before what is committed now. */
    fun prev() = if (cleared) "" else lastWord.of(text)

    /** A commit of [picks], with the syllables taken of [nineKeys]. */
    fun commit(picks: List<CommitRows.Pick>, nineKeys: NineKeys?) = safely {
        val takes = if (layout == null) emptyList() else nineKeys?.takes(layout).orEmpty()
        val prev = prev()
        val rows = CommitRows.build(prev, picks, ::code, ::origin)
        // commits deleted whole just before, typed again by this one: corrected to there (U-03)
        val deleted = journal.pending
        val again = if (deleted.isEmpty()) null else Retype.match(deleted, prev, Retype.syllables(prev, picks), rows, ::matcherOf, ::origin)
        typedAgain = again
        recorded = habits.record(rows + again?.rows.orEmpty(), takes, learnFlags)
    }

    /** A syllable about to be taken of [nineKeys]: the first shown, before the first, what an 'H' pick is weighed against. */
    fun taking(nineKeys: NineKeys, candidates: List<Candidate>, rest: Int, aside: (Candidate) -> Boolean) {
        if (!nineKeys.anyTaken) firstAtTake = firstShown(candidates, rest, aside)?.text
    }

    /**
     * [text] pinned first (U-83), or unpinned, under the code [at] works out, read as the entries
     * it gives: worked out here, so a fault of it is learning's too (review-1a-r2 NIT 5).
     */
    fun pin(text: String, on: Boolean, at: () -> Pair<Code, List<Entry>?>) = safely {
        val (code, entries) = at()
        habits.pin(code, text, Words(entries?.let(::words).orEmpty()).reading, on)
    }

    /** A prediction (联想) of [text] picked [after] the word it follows, [first] the one shown first: see [PredictionOrder]. */
    fun prediction(text: String, after: String, first: String) = safely {
        if (after.isEmpty()) return@safely
        val row = PredictionOrder.row(text, after, first)
        // in the journal as a commit is: deleted whole at once, the pick is taken back (U-03)
        recorded = habits.record(listOf(row), flags = learnFlags)
    }

    /** A word of a glide's other readings committed though the session held it back: as a pick of it from [first]. */
    fun heldBack(word: Word, first: String) = safely {
        val words = Words(listOf(word))
        val code = Code(Kinds.FULL, word.reading.replace(" ", ""))
        val corrected = if (first != word.text) Row.CORRECTED else 0
        val record = habits.record(listOf(Row(Row.PICK or Row.WHOLE or corrected, code, prev(), words, 'B', origin(words), first)), flags = learnFlags)
        // committed by the glide, the session told it as context next: written here as a commit (S10)
        journal.own(word.text, record)
    }

    /**
     * [c], picked of [candidates] (from input place [from], [rest] places left), as
     * learning takes it (impl/design-1a.md §3): its words, read as [entries], and where they end;
     * what it was weighed against, the first shown ending where it ends (or, a syllable [taken],
     * the first shown before); the first sentence shown, its words' texts by [text].
     */
    fun pick(
        c: Candidate,
        from: Int,
        rest: Int,
        entries: List<Entry>?,
        candidates: List<Candidate>,
        aside: (Candidate) -> Boolean,
        text: (Int) -> String,
        taken: Boolean,
        firstPiece: Boolean,
        own: Boolean = false,
    ): CommitRows.Pick? = safely(null) {
        val words = entries?.let(::words)
        val ends = if (words != null) IntArray(c.ends.size) { from + c.ends[it] } else intArrayOf(from + c.end)
        val how = when {
            taken -> 'H'
            c.end < rest -> 'C'
            // the first of the words shown: a pinned phrase or an emoji before it is none
            c === firstShown(candidates, rest, aside) -> 'A'
            else -> 'B'
        }
        val reference = if (taken && firstPiece) firstAtTake.orEmpty().take(c.text.length) else firstShown(candidates, c.end, aside)?.text.orEmpty()
        val shown = firstShown(candidates, rest, aside)?.takeIf { s -> NO_WORD !in s.words }
            ?.let { s -> CommitRows.Shown(from, s.words.map(text), IntArray(s.ends.size) { from + s.ends[it] }) }
        CommitRows.Pick(from, words ?: listOf(Word(c.text, "")), ends, how, reference, shown, own)
    }

    /** Where [words] come from: the dictionary or a user dictionary, added by hand, neither. */
    private fun origin(words: Words): Int {
        val syllables = words.reading.split(' ').filter { it.isNotEmpty() }.map(Syllables::id)
        val known = if (syllables.isEmpty() || syllables.any { it < 0 }) null else user?.origin(Entry(words.text, syllables.toIntArray()))
        return known ?: if (isWord(words.text)) Row.DICTIONARY else Row.NONE
    }

    fun clear() {
        pressed.clear()
        firstAtTake = null
    }

    companion object {
        /** The first of [candidates] that ends [end] places on: of the words, not one [aside] (a phrase, an emoji). */
        fun firstShown(candidates: List<Candidate>, end: Int, aside: (Candidate) -> Boolean) = candidates.firstOrNull { it.end == end && !aside(it) }

        /** [entries] as learning has words: their syllables spelt, apart by spaces. */
        fun words(entries: List<Entry>) = entries.map { e -> Word(e.text, e.syllables.joinToString(" ") { Syllables.spelling(it) }) }
    }
}

/** [text] without its last character. */
internal fun withoutLast(text: String) = if (text.isEmpty()) text else text.substring(0, text.offsetByCodePoints(text.length, -1))
