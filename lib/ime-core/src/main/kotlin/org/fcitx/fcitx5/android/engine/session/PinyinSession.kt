/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.Misreadings
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.CommitRows
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.HabitRanker.Tier
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases.Phrase
import org.fcitx.fcitx5.android.engine.phrase.Chaizi
import org.fcitx.fcitx5.android.engine.phrase.AsideCandidates
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.KeyedSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.user.LearningUse
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import java.util.Calendar

/**
 * Pinyin typed and picked piece by piece. The candidates are the readings of all the input left,
 * then the words it may start with; picking one that reads only the start keeps it and reads the
 * rest after it, with it as the language model's context, until the input is all read and the
 * pieces are committed. Then the words that tend to follow are offered, until a key is typed.
 *
 * Backspace deletes the last key typed; when that leaves nothing after the last piece picked, the
 * piece is dropped, so its keys are read again.
 *
 * The input keeps a cursor, at its end till moved ([Action.Cursor], a tap on the preedit;
 * [Action.Step], an arrow): keys go in there and backspace deletes before it, the input read
 * again whole, as if typed so. A piece picked whose keys that changes is dropped, as backspace
 * drops one; the cursor goes no further back than the pieces' end. On the nine keys a syllable
 * taken puts the cursor past it if it was in or after its digits, and is given back by backspace
 * at the end alone.
 *
 * The words committed stay the context of what is typed next, as they would be read together,
 * until [Action.Reset] or text committed as typed; [Action.Context] makes the words the app's text
 * ends with the context, as if committed (see [TextWords]).
 *
 * What is committed is learned into the learning record ([learnTo], impl/design-1a.md), and the
 * candidates are put in the order the user's habits there say ([HabitOrder], impl/design-1b.md):
 * the word picked under the same keys after the same word first, the words used there next, the
 * user's own words after them. A text put together twice becomes the user's word, theirs under
 * every code it reads. A prediction picked is learned too, after what came before it, as its
 * likeliest reading: 中文 picked after 用 comes up after 用 again. Text kept as typed is not learned.
 *
 * With a [reranker], the best readings of the input are weighed again by how they read as a
 * sentence after the text committed before, by the same context, or after the text the host
 * says is before the cursor ([Action.Context]). With a [refiner] too, a better and slower one,
 * the readings are weighed again by it while the user pauses ([Action.Refine]): the [reranker]
 * decides as each key is typed, within a few milliseconds, the [refiner] then in slices of
 * [REFINE_BUDGET] between them, till the next key.
 *
 * @param spell show each syllable spelt out, not as typed: for 双拼, whose keys say little
 *
 * On the nine keys (a [segmenter] that does not [type letters][Segmenter.typesLetters]) the
 * input is digits, shown as the pinyin of the best reading. What the first digits not taken yet
 * may be is offered ([Snapshot.syllables]); one taken ([Action.Syllable]) goes into the input as
 * its letters and a separator, read as that syllable alone, and the offer moves on to the digits
 * after it. Backspace with no key typed since a syllable was taken gives its digits back, as 搜狗
 * does: the last taken first; with keys typed since, it deletes those first.
 */
class PinyinSession(
    private val data: PinyinData,
    private val segmenter: Segmenter,
    penalties: Penalties = Penalties(),
    private val spell: Boolean = false,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val user: UserModel? = null,
    /** Whether words that may follow are offered after a commit. */
    private val prediction: Boolean = true,
    /** The prior per layer the scores go through; no longer learned (impl/design-1b.md §4). */
    prior: LayerPrior? = null,
    /** Offered where their order says when what is left of the input is their key, a place down where a pin leads. */
    private val phraseBook: PhraseBook = PhraseBook(),
    /** Whether [phraseBook]'s built-in phrases (the date, the time, the weekday) are offered, the setting; never where [spell]. */
    private val builtInPhrases: Boolean = true,
    /** The emoji a word stands for ([EmojiCandidates]); none, the setting off. */
    private val emoji: (String) -> List<String> = { emptyList() },
    /**
     * The characters whose parts' pinyin [Chaizi.KEY] and then the whole input is (Chaizi), each
     * with its reading; none for most. No pinyin starts with u, the stroke lookup's key too.
     */
    private val chaizi: (String) -> List<Pair<String, String>> = { emptyList() },
    /** The time a dynamic phrase is filled in with. */
    private val now: () -> Calendar = { Calendar.getInstance() },
    private val refiner: SentenceRefiner? = null,
    /** Keeps a word the user blocked ([Action.Block]), blocked in [user] too; none offered without. */
    private val block: ((Entry) -> Unit)? = null,
    /** [Predictor.charReadingsOf] [data], asked for when first needed: one map for the sessions over [data] to share. */
    charReadings: () -> Map<Int, IntArray> = { Predictor.charReadingsOf(data.dictionary, data.vocabulary) },
    /**
     * Where the decoder keeps the longer word each word at a node of [data]'s dictionary likeliest
     * starts, worked out as a syllable still being typed reaches the node (see [PinyinDecoder]): an
     * entry per node, asked for when first needed; one array for the sessions that score alike,
     * over the same [user] and [prior], to share.
     */
    extensions: () -> Array<IntArray?> = { arrayOfNulls<IntArray>(data.dictionary.nodeCount) },
    /**
     * The words the user added by hand (UserLexicon.addWord), in their list's order: theirs, as a word
     * they formed is, so none is held back as coarse (see CoarseWords), and ranked after those. Read
     * again as the user's lists change ([UserModel.listings]); the coarse ones as the session is made.
     */
    private val addedWords: () -> Set<String> = { emptySet() },
    /** How what is learned reaches the scores: [LearningUse.DEFAULT] but to measure the channels' worth (ime-eval). */
    use: LearningUse = LearningUse.DEFAULT,
    /** The learning record a commit is written to (impl/design-1a.md), and how; the candidates are ranked by it. */
    learnTo: LearnTo = LearnTo(),
    // last, so a test's picker can follow as a lambda
    private val reranker: SentencePicker? = null,
) : Session {
    init {
        require(pageSize >= 1) { "page size $pageSize" }
    }

    /** Picked text, its words, where it ends in the input, how its words read (null if some is no word), and as the learning record takes it. */
    private class Piece(val text: String, val words: IntArray, val end: Int, val entries: List<Entry>?, val learned: CommitRows.Pick?)

    /**
     * What the decoder read of the input after the pieces: its sentences as placed, the reranker's
     * or refiner's pick first, then its words, [more] of which may be read as a list is shown that
     * far; [first], its first before the reranker and phrases moved it; [levels], how the habits rank it.
     */
    private data class Decoded(
        val graph: SyllableGraph,
        val sentences: List<Candidate>,
        val words: List<Candidate>,
        val more: Boolean,
        val first: Candidate?,
        val levels: List<HabitOrder.Level> = emptyList(),
    )

    /**
     * The input after the pieces as read last, or the predictions after a commit, which read no
     * input: replaced whole as it is read again or cleared, so nothing of one reading is left
     * with the next.
     */
    private class Read(
        val candidates: List<Candidate> = emptyList(),
        val decoded: Decoded? = null,
        val time: Calendar? = null, // when the phrases were filled in, if the input has any: the same as more words are read
        val placed: Map<String, Phrase> = emptyMap(), // the phrases among the candidates, by text
        val preedit: String = "",
        val restShown: String = "", // the end of the preedit: what is left of the input, as shown
        val places: PreeditPlaces = PreeditPlaces.NONE,
        val unsettled: Boolean = false, // a coarse candidate waits past a first page the list does not fill yet
        // the emoji (EmojiCandidates) and the characters by their parts (Chaizi) among the candidates,
        // by text, each with its hint: text, no word, and no reading of the first candidate's
        val aside: Map<String, String> = emptyMap(),
        val ranks: Map<Candidate, HabitRanker.Ranked<Candidate>> = emptyMap(), // how the habits ranked those they did
    ) {
        val moreWords get() = decoded?.more == true
    }

    // a few hundred lookups: made with each session, as the data's index is
    private val coarse = CoarseWords.of({ data.wordIndex.find(it) }, CoarseWords.LIST - addedWords())
    private val coarseCandidates = CoarseCandidates(coarse, data, user)

    private val decoder = PinyinDecoder(
        data.dictionary, data.vocabulary, use.scorer(data.model, user, prior), penalties,
        user = user, extensions = extensions, wholeFirst = segmenter.typesLetters, coarse = coarse, asTyped = ::asTyped,
        whole = use.whole(data.model, user, prior), uncounted = use.uncounted(data.model, user, prior),
    )
    private val predictor = Predictor(data.model, data.vocabulary, data.dictionary, user, coarse = coarse, charReadings = charReadings)
    private val predictionOrder = PredictionOrder(predictor) { text -> user?.idsOfText(text)?.firstOrNull() ?: data.wordIndex.find(text) }
    private var predictedAfter = "" // the word the predictions offered follow: their code
    private val textWords by lazy { TextWords(data.model, data.wordIndex) }

    private val input = StringBuilder()
    private var cursor = 0 // where in the input the next key goes
    private var cursorMoved = false // by the last action, a tap or an arrow
    private val pieces = ArrayList<Piece>()
    private var context = IntArray(0) // the words committed last, at most two
    private var lastEntry: Entry? = null // the last of them, if known how it reads
    private var recent = "" // the text they end, for the reranker
    // whether a pick may learn a pair with the context's last word, the app's text: not where it is a
    // field's that cleared itself, for scoring only (PLAN §2.1), nor what was committed here (see commit)
    private var contextLearnable = true

    private var lastRead = Read()
    private val candidates get() = lastRead.candidates
    private var unrefined: List<Candidate>? = null // the decoder's sentences, till the refiner picks one
    private var refined = 0 // slices of refining spent on them
    // the keys of letter groups, if the segmenter's are (the nine keys, the 14 keys, 双拼's 18 and nine keys)
    private val keyed = segmenter as? KeyedSegmenter
    private val recorder = CommitRecorder(
        learnTo, keyed?.letters, user, { habitOrder.isAdded(it) || data.wordIndex.find(it) != NO_WORD }, segmenter::segment,
        split = { textWords.boundaries(it) }, isAdded = { habitOrder.isAdded(it) },
    )
    private val nineKeys = keyed?.let { k ->
        NineKeys(data, k.letters, recorder.pressed, k::isKey, k::initialsOf) { digits -> if (learning && recorder.learns) recorder.habits.bar(recorder.takeLayout, digits) else emptyMap() }
    }
    private val habitOrder: HabitOrder = HabitOrder(data, user, recorder, decoder, addedWords)
    private var page = 0
    private var predicting = false

    override var learning = true

    override fun apply(action: Action): Snapshot {
        // what a Backspace deleted whole taken back, now that what came next is not a context saying
        // otherwise (U-03). A Reset too: the host posts one before a cursor report's context, which then
        // comes too late to contradict, but only where something was shown, never after a bare Backspace
        if (action !is Action.Context) recorder.settle()
        val moves = action is Action.Cursor || action is Action.Step
        // what the refiner weighs is the input as last read, on the first page; a cursor moved reads nothing
        if (action != Action.Refine && !moves) unrefined = null
        // the keyboard counts presses of a key again unaware of a tap on the preedit: the next is a press of its own,
        // pressed again the key, any of its letters, as after a pick the keyboard did not see; not a letter typed as itself
        val afterMove = cursorMoved
        cursorMoved = moves || (afterMove && action == Action.Refine)
        return if (afterMove && action is Action.Letter) letter(action.letter, action.again, runFrom = cursor, glided = action.glided) else act(action)
    }

    private fun act(action: Action): Snapshot = when (action) {
        Action.Refine -> refine()
        Action.Ink, Action.Glide, is Action.NineKey -> snapshot()
        is Action.Key -> type(action.char, action.glided, action.at)
        is Action.Letter -> letter(action.letter, action.again, glided = action.glided)
        Action.Backspace -> backspace()
        // nothing to pick past the page: the key (space, a digit) is the app's
        is Action.Select -> if (action.index in 0 until pageSize) pick(page * pageSize + action.index) else snapshot(handled = candidates.isNotEmpty())
        is Action.Pick -> pick(action.index)
        is Action.Syllable -> takeSyllable(action.index, action.of)
        is Action.Forget, is Action.Pin, is Action.Unpin, is Action.Block -> pressed(action)
        Action.NextPage -> turn(page + 1)
        Action.PreviousPage -> turn(page - 1)
        Action.CommitRaw -> commitRaw()
        Action.Reset -> {
            // the input method left, the field went, a panel or another input method writes: what is
            // in the field is no longer known till a context says (U-03; review-design-1c M1)
            recorder.unverify()
            dropContext()
            clear()
            snapshot()
        }
        is Action.Context -> {
            recorder.told(action, learning)
            takeContext(action.before, action.cleared).also { action.learnFlags?.let { recorder.learnFlags = it } }
        }
        is Action.Cursor -> moved { lastRead.places.inputAt(action.at, readFrom()) }
        is Action.Step -> moved { action.applyTo(cursor, input.length) }
    }

    /**
     * Takes [text], committed by a session over this one (a character looked up by its strokes),
     * as what the input to come follows: the app's text is what this committed, then [text].
     */
    fun follow(text: String): Snapshot {
        // written by the session over this one: none of this one's commits to take back
        recorder.followed(text, learning)
        return takeText(recent + text, learnText = recorder.text + text)
    }

    /**
     * [keys] typed as [Action.Key]s one after another would be, the input read once: keys a
     * session over this one hands it together (a stroke lookup a letter ended). On the nine keys
     * a key at a time: each key read may change the syllables offered, each change their next id.
     * Keys other than letters a key at a time too: whether 双拼's `;` is read depends on the keys before it.
     */
    fun typeAll(keys: String): Snapshot {
        if (keys.isEmpty() || nineKeys != null || !keys.all { it in 'a'..'z' }) {
            return keys.fold(snapshot()) { before, c -> apply(Action.Key(c)).let { it.copy(commit = before.commit + it.commit) } }
        }
        unrefined = null
        insert(keys)
        read()
        return snapshot()
    }

    /** See [Action.Context]. */
    private fun takeContext(before: String, cleared: Boolean): Snapshot {
        // the text still ends with what was committed here: the cursor did not go anywhere
        // (an editor reporting it where the service did not expect it), and the words the
        // user picked say more than the model's reading of the text. The host reads less than
        // is kept, so its text may be the end of it, long enough not to be so by chance
        val same = recent.isNotEmpty() && (before.endsWith(recent) || before.length >= OVERLAP && recent.endsWith(before))
        // a field cleared (a chat's message sent) ends the message as it is: no word before what comes next (USER-DECISIONS 2026-10-20)
        if (!cleared && learning && same) {
            clear()
            return snapshot()
        }
        return takeText(before, cleared)
    }

    /**
     * [before], the app's text, as what the input to come follows, in place of what was committed;
     * [cleared] (see [Action.Context]): for scoring only, no word the next pick learns a pair with.
     */
    private fun takeText(before: String, cleared: Boolean = false, learnText: String = before): Snapshot {
        dropContext()
        contextLearnable = !cleared
        // all of it, a word the model lacks too, for the word before what is learned
        recorder.followText(learnText, cleared, learning)
        clear()
        // what the app has is kept no longer than the input, and not at all where the user
        // learns nothing, as what they commit; text ending in no word (a space, a letter) is
        // no context for the reranker either, as after text committed as typed
        if (learning) {
            context = textWords.lastTwo(before)
            if (context.isNotEmpty()) recent = recentTail(before)
        }
        return snapshot()
    }

    private fun type(c: Char, glided: Boolean = false, at: Float = Float.NaN): Snapshot {
        if (!reads(c) || !segmenter.readsAfter(input.substring(readFrom(), cursor), c)) return leave()
        // on the nine keys 1 is 分词
        insert((if (c == NINE_KEYS_SEPARATOR && nineKeys?.oneSeparates == true) SyllableGraph.SEPARATOR else c).toString(), glided, at)
        read()
        return snapshot()
    }

    // [keys] typed at the cursor, never before the pieces' end (read and moved keep it there)
    private fun insert(keys: String, glided: Boolean = false, at: Float = Float.NaN) {
        recorder.pressed.insert(cursor, keys, glided, at, input = input, letters = keyed?.letters)
        input.insert(cursor, keys)
        cursor += keys.length
    }

    /** The cursor put where [to] says, if the input keeps one; nothing read again. */
    private fun moved(to: () -> Int): Snapshot {
        if (input.isEmpty() || predicting) return snapshot(handled = false)
        cursor = to().coerceIn(readFrom(), input.length)
        return snapshot()
    }

    /** See [Action.Letter]: on the nine keys alone, the others type letters as they are; a press again goes on no run before [runFrom]. */
    private fun letter(c: Char, again: Boolean, runFrom: Int = readFrom(), glided: Boolean = false): Snapshot =
        cursorAfter(nineKeys?.letter(input, c, again, runFrom, cursor, glided) ?: -1)

    private fun backspace(): Snapshot {
        if (input.isEmpty()) {
            // the app deletes, a prediction shown goes away, and what was committed here is context
            // only as far as it is left: 你 committed and erased kept 你脾气 before 日期 for 7474
            // recent is empty where nothing is learned, but commit set the context all the same
            // scoring's context as it always was; the learning record's text back a character as well
            val learnText = withoutLast(recorder.text)
            recorder.backspace()
            if (recent.isEmpty()) { dropContext(); clear(); recorder.text = learnText } else takeText(withoutLast(recent), recorder.cleared, learnText)
            return snapshot(handled = false)
        }
        // the syllable taken last, given back as its digits; the cursor in the middle deletes a key
        val given = if (cursor == input.length) nineKeys?.giveBack(input) ?: -1 else -1
        if (given >= 0) {
            while (pieces.isNotEmpty() && pieces.last().end > given) pieces.removeAt(pieces.size - 1)
            cursor = input.length
        } else {
            // at the start of the input: nothing before it is the session's to delete
            if (cursor == 0) return snapshot()
            recorder.pressed.delete(cursor - 1, input, keyed?.letters)
            input.deleteCharAt(--cursor)
            while (pieces.isNotEmpty() && (pieces.last().end > cursor || pieces.last().end >= input.length)) pieces.removeAt(pieces.size - 1)
        }
        read()
        return snapshot()
    }

    /** Takes the syllable at [index] of those offered: see the class. */
    private fun takeSyllable(index: Int, of: Int): Snapshot {
        val keys = nineKeys?.takeUnless { predicting } ?: return cursorAfter(-1)
        recorder.taking(keys, candidates, input.length - readFrom(), shownAside)
        return cursorAfter(keys.take(input, index, of, cursor))
    }

    // the input read again, the cursor at [at], if the edit made one; else -1
    private fun cursorAfter(at: Int): Snapshot {
        if (at >= 0) {
            cursor = at
            read()
        }
        return snapshot()
    }

    override fun reads(c: Char): Boolean =
        segmenter.reads(c) || (c == SyllableGraph.SEPARATOR || c == NINE_KEYS_SEPARATOR && nineKeys?.oneSeparates == true) && input.isNotEmpty()

    override fun candidates(from: Int, count: Int): List<Choice> {
        readUntil(from + count)
        return candidates.subList(minOf(from, candidates.size), minOf(from + count, candidates.size)).map { Choice(it.text, hint(it)) }
    }

    /**
     * Reads the decoder's words until there are [count] candidates, or no more: it gives a few
     * first, and those past them only as a list is shown, or turned, that far.
     */
    private fun readUntil(count: Int) {
        while (lastRead.moreWords && candidates.size < count) {
            val read = lastRead
            val decoded = read.decoded ?: return
            val (prev2, prev) = TextWords.previous(context + pieces.flatMap { it.words.asList() })
            val asked = maxOf(decoded.words.size * 2, decoded.words.size + count - candidates.size)
            // the same input in the same context: the decoder reads on from the words it ranked last, if it was this
            val words = decoder.words(decoded.graph, prev2, prev, asked)
            place(decoded.copy(words = words, more = words.size >= asked), read.time)
        }
    }

    /**
     * A word often read wrongly ([PinyinData.misreadings]) typed by a misreading: the reading,
     * with its tones (般若 typed banruo: bō rě). Not for 简拼 or a syllable half typed, which
     * are no misreading; nor for 双拼, whose keys are no reading to compare.
     */
    private fun hint(c: Candidate): String {
        if (spell || predicting) return ""
        lastRead.aside[c.text]?.let { return it }
        val from = readFrom()
        return misreading(data, c.text, input.substring(from, minOf(input.length, from + c.end)).filter { it != SyllableGraph.SEPARATOR })
    }

    private fun pick(index: Int): Snapshot {
        val c = candidates.getOrNull(index) ?: return snapshot(handled = candidates.isNotEmpty())
        if (predicting) {
            lastEntry = learnPrediction(c)
            return commit(c.text, c.words)
        }
        pieces += Piece(c.text, c.words, readFrom() + c.end, if (user == null) null else entries(c), toLearn(c))
        if (pieces.last().end < input.length) {
            read()
            return snapshot()
        }
        val text = pieces.joinToString("") { it.text }
        record()
        // the word the next commit's context goes on from, for 联想 after a word the model lacks
        lastEntry = if (learning) pieces.last().entries?.lastOrNull() else null
        return commit(text, pieces.flatMap { it.words.asList() }.toIntArray())
    }

    /** What a long press on a candidate offered, and the user chose: see [offers]. */
    private fun pressed(action: Action) = when (action) {
        is Action.Forget -> forget(action.index)
        is Action.Pin -> pin(action.index)
        is Action.Unpin -> unpin(action.index)
        is Action.Block -> block(action.index)
        else -> error("not offered: $action")
    }

    // a prediction's code (impl/streams/E-design.md §1); a candidate the habits ranked, its tier and
    // uses under the code of the level that ranked it (U-81); none the habits did not rank
    override fun why(index: Int): Why? {
        val c = candidates.getOrNull(index) ?: return null
        val r = lastRead.ranks[c]
        if (predicting) return Why(r?.tier, r?.uses ?: 0, Code(Kinds.PREDICTION, predictedAfter))
        r ?: return null
        return Why(r.tier, r.uses, lastRead.decoded?.levels?.firstOrNull { it.end == c.end }?.code)
    }

    // the decoder's lattice holds the user's counts as they were
    override val rereads get() = true

    override fun fieldFlags(flags: Int) {
        recorder.learnFlags = flags
    }

    override fun reread(): Snapshot? {
        if (input.isEmpty() && !predicting) return null
        decoder.reset()
        return again()
    }

    override fun offers(index: Int): Set<Offer> {
        val c = candidates.getOrNull(index) ?: return emptySet()
        if (predicting) return predictionOffers(c)
        val phrase = lastRead.placed[c.text]?.takeIf { NO_WORD in c.words }
        return buildSet {
            if (forgettable(c)) add(Offer.FORGET)
            // a word pinned is unpinned, any other word may be (U-83); a phrase is the settings' (custom phrases)
            if (pinnable(c)) add(if (pinnedHere(c) != null) Offer.UNPIN else Offer.PIN)
            // but deleted from them here, as v0.3.0 did (从自定义词组中删除, docs/words.md; UAT PY-49)
            if (phrase != null) add(Offer.DELETE_PHRASE)
            if (phrase == null && blockable(c)) add(Offer.BLOCK)
        }
    }

    // phrases hold nothing to forget, nor a text nothing was learned of (PY-33); text kept as typed
    // the record learned (U-27's letters) is forgotten by its text (U-82; review-1b N3). Not where
    // nothing is to be kept (a password): forgetting records the word. A word added by hand is the
    // user's to delete here too (U-82)
    private fun forgettable(c: Candidate) =
        learning && !shownAside(c) && (recorder.habits.knows(c.text) || habitOrder.isAdded(c.text))

    // a prediction the user taught is forgotten, as are the words learned; any may be blocked
    private fun predictionOffers(c: Candidate): Set<Offer> {
        val word = c.words.singleOrNull()
        if (!learning) return emptySet()
        if (user == null || word == null || entries(c) == null) return emptySet()
        return buildSet {
            if (recorder.habits.knows(c.text)) add(Offer.FORGET)
            if (block != null) add(Offer.BLOCK)
        }
    }

    /**
     * Whether [c] may be pinned first under its own keys (U-83, impl/design-1b.md S8): a word, of any
     * level, the pieces' or a sentence of them; not a phrase, an emoji, text kept as typed; and not
     * where nothing is to be kept, as the keys would be what was typed there (a password).
     */
    private fun pinnable(c: Candidate) =
        learning && recorder.learns && c.text !in lastRead.aside && NO_WORD !in c.words && entries(c) != null

    // the keys [c] reads, as its pin is filed: a level-2 word's are those up to its end
    private fun spanCode(c: Candidate) = recorder.code(readFrom(), readFrom() + c.end, entries(c)?.let(CommitRecorder::words).orEmpty())

    // what is pinned under [c]'s keys, if [c] is it; a fault of working that out is learning's
    private fun pinnedHere(c: Candidate) = recorder.safely(null) { recorder.habits.pinned(spanCode(c))?.takeIf { it.text == c.text } }

    /** Pins the candidate at [index] first under its keys, in place of what was pinned there; then reads the input again. */
    private fun pin(index: Int): Snapshot {
        val c = candidates.getOrNull(index)
        if (c == null || predicting || !pinnable(c)) return snapshot()
        // pins are the user's say, recorded wherever they are made, as their unpins (review-1a-app S2)
        recorder.pin(c.text, on = true) { spanCode(c) to entries(c) }
        return again()
    }

    /**
     * Unpins the candidate at [index], pinned under its keys, or deletes it from the custom phrases
     * if it is one ([Offer.DELETE_PHRASE]); then reads the input again.
     */
    private fun unpin(index: Int): Snapshot {
        val c = candidates.getOrNull(index)
        val pinned = c?.let(::pinnedHere)
        val phrase = c?.let { lastRead.placed[it.text]?.takeIf { _ -> NO_WORD in it.words } }
        if (pinned == null && phrase != null && !predicting) {
            phraseBook.remove(phrase.key, phrase.value)
            read()
            return snapshot()
        }
        if (pinned == null || predicting) return snapshot()
        recorder.pin(pinned.text, on = false) { spanCode(c) to null }
        return again()
    }

    /**
     * Forgets what was learned of the candidate at [index] (U-84): its text, not each of its words;
     * one of the user's own words is theirs no more, one added by hand off their list (U-82). Then
     * reads the input again, on the page shown if it still has candidates.
     */
    private fun forget(index: Int): Snapshot {
        // as offered: nothing recorded where nothing is to be kept
        val c = candidates.getOrNull(index)
        if (!learning || c == null || !forgettable(c)) return snapshot()
        recorder.forget(c.text)
        // or the next pick learns it again, as the word before it
        val words = entries(c)
        if (words != null && before() in words) dropContext()
        decoder.reset()
        return again()
    }

    /** The candidates anew, after what was learned changed: on the page shown, if it still has some. */
    private fun again(): Snapshot {
        if (predicting) {
            lastRead = predict()
            predicting = candidates.isNotEmpty()
            page = 0
            return snapshot()
        }
        val shown = page
        read()
        // a page past the words read at first: read on to it, as turning to it did
        readUntil((shown + 1) * pageSize + 1)
        if (shown * pageSize < candidates.size) page = shown
        return snapshot()
    }

    // a word, not a sentence of them: blocking every word of one would block common ones; and not
    // where nothing is to be kept, as the word blocked is
    private fun blockable(c: Candidate) = learning && block != null && user != null && entries(c)?.size == 1

    private fun block(index: Int): Snapshot {
        val c = candidates.getOrNull(index)
        val word = if (user == null || c == null) null else entries(c)?.singleOrNull()
        if (word == null || block == null || !learning) return snapshot()
        block.invoke(word)
        if (before() == word) dropContext()
        decoder.reset()
        return again()
    }

    /**
     * How each word of [c], of the input read last, reads; null if some is text kept as typed.
     * A prediction read no input: its likeliest reading.
     */
    private fun entries(c: Candidate): List<Entry>? {
        if (predicting) return c.words.singleOrNull()?.let { w -> predictor.reading(w)?.let { listOf(Entry(c.text, it)) } }
        val graph = lastRead.decoded?.graph ?: return null
        var from = graph.start
        return c.words.indices.map { i ->
            val word = c.words[i]
            val reading = (if (word == NO_WORD) null else shows.reading(graph, from, c.ends[i], word)) ?: return null
            from = c.ends[i]
            Entry(text(word), reading.syllables)
        }
    }

    private fun text(word: Int) = wordText(data, user, word)

    /**
     * The word what is committed now follows, to learn the pair: the last committed here, else, after
     * the app's text ([Action.Context], or what Backspace left), its last word as the model has it.
     * A pick there learned no pair once: 点了 picked after the field's 我 twenty times stayed behind 带来.
     */
    private fun before(): Entry? =
        lastEntry ?: context.lastOrNull()?.takeIf { it != NO_WORD && contextLearnable }?.let { w -> predictor.reading(w)?.let { Entry(text(w), it) } }

    /** Learns [c], a prediction picked, after the word it follows: how it reads (what follows it is offered after its end), or null. */
    private fun learnPrediction(c: Candidate): Entry? {
        if (!learning) return null
        recorder.prediction(c.text, predictedAfter, candidates.firstOrNull()?.text.orEmpty())
        return if (user == null) null else entries(c)?.singleOrNull()
    }

    /**
     * The word the candidates of the input after the pieces follow, as their level-1 rows file it
     * (M2); null where nothing ranks. Where nothing is learned (a password, an incognito tab) the
     * habits still rank, after no word: a pin is first whatever is before it (U-83; review-1b S3).
     */
    private fun rankedAfter(): String? {
        if (!recorder.learns) return null
        if (!learning) return ""
        return CommitRows.prevAfter(recorder.prev(), pieces.map { it.learned ?: return null })
    }

    /** The pieces picked, and [rest] after them, written to the learning record as one commit, with the syllables taken. */
    private fun record(rest: CommitRows.Pick? = null) {
        // a piece picked while learning was off (a password) has none: not part learned
        if (learning && recorder.learns) recorder.commit(pieces.map { it.learned ?: return } + listOfNotNull(rest), nineKeys)
    }

    // [c], picked, as the learning record takes it: see CommitRecorder.pick. A phrase, an
    // emoji or a character by its parts is no word picked (PLAN §2.7 U-66, U-67): its commit learns nothing
    private fun toLearn(c: Candidate) = if (!learning || !recorder.learns || shownAside(c)) null else recorder.pick(
        c, readFrom(), input.length - readFrom(), entries(c), candidates, shownAside, ::text, nineKeys?.anyTaken == true, pieces.isEmpty(),
        lastRead.ranks[c]?.original == true,
    )

    /** Whether the keys kept for the learning record cover the input a place each, as they must: for tests. */
    internal val pressedInStep get() = recorder.pressed.length == input.length

    // the phrases, emoji and characters by their parts among the candidates: none the first of the words shown.
    // A phrase is its own candidate, text: one of the decoder's of the same text is a word (impl/PLAN.md §2.7)
    private val shownAside = { c: Candidate -> c.text in lastRead.aside || NO_WORD in c.words && c.text in lastRead.placed }

    /**
     * [word], read [reading], committed by a glide over this though held back here ([GlideReading]):
     * learned as picked over [first], the first shown, asked only where it is learned. Before the context it is followed by.
     */
    fun heldBack(word: String, reading: String, first: () -> String) = if (learning && recorder.learns && reading.isNotEmpty()) recorder.heldBack(Word(word, reading), first()) else Unit

    private fun turn(to: Int): Snapshot {
        readUntil((to + 1) * pageSize + 1)
        if (to < 0 || to * pageSize >= candidates.size) return snapshot(handled = candidates.isNotEmpty())
        page = to
        return snapshot()
    }

    /**
     * A key not read, going to the app: what is typed is committed as the first candidate reads
     * it, the rest as typed, and nothing is predicted after it (`nihao,` gives 你好，).
     */
    private fun leave(): Snapshot {
        val picked = if (input.isNotEmpty() && !predicting && candidates.isNotEmpty()) pick(0).commit else ""
        // the first candidate read only the start
        val rest = if (input.isNotEmpty()) commitRaw().commit else ""
        // the learning record's text too: what it files next follows punctuation, no word (design §3);
        // and the key goes into the field, not written here (U-03)
        dropContext()
        recorder.unverify()
        clear()
        return snapshot(commit = picked + rest, handled = false)
    }

    private fun commitRaw(): Snapshot {
        if (input.isEmpty()) {
            clear()
            return snapshot(handled = false)
        }
        // digits say nothing: the pinyin shown for them, as 搜狗 commits it
        // digits no reading took stay as typed
        val shown = lastRead.restShown.filter { it != ' ' && it != SyllableGraph.SEPARATOR }
        // a letter typed as itself (九键) is upper case in the input; no other input has one
        val rest = if (nineKeys == null || shown.isEmpty()) asTyped(input.substring(readFrom())) else shown
        val text = pieces.joinToString("") { it.text } + rest
        record(CommitRows.Pick(readFrom(), listOf(Word(rest, "")), intArrayOf(input.length), 'F', "", null))
        // text as typed is no words: nothing to go on from
        dropContext()
        // but for the word before what is learned: letters as typed (U-52), which end with the text's
        recorder.committed(text, learning)
        clear()
        return snapshot(commit = text)
    }

    private fun commit(text: String, words: IntArray): Snapshot {
        context = (context + words).takeLast(2).toIntArray()
        // a pair goes on from lastEntry, what was learned; not learned, so no pair with what comes next either
        contextLearnable = false
        // what the user writes is kept no longer than it has to be where they learn nothing
        recent = if (learning) recentTail(recent + text) else ""
        recorder.committed(text, learning)
        // text kept as typed ends the context
        if (context.lastOrNull() == NO_WORD) dropContext()
        clear()
        // what may follow a password is no one's business
        if (prediction && learning) lastRead = predict()
        predicting = candidates.isNotEmpty()
        return snapshot(commit = text)
    }

    /**
     * What may follow the words committed last. After one the model never saw (the user's own,
     * put together from pieces, or a dictionary word it lacks) that is what follows its end, 再
     * of 拟再: the model's context would be its unknown word, followed by anything. So too after
     * a word the model has but never saw anything after: a new word of the compiled word pack
     * is a unigram alone.
     */
    private fun predict(): Read {
        // a search field: no 联想 (U-91)
        if (recorder.learnFlags and Commit.NO_PREDICT != 0) return Read()
        val (prev2, prev) = TextWords.previous(context)
        val last = lastEntry
        val tail = { last?.let { predictor.tail(it.text, it.syllables) } ?: NO_WORD }
        val learned = recorder.prev()
        // a word of the model's the decoder read in pieces (好的 as 好 的): the tail finds it, as it ends with it
        val own = if (prev == NO_WORD || data.wordIndex.find(learned) != NO_WORD) NO_WORD else ownWord(user, text(prev), learned)
        // after the user's own word, what follows the decoder's word it ends with
        val offer = if (own == NO_WORD) {
            // what was put in after it is counted after that word, as the record has its text (原 UAT PY-71)
            val decoded = if (prev == NO_WORD) "" else text(prev)
            val whole = if (decoded.isEmpty() || learned.length <= decoded.length || !learned.endsWith(decoded)) NO_WORD else data.wordIndex.find(learned)
            predictionOrder.offer(recorder.habits, prev2, prev, if (whole == NO_WORD) prev else whole, tail)
        } else {
            predictionOrder.offer(recorder.habits, NO_WORD, own, tail = { if (prev < data.vocabulary.size) prev else tail() })
        }
        predictedAfter = offer.after
        return Read(candidates = offer.candidates, ranks = offer.ranks)
    }

    // a key the app took (an arrow, Enter, Escape) moved the cursor or changed the text before it,
    // and no Context follows where the service expected that: what was committed here is context
    // no more
    override val lastCommit get() = recorder.lastRecorded

    override fun passedOn() {
        recorder.passedOn()
        dropContext()
    }

    private fun dropContext() {
        context = IntArray(0)
        lastEntry = null
        recent = ""
        recorder.text = ""
    }

    private fun clear() {
        input.setLength(0)
        cursor = 0
        lastRead = Read()
        // the next input decodes afresh: what another input method learned or forgot since, with
        // the user's words they share, would not reach the scores the decoder keeps from key to key
        decoder.reset()
        nineKeys?.clear()
        recorder.clear()
        pieces.clear()
        page = 0
        predicting = false
    }

    private fun readFrom() = pieces.lastOrNull()?.end ?: 0

    /** Reads the input after the pieces picked. */
    private fun read() {
        predicting = false
        page = 0
        cursor = cursor.coerceIn(readFrom(), input.length)
        if (input.isEmpty()) {
            lastRead = Read()
            nineKeys?.clear()
            return
        }
        // where each key was tapped, as the input stands: the 14 keys' letters weighed by it (none elsewhere)
        val graph = segmenter.segment(input.substring(readFrom()), if (keyed?.letters?.isGrouped == true) recorder.pressed.taps(readFrom(), input.length) else null)
        val (prev2, prev) = TextWords.previous(context + pieces.flatMap { it.words.asList() })
        val decoding = decoder.decode(graph, prev2, prev)
        // what the user's sentences keep put in them, before anything reranks them (U-08, design-1c §1)
        val after = rankedAfter()
        val sentences = habitOrder.enforce(graph, readFrom(), after, decoding.sentences)
        // a syllable still being typed (wo xiang m) is no reading to weigh: the refiner's pick there
        // (我项目 over 我想买) only flashed on the bar, some 30 ms before the next key replaced it
        if (refiner != null && sentences.size > 1 && endsOnSyllable(graph)) {
            unrefined = sentences
            refined = 0
        }
        nineKeys?.forgetBest()
        val decoderFirst = decoding.sentences.firstOrNull() ?: decoding.words.firstOrNull()
        val more = decoding.words.size >= PinyinDecoder.DEFAULT_WORDS
        // what the habits put in, worked out once a reading: a later page, a refined pick place it again
        // level 2 by the decoder's own first, before anything was put in (design-1c §1)
        val levels = habitOrder.levels(graph, readFrom(), after, decoderFirst, sentences + decoding.words)
        place(Decoded(graph, rerank(sentences), decoding.words, more = more, first = decoderFirst, levels = levels))
        // a phrase goes where its order says only in a list that long, else last: read that far,
        // or it would move as the rest are read
        lastRead.placed.values.maxOfOrNull { it.order }?.let { readUntil(it + lastRead.placed.size + 1) }
        // so would a coarse word waiting past a first page the words read so far do not fill
        while (lastRead.unsettled && lastRead.moreWords) readUntil(candidates.size + 1)
        // offered here only: the refiner's pick reordering them would drop a tap on the list shown
        nineKeys?.offer(input, readFrom(), graph)
    }

    /** Lists the sentences [decoded] has, then its words, with the phrases the input has among them. */
    private fun place(decoded: Decoded, time: Calendar? = null) {
        val rest = input.length - readFrom()
        val key = input.substring(readFrom())
        // the built-in ones for full pinyin only: 双拼's sj, xq spell syllables
        val phrases = phraseBook.offered(builtIns = !spell && builtInPhrases)
        // the time asked once, for what is shown and what is kept of it to agree
        val time = time ?: if (key in phrases) now() else null
        val placed: Map<String, Phrase> = if (time == null) emptyMap() else phrases.offered(key, time).associate { it.text to it.phrase }
        // a phrase is text, no word: nothing to learn, and what follows it reads after nothing; so are
        // the emoji and the characters by their parts, placed before the phrases so each is where its order says
        val asText = { text: String -> Candidate(text, rest, 0f, intArrayOf(NO_WORD), intArrayOf(rest)) }
        val ordered = habitOrder.order(decoded.graph, (decoded.sentences + decoded.words).distinctBy { it.text }, decoded.sentences, decoded.levels, pageSize)
        val read = ordered.list
        // a phrase of order k a place down where a pin leads: the pin stays first (U-83)
        val withPhrases = { list: List<Candidate> ->
            val pin = ordered.pinned?.takeIf { list.firstOrNull() === it }
            val unpinned = if (pin == null) list else list.drop(1)
            val placed = phrases.place(key, { checkNotNull(time) }, unpinned, text = { it.text }, all = { it.end == rest }) { asText(it) }
            // a phrase of the pin's text is the pin, shown once (an older build's, both kept: review-1b M2)
            listOfNotNull(pin) + if (pin == null) placed else placed.filter { it.text != pin.text }
        }
        // what a habit put first or among the used is the user's choice, never held back as coarse (S7)
        // the user's own too, unused for 30 typing days (U-43; review-1b N2)
        val kept = { c: Candidate -> ordered.tier(c).let { it != null && it <= Tier.MINE } }
        val readPlaced by lazy { withPhrases(read) }
        val readHeld by lazy { coarseCandidates.held(readPlaced, pageSize, kept) }
        // the emoji go with the reading shown first: not one held past the first page, nor one a pinned phrase put second
        // the dates 日期 stands for and so on, where the input is no key of them (双拼's sj is a syllable): never twice
        val wordPhrases = { word: String -> if (builtInPhrases && placed.isEmpty()) phraseBook.builtInsOf(word) { time ?: now() } else emptyList() }
        val withAside = AsideCandidates.place(read, key, emoji, chaizi.takeUnless { spell }, { it.text }, { it.end == rest }, asText, wordPhrases, first = { coarseCandidates.order(readPlaced, pageSize, readHeld).firstOrNull() }) { ordered.tier(it) == Tier.LIFTED }
        // nothing set aside: the list worked out for that is the one listed
        val listed = if (withAside.candidates === read) readPlaced else withPhrases(withAside.candidates)
        val held = if (withAside.candidates === read) readHeld else coarseCandidates.held(listed, pageSize, kept)
        val shownOrder = coarseCandidates.order(listed, pageSize, held)
        // the first reading of all the input shown: the preedit, the syllables offered and Enter's text go
        // by it, as ranked (impl/design-1b.md §2). Every one held, that is the candidate shown first, the
        // rest of the input as typed after it
        val best = read.firstOrNull { it.end == rest && it !in held }
            ?: shownOrder.firstOrNull()?.takeIf { decoded.sentences.isNotEmpty() }?.let { throughEnd(it, decoded.graph.end) }
        val shown = if (best == null) PreeditPlaces.Builder().add(asTyped(decoded.graph.input), readFrom(), input.length) else shows.preedit(decoded.graph, best)
        val picked = pieces.joinToString("") { it.text }
        val restShown = shown.rest
        lastRead = Read(
            candidates = shownOrder,
            decoded = decoded,
            time = time,
            placed = placed,
            preedit = picked + restShown,
            restShown = restShown,
            places = shown.places(picked.length, input.length),
            unsettled = held.isNotEmpty() && listed.size - held.size < pageSize,
            aside = withAside.aside,
            ranks = ordered.ranks,
        )
    }

    /** What the rerankers read before the input. */
    private fun textBefore() = (if (learning) recent else "") + pieces.joinToString("") { it.text }

    /** [sentences] with the one the [reranker] picks first. */
    private fun rerank(sentences: List<Candidate>): List<Candidate> {
        val reranker = reranker ?: return sentences
        return first(sentences, reranker.pick(textBefore(), sentences.map { it.text }, sentences.map { it.score }))
    }

    /** A slice of the [refiner]'s work; once it picks, the decoder's sentences with that one first. */
    private fun refine(): Snapshot {
        val sentences = unrefined ?: return snapshot()
        if (refiner == null || refined++ >= refiner.slices) {
            unrefined = null
            return snapshot()
        }
        val picked = refiner.refine(textBefore(), sentences.map { it.text }, sentences.map { it.score }, REFINE_BUDGET)
            ?: return snapshot()
        unrefined = null
        val decoded = lastRead.decoded
        if (picked != SentenceRefiner.NONE && decoded != null) place(decoded.copy(sentences = first(sentences, picked)), lastRead.time)
        return snapshot()
    }

    private fun snapshot(commit: String = "", handled: Boolean = true): Snapshot {
        val from = page * pageSize
        val shown = candidates.subList(minOf(from, candidates.size), minOf(from + pageSize, candidates.size))
        return Snapshot(
            commit = commit,
            preedit = lastRead.preedit,
            candidates = shown.map { it.text },
            page = page,
            hasPreviousPage = page > 0,
            hasNextPage = from + pageSize < candidates.size || lastRead.moreWords,
            handled = handled,
            predicting = predicting,
            hints = shown.map(::hint).takeIf { h -> h.any { it.isNotEmpty() } }.orEmpty(),
            // not counted while the decoder has words not yet read
            total = if (lastRead.moreWords) -1 else candidates.size,
            first = from,
            // a prediction too: what was learned of it may be forgotten (see predictionOffers)
            actionable = candidates.isNotEmpty(),
            refines = unrefined != null,
            syllables = nineKeys?.offered.orEmpty(),
            syllablesId = nineKeys?.id ?: 0,
            cursor = if (cursor >= input.length) -1 else lastRead.places.preeditAt(cursor),
        )
    }

    // text kept as typed as it shows and is committed: see KeyedSegmenter.asTyped (a grouped key's code never reaches the field)
    private fun asTyped(text: String) = keyed?.asTyped(text) ?: text

    // how the input shows: its syllables, as typed, spelt (an inner class, for the session's size)
    private val shows = Shows()

    private inner class Shows {
        /**
         * The rest of the preedit for [best], with where in the input each of its places is: the
         * syllables it reads, a space between them, or `'` where one was typed (`ni hao`, `xi'an`);
         * text kept as typed shows as typed.
         */
        fun preedit(graph: SyllableGraph, best: Candidate): PreeditPlaces.Builder {
            val parts = ArrayList<String>()
            val ends = ArrayList<Int>()
            var from = graph.start
            var raw = false
            for (i in best.words.indices) {
                val to = best.ends[i]
                val word = best.words[i]
                val reading = if (word == NO_WORD) null else reading(graph, from, to, word)
                if (reading != null && reading.syllables.size > 1 && reading.syllables.all(LatinWords::isLetter)) {
                    // a Latin word shows as typed, not a letter at a time
                    parts += reading.shown.joinToString("")
                    ends += reading.ends.last()
                    raw = false
                } else if (reading != null) {
                    parts += reading.shown
                    ends += reading.ends
                    nineKeys?.note(readFrom() + from, reading.ends.map { readFrom() + it }, reading.syllables)
                    raw = false
                } else if (raw) {
                    parts[parts.size - 1] = parts.last() + asTyped(graph.text(from, to))
                    ends[ends.size - 1] = to
                } else {
                    // as rest in commitRaw
                    parts += asTyped(graph.text(from, to))
                    ends += to
                    raw = true
                }
                from = to
            }
            return joined(graph, parts, ends, readFrom())
        }

        /** How [edge] of [graph] shows, read as [syllable]: as typed, spelt for 双拼, letters for digits. */
        private fun shown(graph: SyllableGraph, edge: Int, syllable: Int): String = when {
            // digits say nothing: the syllable read, a fuzzy one too (9664 as zhong with z for zh), and
            // of one still being typed as many letters as were typed
            keyed != null -> if (graph.kind(edge) == Kind.SYLLABLE) Syllables.spelling(syllable) else keyed.started(syllable, graph.text(edge).length)
            spell && graph.kind(edge) == Kind.SYLLABLE -> Syllables.spelling(syllable)
            else -> graph.text(edge)
        }

        // the user's trie for the user's words
        fun reading(graph: SyllableGraph, from: Int, to: Int, word: Int): Reading? =
            reading(graph, from, to, word, data.dictionary, user?.takeIf { word >= data.vocabulary.size }) { e, syllable -> shown(graph, e, syllable) }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 5

        /** The nine keys' 分词 key: a separator while something is typed, the app's 1 otherwise. */
        const val NINE_KEYS_SEPARATOR = '1'

        /**
         * The [refiner]'s work at most per [Action.Refine], in [Reranker]'s units: one position of
         * the large model, a few milliseconds on a phone. A key pressed meanwhile waits for it.
         */
        const val REFINE_BUDGET = 1
        // as much of the host's text as makes it the end of what was committed, not a word that
        // happens to be: as long as the text the context is read from
        private const val OVERLAP = 24
    }
}

// the text committed that the reranker reads before the input: well over what it has room
// for (39 tokens), so a commit drops only text it no longer reads, and it goes on from what
// it read rather than read it all again
private const val RECENT = 128

/** The last [RECENT] chars of [text], not starting in the middle of a pair: what a session keeps of the text before. */
internal fun recentTail(text: String): String {
    if (text.length <= RECENT) return text
    val from = text.length - RECENT
    return text.substring(if (Character.isLowSurrogate(text[from])) from + 1 else from)
}

/**
 * Whether the input's longest last syllable is whole: zhong, not zho (whose o alone is a
 * syllable too, but not what is being typed), nor an initial. n, m, ng and r (嗯, 呣), and a,
 * o and e, are syllables as well as initials: at the end, likelier the start of a longer one.
 */
fun endsOnSyllable(graph: SyllableGraph): Boolean {
    val last = (0 until graph.edgeCount).filter { graph.to(it) == graph.end }
    val from = last.minOfOrNull { graph.from(it) } ?: return false
    val longest = last.filter { graph.from(it) == from }.map { graph.kind(it) }
    return Kind.SYLLABLE in longest && Kind.INITIAL !in longest
}

// [c] read on to [end] as typed: a word of the start of the input, shown for all of it
// [text]'s reading, if [typed] is one of its misreadings ([PinyinData.misreadings]); else ""
private fun misreading(data: PinyinData, text: String, typed: String): String {
    val word = data.misreadings[text] ?: return ""
    return if (word.misread.any { it.replace("'", "") == typed }) word.reading else ""
}

private fun throughEnd(c: Candidate, end: Int) = if (c.end >= end) c else Candidate(c.text, end, c.score, c.words + NO_WORD, c.ends + end)

private fun first(sentences: List<Candidate>, picked: Int) =
    if (picked == 0) sentences else listOf(sentences[picked]) + sentences.filterIndexed { i, _ -> i != picked }

/** [parts] of [graph]'s input, each ending at its [ends], joined for the preedit; [base] where the graph's input starts in the session's. */
private fun joined(graph: SyllableGraph, parts: List<String>, ends: List<Int>, base: Int): PreeditPlaces.Builder {
    val out = PreeditPlaces.Builder().add(graph.input.substring(0, graph.start), base, base + graph.start)
    for (k in parts.indices) {
        val start = if (k == 0) graph.start else ends[k - 1]
        // a separator typed shows as itself, a space between keys
        if (k > 0 && graph.input[start - 1] == SyllableGraph.SEPARATOR) {
            out.add(SyllableGraph.SEPARATOR.toString(), base + start - 1, base + start)
        } else if (k > 0) {
            out.add(" ", base + start, base + start)
        }
        // a separator ending the part shows as the next joiner, or, last, not at all
        val end = if (graph.input[ends[k] - 1] == SyllableGraph.SEPARATOR) ends[k] - 1 else ends[k]
        out.add(parts[k], base + start, base + end)
    }
    return out
}

// the user's own word [learned], the record's last, that the decoder's last word [decoded] ends, made
// of several of the decoder's (你好 of 你 好): 联想 goes on from it, as what they put in after it is
// counted after it by its text (U-71) and turned off there (U-72). NO_WORD where the decoder's is it
private fun ownWord(user: UserModel?, decoded: String, learned: String): Int =
    if (learned.length <= decoded.length || !learned.endsWith(decoded)) NO_WORD else user?.idsOfText(learned)?.firstOrNull() ?: NO_WORD
