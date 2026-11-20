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

/** What a [PinyinSession] writes to the learning record ([habits]) besides its model: the keys [pressed] for its input under [kind] ([multiTapKind] for 连按 letters, full pinyin for glides), after the word [lastWord] finds. */
internal class CommitRecorder(
    to: LearnTo,
    /** The nine keys' or a grouped keyboard's letters if the session types on them: their layout's kind. */
    letters: NineKeyLetters?,
    /** Where a word comes from ([UserModel.origin]). */
    private val user: UserModel?,
    /** In the dictionary or added by hand. */
    private val isWord: (String) -> Boolean,
    /** The session's segmenter, to match a retyped commit; null: none is. */
    private val segment: ((String) -> SyllableGraph)? = null,
    /** The model's word split (`TextWords.boundaries`), as 联想 uses: the 前文 is its last ([LastWord]). */
    split: ((String) -> IntArray)? = null,
    /** Added by hand: one word wherever [split] cuts it. */
    isAdded: (String) -> Boolean = { false },
) {
    val habits = to.habits
    private val layout = letters?.tag()
    // grouped keys apart from the nine keys' (qk:, not t9:); 注音九键's 连按 is under zy:, never t9m:
    private val grouped = layout != null && (letters?.isGrouped == true || Kinds.isZhuyin(to.kind))
    // grouped 双拼 spk:<scheme>:<tag>, the 14 keys qk:<tag>, zhuyin its own
    private val kind = when {
        layout == null || Kinds.isZhuyin(to.kind) -> to.kind
        !grouped -> Kinds.nineKeys(layout)
        Kinds.isShuangpin(to.kind) -> Kinds.shuangpinKeys(to.kind.substringAfter(':'), layout)
        else -> Kinds.keys(layout)
    }

    /** Where a bar-taken syllable is kept ([Kinds.takeLayout]); "" where the keys have no bar. */
    val takeLayout = layout?.let { Kinds.takeLayout(kind, it) }.orEmpty()
    private val multiTapKind = layout?.takeUnless { grouped }?.let(Kinds::nineKeysMultiTap)
    private val simplify = to.simplify
    private val lastWord = LastWord({ isWord(it) || habits.isWord(it) }, simplify, split) { isAdded(it) || habits.isWord(it) }
    val pressed = PressedKeys()

    /** The field as this session wrote it: what an app Backspace deletes and a commit retypes. */
    private val journal = FieldJournal({ habits }, simplify)

    // how a deleted commit is retyped: this session's kind by its segmenter, a glide's by full pinyin's; none for a
    // prediction or another kind, where the retyping run ends
    private fun matcherOf(row: Row): Retype.Matcher? {
        val segment = segment ?: return null
        return when (row.code.kind) {
            kind -> Retype.byReading(segment(row.code.keys))
            Kinds.FULL -> Retype.byReading(fullPinyin.segment(row.code.keys))
            else -> null
        }
    }

    private val fullPinyin by lazy(LazyThreadSafetyMode.NONE) { PinyinSegmenter() }

    // the commit being recorded and what it retyped: for the journal
    private var recorded: Commit? = null
    private var typedAgain: Retype.Match? = null

    /** Null for none: what a session over this one (a table's lookup) keeps in its journal. */
    var lastRecorded: Commit? = null
        private set

    /** See FieldJournal.settle. */
    fun settle() = journal.settle()

    /** See CommitJournal.backspace. */
    fun backspace() = journal.backspace()

    /** Something this session did not write went into the field. */
    fun unverify() = journal.unverify()

    /** A key went to the app: deletions stand, and what the key does is the app's. */
    fun passedOn() = journal.passedOn()

    /** See FieldJournal.told. */
    fun told(c: Action.Context, learning: Boolean) = journal.told(c, learning)

    /** Committed by a session over this one (a looked-up character): nothing to take back; kept only when [learning]. */
    fun followed(text: String, learning: Boolean) = journal.followed(text, learning)

    /** See CommitJournal.own. */
    fun ownContext(before: String) = journal.ownContext(before)

    /** The field cleared itself (Action.Context): no word before the next commit. */
    var cleared = false

    /** The text before the cursor as far as kept: the app's, then commits after it. */
    var text = ""

    /** What the field's commits are not learned for ([Commit.NO_PAIRS], [Commit.NO_PREDICT]), per the last [Action.Context]. */
    var learnFlags = 0

    /** The first candidate shown when a syllable was first taken: what an 'H' pick is weighed against. */
    var firstAtTake: String? = null

    val learns get() = habits.learns

    fun forget(text: String) = safely { habits.forget(text) }

    /** The whole phrase's count only, no word of that text. */
    fun forgetPhrase(text: String) = safely { habits.forgetPhrase(text) }

    /** [block]'s value or [otherwise]: a row-building fault is learning's, never the commit's (Habits.failed). */
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

    fun code(from: Int, to: Int, words: List<Word>, slips: Map<Int, Char> = emptyMap()): Code = when {
        // a glide's letters are no keys: its words as full pinyin, whatever the keyboard
        pressed.glided(from, to) -> Code(Kinds.FULL, words.joinToString("") { it.reading.replace(" ", "") })
        multiTapKind != null && pressed.multiTap(from, to) -> Code(multiTapKind, pressed.keys(from, to, slips))
        else -> Code(kind, pressed.keys(from, to, slips))
    }

    /** The app's text before the cursor, which the next commit follows; kept only when [learning]. */
    fun followText(text: String, cleared: Boolean, learning: Boolean) {
        this.cleared = cleared
        this.text = if (learning) recentTail(text) else ""
    }

    /** What the next commit follows. */
    fun committed(text: String, learning: Boolean) {
        cleared = false
        this.text = if (learning) recentTail(this.text + text) else ""
        // where nothing is learned there is nothing to take back, and no text to keep
        journal.committed(text, recorded, learning, typedAgain)
        lastRecorded = recorded
        recorded = null
        typedAgain = null
    }

    fun prev() = if (cleared) "" else lastWord.of(text)

    /** Simplified, as the record keeps text: what a whole phrase goes on from. */
    fun textBefore() = if (cleared) "" else simplify(text)

    fun commit(picks: List<CommitRows.Pick>, nineKeys: NineKeys?) = safely {
        val takes = if (layout == null) emptyList() else nineKeys?.takes(takeLayout).orEmpty()
        val prev = prev()
        // a slipped key is learned as the key meant, so the code stays as it reads
        val slips = picks.fold(emptyMap<Int, Char>()) { all, p -> if (p.slips.isEmpty()) all else all + p.slips }
        val rows = CommitRows.build(prev, picks, { from, to, words -> code(from, to, words, slips) }, ::origin)
        // commits deleted whole just before and retyped by this one: corrected to it
        val deleted = journal.pending
        val again = if (deleted.isEmpty()) null else Retype.match(deleted, prev, Retype.syllables(prev, picks), rows, ::matcherOf, ::origin)
        typedAgain = again
        recorded = habits.record(rows + again?.rows.orEmpty(), takes, learnFlags)
    }

    /** Notes the first candidate shown before a syllable is taken: what an 'H' pick is weighed against. */
    fun taking(nineKeys: NineKeys, candidates: List<Candidate>, rest: Int, aside: (Candidate) -> Boolean) {
        if (!nineKeys.anyTaken) firstAtTake = firstShown(candidates, rest, aside)?.text
    }

    /** Pins [text] first, or unpins, under the code [at] computes: computed here so its fault is learning's too. */
    fun pin(text: String, on: Boolean, at: () -> Pair<Code, List<Entry>?>) = safely {
        val (code, entries) = at()
        habits.pin(code, text, Words(entries?.let(::words).orEmpty()).reading, on)
    }

    /** A 联想 pick after [after]; [first] the one shown first, [continued] a whole phrase's rest: see [PredictionOrder]. */
    fun prediction(text: String, after: String, first: String, continued: String = "") = safely {
        // after nothing, no whole phrase either: PredictionOrder offers a phrase's rest only after its word
        if (after.isEmpty()) return@safely
        val row = PredictionOrder.row(text, after, first)
        // journaled as a commit: deleted whole at once, the pick is taken back; a phrase's rest counts as a whole use
        recorded = if (continued.isEmpty()) habits.record(listOf(row), flags = learnFlags) else habits.recordContinued(listOf(row), learnFlags, continued)
    }

    /** A glide alternative committed though the session held it back: as a pick from [first]. */
    fun heldBack(word: Word, first: String) = safely {
        val words = Words(listOf(word))
        val code = Code(Kinds.FULL, word.reading.replace(" ", ""))
        val corrected = if (first != word.text) Row.CORRECTED else 0
        val record = habits.record(listOf(Row(Row.PICK or Row.WHOLE or corrected, code, prev(), words, 'B', origin(words), first)), flags = learnFlags)
        // committed by the glide, which the session hears as context next: journaled here as a commit
        journal.own(word.text, record)
    }

    /** [c] picked from [candidates] (from input place [from], [rest] left) as learning takes it: its words as [entries], where they end, what it was weighed against, and the first sentence shown. */
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
        slips: Map<Int, Char> = emptyMap(),
    ): CommitRows.Pick? = safely(null) {
        val words = entries?.let(::words)
        val ends = if (words != null) IntArray(c.ends.size) { from + c.ends[it] } else intArrayOf(from + c.end)
        val how = when {
            taken -> 'H'
            c.end < rest -> 'C'
            // the first of the words shown: a pinned phrase or emoji before it is none
            c === firstShown(candidates, rest, aside) -> 'A'
            else -> 'B'
        }
        val reference = if (taken && firstPiece) firstAtTake.orEmpty().take(c.text.length) else firstShown(candidates, c.end, aside)?.text.orEmpty()
        val shown = firstShown(candidates, rest, aside)?.takeIf { s -> NO_WORD !in s.words }
            ?.let { s -> CommitRows.Shown(from, s.words.map(text), IntArray(s.ends.size) { from + s.ends[it] }) }
        CommitRows.Pick(from, words ?: listOf(Word(c.text, "")), ends, how, reference, shown, own, slips)
    }

    /** Dictionary or user dictionary, added by hand, or neither. */
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
        /** The first of [candidates] ending [end] places on, not one [aside] (a phrase, an emoji). */
        fun firstShown(candidates: List<Candidate>, end: Int, aside: (Candidate) -> Boolean) = candidates.firstOrNull { it.end == end && !aside(it) }

        /** Syllables space-separated. */
        fun words(entries: List<Entry>) = entries.map { e -> Word(e.text, e.spelled()) }
    }
}

internal fun withoutLast(text: String) = if (text.isEmpty()) text else text.substring(0, text.offsetByCodePoints(text.length, -1))
