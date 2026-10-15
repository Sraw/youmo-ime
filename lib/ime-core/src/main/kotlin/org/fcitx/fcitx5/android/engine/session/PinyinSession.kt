/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.Misreadings
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases.Phrase
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.fcitx.fcitx5.android.engine.user.UserScorer
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
 * With a [user] model, what is committed is learned: its words and their order. Where the user
 * corrected the engine, putting the text together from pieces or picking a reading of several
 * words other than the first, the text is learned as one word, found whole the next time. A
 * prediction picked is learned too, after what came before it, as its likeliest reading: 中文
 * picked after 用 comes up after 用 again. Text kept as typed is not learned.
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
    /** Learned from what is picked, while [learning]. */
    private val prior: LayerPrior? = null,
    /**
     * Offered where their order says when what is left of the input is their key; a candidate
     * pinned ([Action.Pin]) or deleted ([Action.Unpin]) changes them.
     */
    private val phraseBook: PhraseBook = PhraseBook(),
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
    /** The words the user added by hand (UserLexicon.addWord): theirs, as a word they typed is, so none is held back as coarse (see CoarseWords). */
    addedWords: Set<String> = emptySet(),
    // last, so a test's picker can follow as a lambda
    private val reranker: SentencePicker? = null,
) : Session {
    init {
        require(pageSize >= 1) { "page size $pageSize" }
    }

    /**
     * Picked text, its words, where it ends in the input, how its words read (null if some is no
     * word), and whether it was picked over a better reading.
     */
    private class Piece(val text: String, val words: IntArray, val end: Int, val entries: List<Entry>?, val corrected: Boolean)

    /**
     * What the decoder read of the input after the pieces: its sentences as placed, the reranker's
     * or refiner's pick first, then its words, [more] of which may be read as a list is shown that
     * far; [first], its first before the reranker and phrases moved it.
     */
    private data class Decoded(
        val graph: SyllableGraph,
        val sentences: List<Candidate>,
        val words: List<Candidate>,
        val more: Boolean,
        val first: Candidate?,
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
    ) {
        val moreWords get() = decoded?.more == true
    }

    // a few hundred lookups: made with each session, as the data's index is
    private val coarse = CoarseWords.of({ data.wordIndex.find(it) }, CoarseWords.LIST - addedWords)
    private val coarseCandidates = CoarseCandidates(coarse, data, user)

    private val decoder = PinyinDecoder(
        data.dictionary,
        data.vocabulary,
        (if (user == null) WordScorer.of(data.model) else UserScorer(data.model, user)).let { if (prior == null) it else prior.scorer(it) },
        penalties,
        user = user,
        extensions = extensions,
        wholeFirst = segmenter.typesLetters,
        coarse = coarse,
        asTyped = ::asTyped,
    )
    private val predictor = Predictor(data.model, data.vocabulary, data.dictionary, user, coarse = coarse, charReadings = charReadings)
    private val textWords by lazy { TextWords(data.model, data.wordIndex) }

    private val input = StringBuilder()
    private var cursor = 0 // where in the input the next key goes
    private var cursorMoved = false // by the last action, a tap or an arrow
    private val pieces = ArrayList<Piece>()
    private var context = IntArray(0) // the words committed last, at most two
    private var lastEntry: Entry? = null // the last of them, if known how it reads
    private var recent = "" // the text they end, for the reranker

    private var lastRead = Read()
    private val candidates get() = lastRead.candidates
    private var unrefined: List<Candidate>? = null // the decoder's sentences, till the refiner picks one
    private var refined = 0 // slices of refining spent on them
    private val nineKeys = (segmenter as? T9Segmenter)?.let { NineKeys(data, it.letters) }
    private var page = 0
    private var predicting = false

    override var learning = true

    override fun apply(action: Action): Snapshot {
        val moves = action is Action.Cursor || action is Action.Step
        // what the refiner weighs is the input as last read, on the first page; a cursor moved reads nothing
        if (action != Action.Refine && !moves) unrefined = null
        // the keyboard counts presses of a key again unaware of a tap on the preedit: the next is a press of its own
        val afterMove = cursorMoved
        cursorMoved = moves || (afterMove && action == Action.Refine)
        return if (afterMove && action is Action.Letter) act(action.copy(again = false)) else act(action)
    }

    private fun act(action: Action): Snapshot = when (action) {
        Action.Refine -> refine()
        Action.Ink -> snapshot()
        is Action.Key -> type(action.char)
        is Action.Letter -> letter(action.letter, action.again)
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
            dropContext()
            clear()
            snapshot()
        }
        is Action.Context -> takeContext(action.before)
        is Action.Cursor -> moved { lastRead.places.inputAt(action.at, readFrom()) }
        is Action.Step -> moved { (cursor.toLong() + action.by).coerceIn(0, input.length.toLong()).toInt() }
    }

    /**
     * Takes [text], committed by a session over this one (a character looked up by its strokes),
     * as what the input to come follows: the app's text is what this committed, then [text].
     */
    fun follow(text: String): Snapshot = takeText(recent + text)

    /**
     * [keys] typed as [Action.Key]s one after another would be, the input read once: keys a
     * session over this one hands it together (a stroke lookup a letter ended). On the nine keys
     * a key at a time: each key read may change the syllables offered, each change their next id.
     */
    fun typeAll(keys: String): Snapshot {
        if (keys.isEmpty() || nineKeys != null || !keys.all(segmenter::reads)) {
            return keys.fold(snapshot()) { before, c -> apply(Action.Key(c)).let { it.copy(commit = before.commit + it.commit) } }
        }
        unrefined = null
        insert(keys)
        read()
        return snapshot()
    }

    /** See [Action.Context]. */
    private fun takeContext(before: String): Snapshot {
        // the text still ends with what was committed here: the cursor did not go anywhere
        // (an editor reporting it where the service did not expect it), and the words the
        // user picked say more than the model's reading of the text. The host reads less than
        // is kept, so its text may be the end of it, long enough not to be so by chance
        val same = before.endsWith(recent) || before.length >= OVERLAP && recent.endsWith(before)
        if (learning && recent.isNotEmpty() && same) {
            clear()
            return snapshot()
        }
        return takeText(before)
    }

    /** [before], the app's text, as what the input to come follows, in place of what was committed. */
    private fun takeText(before: String): Snapshot {
        dropContext()
        clear()
        // what the app has is kept no longer than the input, and not at all where the user
        // learns nothing, as what they commit; text ending in no word (a space, a letter) is
        // no context for the reranker either, as after text committed as typed
        if (learning) {
            context = textWords.lastTwo(before)
            if (context.isNotEmpty()) recent = tail(before)
        }
        return snapshot()
    }

    private fun type(c: Char): Snapshot {
        if (!reads(c)) return leave()
        // on the nine keys 1 is 分词
        insert((if (c == NINE_KEYS_SEPARATOR && nineKeys != null) SyllableGraph.SEPARATOR else c).toString())
        read()
        return snapshot()
    }

    // [keys] typed at the cursor, never before the pieces' end (read and moved keep it there)
    private fun insert(keys: String) {
        input.insert(cursor, keys)
        cursor += keys.length
    }

    /** The cursor put where [to] says, if the input keeps one; nothing read again. */
    private fun moved(to: () -> Int): Snapshot {
        if (input.isEmpty() || predicting) return snapshot(handled = false)
        cursor = to().coerceIn(readFrom(), input.length)
        return snapshot()
    }

    /** See [Action.Letter]: on the nine keys alone, the others type letters as they are. */
    private fun letter(c: Char, again: Boolean): Snapshot = cursorAfter(nineKeys?.letter(input, c, again, readFrom(), cursor) ?: -1)

    private fun backspace(): Snapshot {
        if (input.isEmpty()) {
            // the app deletes; a prediction shown goes away
            clear()
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
            input.deleteCharAt(--cursor)
            while (pieces.isNotEmpty() && (pieces.last().end > cursor || pieces.last().end >= input.length)) pieces.removeAt(pieces.size - 1)
        }
        read()
        return snapshot()
    }

    /** Takes the syllable at [index] of those offered: see the class. */
    private fun takeSyllable(index: Int, of: Int): Snapshot =
        cursorAfter(if (predicting) -1 else nineKeys?.take(input, index, of, cursor) ?: -1)

    // the input read again, the cursor at [at], if the edit made one; else -1
    private fun cursorAfter(at: Int): Snapshot {
        if (at >= 0) {
            cursor = at
            read()
        }
        return snapshot()
    }

    override fun reads(c: Char): Boolean =
        segmenter.reads(c) || (c == SyllableGraph.SEPARATOR || c == NINE_KEYS_SEPARATOR && nineKeys != null) && input.isNotEmpty()

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
        val word = data.misreadings[c.text] ?: return ""
        val from = readFrom()
        val typed = input.substring(from, minOf(input.length, from + c.end)).filter { it != SyllableGraph.SEPARATOR }
        return if (word.misread.any { it.replace("'", "") == typed }) word.reading else ""
    }

    private fun pick(index: Int): Snapshot {
        val c = candidates.getOrNull(index) ?: return snapshot(handled = candidates.isNotEmpty())
        if (predicting) {
            lastEntry = learnPrediction(c)
            return commit(c.text, c.words)
        }
        pieces += Piece(c.text, c.words, readFrom() + c.end, if (user == null) null else entries(c), c !== lastRead.decoded?.first)
        learnPrior(c)
        if (pieces.last().end < input.length) {
            read()
            return snapshot()
        }
        val text = pieces.joinToString("") { it.text }
        val learned = learn(text)
        // not learned, so no pair with what comes next either
        if (learned == null) lastEntry = null
        return commit(text, learned ?: pieces.flatMap { it.words.asList() }.toIntArray())
    }

    /** What a long press on a candidate offered, and the user chose: see [offers]. */
    private fun pressed(action: Action) = when (action) {
        is Action.Forget -> forget(action.index)
        is Action.Pin -> pin(action.index)
        is Action.Unpin -> unpin(action.index)
        is Action.Block -> block(action.index)
        else -> error("not offered: $action")
    }

    override fun offers(index: Int): Set<Offer> {
        val c = candidates.getOrNull(index) ?: return emptySet()
        if (predicting) return predictionOffers(c)
        val phrase = lastRead.placed[c.text]
        return buildSet {
            if (forgettable(c)) add(Offer.FORGET)
            // the first already, and a phrase: nothing to pin
            if ((phrase == null || index != 0) && pinnable(c)) add(Offer.PIN)
            if (phrase != null) add(Offer.UNPIN)
            if (phrase == null && blockable(c)) add(Offer.BLOCK)
        }
    }

    // text kept as typed and phrases hold no words to forget. Not where nothing is to be kept (a
    // password): forgetting records the word
    private fun forgettable(c: Candidate) = learning && user != null && entries(c) != null

    // a prediction the user taught is forgotten, as are the words learned; any may be blocked
    private fun predictionOffers(c: Candidate): Set<Offer> {
        val word = c.words.singleOrNull()
        if (!learning) return emptySet()
        if (user == null || word == null || entries(c) == null) return emptySet()
        return buildSet {
            if (user.probability(NO_WORD, word) > 0f) add(Offer.FORGET)
            if (block != null) add(Offer.BLOCK)
        }
    }

    /**
     * Whether [c] may be pinned as a phrase of what is left of the input: only one reading all
     * of it, as fcitx5-chinese-addons has it, or typing that again would drop the rest of the
     * keys; only to a key the file can keep; and not where nothing is to be kept, as the key
     * would be what was typed there (a password); nor a key with a letter typed as itself (九键,
     * upper case), which no other input method types.
     */
    private fun pinnable(c: Candidate) =
        learning && c.end == input.length - readFrom() && input.substring(readFrom()).let { CustomPhrases.isKey(it) && it.none(Char::isUpperCase) }

    /**
     * Makes the candidate at [index] the first phrase of what is left of the input, as
     * fcitx5-chinese-addons does: offered first whenever that is typed again. A phrase filled in
     * when offered is pinned as the user wrote it, to be filled in again.
     */
    private fun pin(index: Int): Snapshot {
        val c = candidates.getOrNull(index)
        if (c == null || predicting || !pinnable(c)) return snapshot()
        // read again whatever saving it does: the phrases changed all the same
        try {
            phraseBook.pin(input.substring(readFrom()), lastRead.placed[c.text]?.value ?: c.text)
        } finally {
            read()
        }
        return snapshot()
    }

    /** Deletes the phrase the candidate at [index] is, then reads the input again. */
    private fun unpin(index: Int): Snapshot {
        val phrase = candidates.getOrNull(index)?.let { lastRead.placed[it.text] }
        if (phrase == null || predicting) return snapshot()
        try {
            phraseBook.remove(phrase.key, phrase.value)
        } finally {
            read()
        }
        return snapshot()
    }

    /**
     * Forgets what the user model learned of the words of the candidate at [index], then reads
     * the input again, on the page shown if it still has candidates. Text kept as typed and
     * phrases hold no words to forget.
     */
    private fun forget(index: Int): Snapshot {
        // as offered: nothing recorded where nothing is to be kept
        if (!learning) return snapshot()
        val c = candidates.getOrNull(index)
        val words = if (user == null || c == null) null else entries(c)
        if (words == null) return snapshot()
        user?.forget(words)
        // or the next pick learns it again, as the word before it
        if (lastEntry in words) dropContext()
        decoder.reset()
        return again()
    }

    /** The candidates anew, after what was learned changed: on the page shown, if it still has some. */
    private fun again(): Snapshot {
        if (predicting) {
            lastRead = Read(candidates = predict())
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
        if (lastEntry == word) dropContext()
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
            val reading = (if (word == NO_WORD) null else reading(graph, from, c.ends[i], word)) ?: return null
            from = c.ends[i]
            Entry(text(word), reading.syllables)
        }
    }

    private fun text(word: Int) = if (word < data.vocabulary.size || user == null) data.vocabulary.word(word) else user.text(word)

    /** Learns [c], a prediction picked, after the word before it: what it was learned as, or null. */
    private fun learnPrediction(c: Candidate): Entry? {
        if (user == null || !learning) return null
        val entry = entries(c)?.singleOrNull() ?: return null
        // after the app's text, its last word as the model has it
        val before = lastEntry ?: context.lastOrNull()?.takeIf { it != NO_WORD }?.let { w -> predictor.reading(w)?.let { Entry(text(w), it) } }
        user.learn(before, listOf(entry))
        decoder.reset()
        return entry
    }

    /**
     * Learns the pieces about to be committed as [text]: as one word if the user corrected the
     * engine to get it, else as the words of the one piece.
     *
     * @return the words learned, or null if nothing was
     */
    private fun learn(text: String): IntArray? {
        if (user == null || !learning) return null
        val entries = pieces.map { it.entries ?: return null }
        val corrected = pieces.size > 1 || (pieces[0].corrected && pieces[0].words.size > 1)
        val sentence = if (corrected && text.codePointCount(0, text.length) <= MAX_PHRASE) {
            listOf(Entry(text, entries.flatten().flatMap { it.syllables.asList() }.toIntArray()))
        } else {
            entries.flatten()
        }
        val words = user.learn(lastEntry, sentence)
        lastEntry = sentence.last()
        // the scores the decoder kept are stale
        decoder.reset()
        return words
    }

    /**
     * What [picked] says of its layers against the first choice, as far as it read: see
     * [LayerPrior.learn]. The first choice as the user saw it, after the reranker and the pinned
     * phrases: what they passed over is the signal, whoever put it there.
     */
    private fun learnPrior(picked: Candidate) {
        if (prior == null || !learning) return
        val first = candidates.firstOrNull()
        if (first == null || picked === first) return
        val offered = first.words.filterIndexed { i, _ -> first.ends[i] <= picked.end }.toIntArray()
        if (prior.learn(picked.words, offered)) decoder.reset()
    }

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
        dropContext()
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
        // text as typed is no words: nothing to go on from
        dropContext()
        clear()
        return snapshot(commit = text)
    }

    private fun commit(text: String, words: IntArray): Snapshot {
        context = (context + words).takeLast(2).toIntArray()
        // what the user writes is kept no longer than it has to be where they learn nothing
        recent = if (learning) tail(recent + text) else ""
        // text kept as typed ends the context
        if (context.lastOrNull() == NO_WORD) dropContext()
        clear()
        // what may follow a password is no one's business
        if (prediction && learning) lastRead = Read(candidates = predict())
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
    private fun predict(): List<Candidate> {
        val (prev2, prev) = TextWords.previous(context)
        val last = lastEntry ?: return predictor.predict(prev2, prev)
        return predictor.predict(prev2, prev) { predictor.tail(last.text, last.syllables) }
    }

    private fun dropContext() {
        context = IntArray(0)
        lastEntry = null
        recent = ""
    }

    private fun clear() {
        input.setLength(0)
        cursor = 0
        lastRead = Read()
        // the next input decodes afresh: what another input method learned or forgot since, with
        // the user's words they share, would not reach the scores the decoder keeps from key to key
        decoder.reset()
        nineKeys?.clear()
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
        val graph = segmenter.segment(input.substring(readFrom()))
        val (prev2, prev) = TextWords.previous(context + pieces.flatMap { it.words.asList() })
        val decoding = decoder.decode(graph, prev2, prev)
        // a syllable still being typed (wo xiang m) is no reading to weigh: the refiner's pick there
        // (我项目 over 我想买) only flashed on the bar, some 30 ms before the next key replaced it
        if (refiner != null && decoding.sentences.size > 1 && endsOnSyllable(graph)) {
            unrefined = decoding.sentences
            refined = 0
        }
        nineKeys?.forgetBest()
        val decoderFirst = decoding.sentences.firstOrNull() ?: decoding.words.firstOrNull()
        val more = decoding.words.size >= PinyinDecoder.DEFAULT_WORDS
        place(Decoded(graph, rerank(decoding.sentences), decoding.words, more = more, first = decoderFirst))
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
        val phrases = phraseBook.phrases
        // the time asked once, for what is shown and what is kept of it to agree
        val time = time ?: if (key in phrases) now() else null
        val placed: Map<String, Phrase> = if (time == null) emptyMap() else phrases.offered(key, time).associate { it.text to it.phrase }
        // a phrase is text, no word: nothing to learn, and what follows it reads after nothing
        val listed = phrases.place(
            key, { checkNotNull(time) }, (decoded.sentences + decoded.words).distinctBy { it.text },
            text = { it.text }, all = { it.end == rest },
        ) { Candidate(it, rest, 0f, intArrayOf(NO_WORD), intArrayOf(rest)) }
        val held = coarseCandidates.held(listed, pageSize)
        val ordered = coarseCandidates.order(listed, pageSize, held)
        // the first reading shown: the preedit, the syllables offered and Enter's text go by it. Every
        // sentence held, that is the candidate shown first, the rest of the input as typed after it
        val best = decoded.sentences.firstOrNull { it !in held }
            ?: ordered.firstOrNull()?.takeIf { decoded.sentences.isNotEmpty() }?.let { throughEnd(it, decoded.graph.end) }
        val shown = if (best == null) PreeditPlaces.Builder().add(asTyped(decoded.graph.input), readFrom(), input.length) else preedit(decoded.graph, best)
        val picked = pieces.joinToString("") { it.text }
        lastRead = Read(
            candidates = ordered,
            decoded = decoded,
            time = time,
            placed = placed,
            preedit = picked + shown.rest,
            restShown = shown.rest,
            places = shown.places(picked.length, input.length),
            unsettled = held.isNotEmpty() && listed.size - held.size < pageSize,
        )
    }

    // [c] read on to [end] as typed: a word of the start of the input, shown for all of it
    private fun throughEnd(c: Candidate, end: Int) = if (c.end >= end) c else Candidate(c.text, end, c.score, c.words + NO_WORD, c.ends + end)

    /** What the rerankers read before the input. */
    private fun textBefore() = (if (learning) recent else "") + pieces.joinToString("") { it.text }

    /** [sentences] with the one the [reranker] picks first. */
    private fun rerank(sentences: List<Candidate>): List<Candidate> {
        val reranker = reranker ?: return sentences
        return first(sentences, reranker.pick(textBefore(), sentences.map { it.text }, sentences.map { it.score }))
    }

    private fun first(sentences: List<Candidate>, picked: Int) =
        if (picked == 0) sentences else listOf(sentences[picked]) + sentences.filterIndexed { i, _ -> i != picked }

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

    /**
     * [best] as the syllables it reads, a space between them, or `'` where one was typed: `ni hao`,
     * `xi'an`; text kept as typed shows as typed.
     */
    private fun preedit(graph: SyllableGraph, best: Candidate): PreeditPlaces.Builder {
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
        val base = readFrom()
        val out = PreeditPlaces.Builder().add(graph.input.substring(0, graph.start), base, base + graph.start)
        for (k in parts.indices) {
            val start = if (k == 0) graph.start else ends[k - 1]
            // a separator typed shows as itself, a space between keys
            if (k > 0 && graph.input[start - 1] == SyllableGraph.SEPARATOR) {
                out.add(SyllableGraph.SEPARATOR.toString(), base + start - 1, base + start)
            } else if (k > 0) out.add(" ", base + start, base + start)
            // a separator ending the part shows as the next joiner, or, last, not at all
            val end = if (graph.input[ends[k] - 1] == SyllableGraph.SEPARATOR) ends[k] - 1 else ends[k]
            out.add(parts[k], base + start, base + end)
        }
        return out
    }

    /** How [word] reads from [from] to [to] of [graph]: what each syllable shows, where it ends, and its id. */
    private class Reading(val shown: List<String>, val ends: List<Int>, val syllables: IntArray)

    /** How [edge] of [graph] shows, read as [syllable]: as typed, spelt for 双拼, letters for digits. */
    private fun shown(graph: SyllableGraph, edge: Int, syllable: Int): String = when {
        // digits say nothing: the syllable read, a fuzzy one too (9664 as zhong with z for zh), and
        // of one still being typed as many letters as were typed, a run of a key pressed again maybe one
        segmenter is T9Segmenter -> Syllables.spelling(syllable).let {
            if (graph.kind(edge) == Kind.SYLLABLE) it else it.take(segmenter.lettersOf(graph.text(edge), syllable))
        }
        spell && graph.kind(edge) == Kind.SYLLABLE -> Syllables.spelling(syllable)
        else -> graph.text(edge)
    }

    // text kept as typed as it shows and is committed: see T9Segmenter.asTyped
    private fun asTyped(text: String) = if (segmenter is T9Segmenter) T9Segmenter.asTyped(text) else text

    /**
     * The syllables along a path of [graph] from [from] to [to] that spell [word], exact readings
     * first, or null if there is none. The decoder keeps only where words end, so they are
     * found again here.
     */
    private fun reading(graph: SyllableGraph, from: Int, to: Int, word: Int): Reading? {
        val dictionary = data.dictionary
        // the user's trie for the user's words
        val own = user?.takeIf { word >= data.vocabulary.size }
        val shown = ArrayList<String>()
        val ends = ArrayList<Int>()
        val syllables = ArrayList<Int>()

        fun child(node: Int, syllable: Int) = own?.child(node, syllable) ?: dictionary.child(node, syllable)

        fun spells(node: Int): Boolean = if (own != null) {
            (0 until own.wordCount(node)).any { own.word(node, it) == word }
        } else {
            (0 until dictionary.wordCount(node)).any { dictionary.word(node, it) == word }
        }

        fun walk(at: Int, node: Int): Boolean {
            if (at == to) return syllables.isNotEmpty() && spells(node)
            for (e in graph.edges(at)) {
                if (graph.kind(e) == Kind.RAW || graph.to(e) > to) continue
                val m = graph.matches(e)
                // exact readings first: a fuzzy one reaching the same word is the less likely
                for (i in (0 until m.size).sortedBy { m.flags(it) }) {
                    val child = child(node, m.syllable(i))
                    if (child < 0) continue
                    shown += shown(graph, e, m.syllable(i))
                    ends += graph.to(e)
                    syllables += m.syllable(i)
                    if (walk(graph.to(e), child)) return true
                    shown.removeAt(shown.size - 1)
                    ends.removeAt(ends.size - 1)
                    syllables.removeAt(syllables.size - 1)
                }
            }
            return false
        }

        val root = own?.root ?: dictionary.root
        return if (walk(from, root)) Reading(shown, ends, syllables.toIntArray()) else null
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
        // the longest text put together from pieces that is learned as a word: past this, it is
        // a sentence, which would crowd the dictionary with things never typed again
        const val MAX_PHRASE = 8
        // the text committed that the reranker reads before the input: well over what it has room
        // for (39 tokens), so a commit drops only text it no longer reads, and it goes on from what
        // it read rather than read it all again
        private const val RECENT = 128

        // as much of the host's text as makes it the end of what was committed, not a word that
        // happens to be: as long as the text the context is read from
        private const val OVERLAP = 24

        /** The last [RECENT] chars of [text], not starting in the middle of a pair. */
        internal fun tail(text: String): String {
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
    }
}
