/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.fcitx.fcitx5.android.engine.learn.CommitRows
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
import org.fcitx.fcitx5.android.engine.phrase.CommonWordBook
import org.fcitx.fcitx5.android.engine.phrase.CommonWords
import org.fcitx.fcitx5.android.engine.phrase.AsideCandidates
import org.fcitx.fcitx5.android.engine.phrase.PartsLookup
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.KeyedSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import org.fcitx.fcitx5.android.engine.reading.WordReading
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.user.LearningUse
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import java.util.Calendar

/**
 * Pinyin typed and picked piece by piece. The candidates are the readings of all the input left, then the words
 * it may start with; picking one that reads only the start keeps it and reads the rest after it, until all is
 * read and committed; then predictions follow until a key is typed. Backspace drops a piece whose keys it
 * reaches. The input keeps a cursor; a piece whose keys an edit changes is dropped.
 *
 * Committed words stay the context of what is typed next until [Action.Reset] or raw text; [Action.Context]
 * makes the app's text the context. Commits are learned ([learnTo]) and candidates ordered by habits ([HabitOrder]).
 * A [reranker] reweighs the best readings as each key is typed; a slower [refiner] in [REFINE_BUDGET] slices
 * while the user pauses ([Action.Refine]).
 *
 * @param spell show each syllable spelt out, not as typed: for 双拼, whose keys say little
 * @param keysShown show 双拼's syllables as the scheme's keys pressed instead (a slipped key as meant)
 *
 * On the nine keys the input is digits, shown as the best reading's pinyin; a syllable taken ([Action.Syllable])
 * goes in as its letters and a separator, and Backspace right after gives its digits back, as 搜狗 does.
 */
class PinyinSession(
    private val data: PinyinData,
    private val segmenter: Segmenter,
    penalties: Penalties = Penalties(),
    private val spell: Boolean = false,
    private val keysShown: Boolean = false,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val user: UserModel? = null,
    private val prediction: Boolean = true,
    /** The prior per layer the scores go through; no longer learned. */
    prior: LayerPrior? = null,
    private val phraseBook: PhraseBook = PhraseBook(),
    /** Whether the built-in phrases (date, time, weekday) are offered: under their keys only where the [segmenter] spells full pinyin. */
    private val builtInPhrases: Boolean = true,
    /** 常用词: replacements first of all, a pin after them, where the rest of the input is their key. */
    private val commonWords: CommonWordBook = CommonWordBook.none(),
    private val emoji: (String) -> List<String> = { emptyList() },
    /** Characters by their parts' pinyin after [Chaizi.KEY] (no pinyin starts with u). */
    private val chaizi: PartsLookup = PartsLookup { emptyList() },
    private val now: () -> Calendar = JvmClock::calendar,
    private val refiner: SentenceRefiner? = null,
    /** Keeps a word the user blocked ([Action.Block]); none offered without. */
    private val block: ((Entry) -> Unit)? = null,
    /** Asked for when first needed: one map for the sessions over [data] to share. */
    charReadings: () -> Map<Int, IntArray> = { Predictor.charReadingsOf(data.dictionary, data.vocabulary) },
    /** The decoder's per-node extension cache ([PinyinDecoder]), made lazily; shared by sessions that score alike over the same [user] and [prior]. */
    extensions: () -> Array<IntArray?> = { arrayOfNulls<IntArray>(data.dictionary.nodeCount) },
    /** Words the user added by hand, in list order: never held back as coarse, ranked after formed words; reread as [UserModel.listings] change. */
    private val addedWords: () -> Set<String> = { emptySet() },
    // a few hundred lookups as the session is made, unless given (Engines' sessions share one)
    private val coarse: CoarseWords = CoarseWords.of({ data.wordIndex.find(it) }, CoarseWords.LIST - addedWords()),
    /** [LearningUse.DEFAULT] but to measure the channels' worth (ime-eval). */
    use: LearningUse = LearningUse.DEFAULT,
    learnTo: LearnTo = LearnTo(),
    // last, so a test's picker can follow as a lambda
    private val reranker: SentencePicker? = null,
) : Session {

    /** Picked text, its words, where it ends in the input, its entries (null if some is no word). */
    private class Piece(val text: String, val words: IntArray, val end: Int, val entries: List<Entry>?, val learned: CommitRows.Pick?)

    /** The decoder's reading of the input after the pieces: sentences placed then words, [more] read lazily; [first] before reranking; [levels] how habits rank it. */
    private data class Decoded(
        val graph: SyllableGraph,
        val sentences: List<Candidate>,
        val words: List<Candidate>,
        val more: Boolean,
        val first: Candidate?,
        val levels: List<HabitOrder.Level> = emptyList(),
    )

    /** The input after the pieces as read last, or the predictions: replaced whole, so nothing of one reading leaks into the next. */
    private class Read(
        val candidates: List<Candidate> = emptyList(),
        val decoded: Decoded? = null,
        val time: Calendar? = null, // when the phrases were filled in: the same as more words are read
        val placed: Map<String, Phrase> = emptyMap(),
        val common: Map<String, CommonWords.Found> = emptyMap(),
        val preedit: String = "",
        val restShown: String = "",
        val places: PreeditPlaces = PreeditPlaces.NONE,
        val unsettled: Boolean = false, // a coarse candidate waits past a first page the list does not fill yet
        // the emoji and Chaizi characters among the candidates, by text, each with its hint
        val aside: Map<String, String> = emptyMap(),
        val ranks: Map<Candidate, HabitRanker.Ranked<Candidate>> = emptyMap(),
    ) {
        val moreWords get() = decoded?.more == true
    }

    private val coarseCandidates = CoarseCandidates(coarse, data, user)

    private val decoder = PinyinDecoder(
        data.dictionary, data.vocabulary, use.scorer(data.model, user, prior), penalties,
        user = user, extensions = extensions, wholeFirst = segmenter.typesLetters, coarse = coarse, asTyped = { keyed.typedOrSelf(it) },
        whole = use.whole(data.model, user, prior), uncounted = use.uncounted(data.model, user, prior), toneAllows = (segmenter as? KeyedSegmenter)?.toneAllows,
    )
    private val predictor = Predictor(data.model, data.vocabulary, data.dictionary, user, coarse = coarse, charReadings = charReadings)
    private val textWords = TextWords(data.model, data.wordIndex)

    private val input = StringBuilder()
    private var cursor = 0
    private var cursorMoved = false
    private val pieces = ArrayList<Piece>()
    private var context = IntArray(0) // at most two
    private var lastEntry: Entry? = null
    private var recent = ""
    // whether a pick may learn a pair with the context's last word: not after a field that cleared itself
    private var contextLearnable = true

    private var lastRead = Read()
    private val candidates get() = lastRead.candidates
    private var unrefined: List<Candidate>? = null // the decoder's sentences, till the refiner picks one
    private var refined = 0
    private val keyed = segmenter as? KeyedSegmenter
    private val recorder = CommitRecorder(
        learnTo, keyed?.letters, user, { habitOrder.isAdded(it) || data.wordIndex.find(it) != NO_WORD }, segmenter::segment,
        split = { textWords.boundaries(it) }, isAdded = { habitOrder.isAdded(it) },
    )
    private val nineKeys = keyed?.let { k ->
        NineKeys(data, k.letters, recorder.pressed, k::isKey, k::initialsOf, k::keyOf, k::shownOffered) { digits -> if (learning && recorder.learns) recorder.habits.bar(recorder.takeLayout, digits) else emptyMap() }
    }
    private val habitOrder: HabitOrder = HabitOrder(data, user, recorder, decoder, addedWords)
    private val predictions = Predictions(predictor, textWords, user, habits = recorder.habits, learning = { learning })
    private val pager = Pager(pageSize)
    private var predicting = false

    override var learning = true

    override fun apply(action: Action): Snapshot {
        // a Backspace's whole deletions taken back now that no context says otherwise; a Reset too, since the host
        // posts one before a cursor report's context
        if (action !is Action.Context) recorder.settle()
        val moves = action is Action.Cursor || action is Action.Step
        // what the refiner weighs is the input as last read; a cursor moved reads nothing
        if (action != Action.Refine && !moves) unrefined = null
        // the keyboard counts presses unaware of a tap on the preedit: the next press is a press of its own
        val afterMove = cursorMoved
        cursorMoved = moves || (afterMove && action == Action.Refine)
        return if (afterMove && action is Action.Letter) letter(action.letter, action.again, runFrom = cursor, glided = action.glided) else act(action)
    }

    private fun act(action: Action): Snapshot = when (action) {
        Action.Refine -> refine()
        is Action.Ink, is Action.Glide, is Action.Stroke, is Action.NineKey, Action.LookUp -> snapshot()
        is Action.Key -> type(action.char, action.glided, action.at, action.touch)
        is Action.Letter -> letter(action.letter, action.again, glided = action.glided)
        Action.Backspace -> backspace()
        is Action.Select -> select(action.index)
        is Action.Pick -> pick(action.index)
        is Action.Syllable -> takeSyllable(action.index, action.of)
        is Action.Forget, is Action.Pin, is Action.Unpin, is Action.Block -> pressed(action)
        Action.NextPage -> turn(pager.page + 1)
        Action.PreviousPage -> turn(pager.page - 1)
        Action.CommitRaw -> commitRaw()
        // 联想's ×: the offer alone goes; the context and record stay for what is typed next
        Action.DropPredictions -> { predictions.unoffer(); read(); snapshot() }
        Action.Reset -> {
            // what is in the field is no longer known till a context says
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
        is Action.Step -> moved { stepped(action) }
    }

    // past the places the preedit cannot show (a taken syllable's keys shown as fewer letters), or a step looks lost
    private fun stepped(step: Action.Step): Int {
        var at = step.applyTo(cursor, input.length)
        val by = if (step.by < 0) -1 else 1
        while (at > readFrom() && at < input.length && !lastRead.places.shows(at)) at += by
        return at
    }

    /** Takes [text], committed by a session over this one (a stroke lookup), as what the input to come follows. */
    fun follow(text: String): Snapshot {
        recorder.followed(text, learning)
        return takeText(recent + text, learnText = recorder.text + text)
    }

    /** [keys] typed as [Action.Key]s one after another, read once; on the nine keys and for non-letters still one at a time, as each may change what the next means. */
    fun typeAll(keys: String): Snapshot {
        if (keys.isEmpty() || nineKeys != null || !keys.all { it in 'a'..'z' }) return keys.fold(snapshot()) { before, c -> apply(Action.Key(c)).let { it.copy(commit = before.commit + it.commit) } }
        unrefined = null
        insert(keys)
        read()
        return snapshot()
    }

    private fun takeContext(before: String, cleared: Boolean): Snapshot {
        // the text still ends with what was committed here: the words the user picked say more than the model's reading.
        // The host reads less than is kept, so its text may be only the end of it, long enough not to match by chance
        val same = recent.isNotEmpty() && (before.endsWith(recent) || before.length >= OVERLAP && recent.endsWith(before))
        // a cleared field (a chat's message sent) leaves no word before what comes next
        if (!cleared && learning && same) {
            clear()
            return snapshot()
        }
        return takeText(before, cleared)
    }

    /** [before], the app's text, as what the input to come follows; [cleared]: for scoring only, no pair learned with it. */
    private fun takeText(before: String, cleared: Boolean = false, learnText: String = before): Snapshot {
        dropContext()
        contextLearnable = !cleared
        recorder.followText(learnText, cleared, learning)
        clear()
        // kept no longer than the input and not at all without learning; text ending in no word is no reranker context
        if (learning) {
            context = textWords.lastTwo(before)
            if (context.isNotEmpty()) recent = recentTail(before)
        }
        return snapshot()
    }

    private fun type(typed: Char, glided: Boolean = false, at: Float = Float.NaN, touch: Touch? = null): Snapshot {
        if (endsInput(typed)) return leave()
        val c = semicolonRead(typed, keyed, input, readFrom(), cursor) ?: return snapshot()
        // on the nine keys 1 is 分词; on 大千 a space after a syllable with no tone is its first tone
        insert((if (c == NINE_KEYS_SEPARATOR && nineKeys?.oneSeparates == true) SyllableGraph.SEPARATOR else if (c == ' ') keyed?.spaceTone ?: c else c).toString(), glided, at, touch)
        read()
        return snapshot()
    }

    private fun insert(keys: String, glided: Boolean = false, at: Float = Float.NaN, touch: Touch? = null) {
        recorder.pressed.insert(cursor, keys, glided, at, input = input, letters = keyed?.letters, touch = touch)
        input.insert(cursor, keys)
        cursor += keys.length
    }

    private fun moved(to: () -> Int): Snapshot {
        if (input.isEmpty() || predicting) return snapshot(handled = false)
        cursor = to().coerceIn(readFrom(), input.length)
        return snapshot()
    }

    private fun letter(c: Char, again: Boolean, runFrom: Int = readFrom(), glided: Boolean = false): Snapshot =
        cursorAfter(nineKeys?.letter(input, c, again, runFrom, cursor, glided) ?: -1)

    private fun backspace(): Snapshot {
        if (input.isEmpty()) {
            // what was committed here is context only as far as it is left: 你 committed and erased kept 你脾气 before 日期
            val learnText = withoutLast(recorder.text)
            recorder.backspace()
            if (recent.isEmpty()) { dropContext(); clear(); recorder.text = learnText } else takeText(withoutLast(recent), recorder.cleared, learnText)
            return snapshot(handled = false)
        }
        val given = if (cursor == input.length) nineKeys?.giveBack(input) ?: -1 else -1
        if (given >= 0) {
            while (pieces.isNotEmpty() && pieces.last().end > given) pieces.removeAt(pieces.size - 1)
            cursor = input.length
        } else {
            if (cursor == 0) return snapshot()
            recorder.pressed.delete(cursor - 1, input, keyed?.letters)
            input.deleteCharAt(--cursor)
            while (pieces.isNotEmpty() && (pieces.last().end > cursor || pieces.last().end >= input.length)) pieces.removeAt(pieces.size - 1)
        }
        read()
        return snapshot()
    }

    private fun takeSyllable(index: Int, of: Int): Snapshot {
        val keys = nineKeys?.takeUnless { predicting } ?: return cursorAfter(-1)
        recorder.taking(keys, candidates, input.length - readFrom(), shownAside)
        return cursorAfter(keys.take(input, index, of, cursor))
    }

    private fun cursorAfter(at: Int): Snapshot {
        if (at >= 0) {
            cursor = at
            read()
        }
        return snapshot()
    }

    override fun endsInput(c: Char): Boolean = !reads(c) || !segmenter.readsAfter(input.substring(readFrom(), cursor), c)

    // a zhuyin tone with nothing typed is not typed (it would go unseen); a separator only after something typed
    override fun reads(c: Char): Boolean = readsKey(c) || input.isNotEmpty() && separates(c, nineKeys?.oneSeparates == true) || c == ' ' && keyed?.tonesBySpace(input.substring(readFrom(), cursor)) == true
    private fun readsKey(c: Char) = segmenter.reads(c) && (input.isNotEmpty() || ZhuyinKeys.toneOf(c) == 0)

    override fun candidates(from: Int, count: Int): List<Choice> {
        readUntil(from + count)
        return candidates.subList(minOf(from, candidates.size), minOf(from + count, candidates.size)).map { Choice(it.text, hint(it)) }
    }

    /** Reads the decoder's words until there are [count] candidates, or no more: those past the first few only as a list is shown that far. */
    private fun readUntil(count: Int) {
        while (lastRead.moreWords && candidates.size < count) {
            val read = lastRead
            val decoded = read.decoded ?: return
            val (prev2, prev) = TextWords.previous(context + pieces.flatMap { it.words.asList() })
            val asked = maxOf(decoded.words.size * 2, decoded.words.size + count - candidates.size)
            val words = decoder.words(decoded.graph, prev2, prev, asked)
            place(decoded.copy(words = words, more = words.size >= asked), read.time)
        }
    }

    // 双拼's keys are no reading to compare
    private fun hint(c: Candidate): String {
        if (spell || predicting) return ""
        lastRead.aside[c.text]?.let { return it }
        val from = readFrom()
        return misreading(data, c.text, input.substring(from, minOf(input.length, from + c.end)).filter { it != SyllableGraph.SEPARATOR })
    }

    override fun lookedUp(index: Int) = candidates.getOrNull(index)?.text?.takeIf { !spell && !predicting && it in lastRead.aside && isOneHan(it) }?.let { pieces.joinToString("") { p -> p.text } }

    override fun readingOf(index: Int) = candidates.getOrNull(index)?.let { c -> entries(c)?.mapIndexed { i, e -> WordReading(e.text, c.words[i].takeIf { it < data.vocabulary.size } ?: -1, e.syllables) } }

    private fun pick(index: Int): Snapshot {
        val c = candidates.getOrNull(index) ?: return snapshot(handled = candidates.isNotEmpty())
        if (predicting) {
            lastEntry = learnPrediction(c)
            // the rest of a whole phrase is no word: what follows goes on from its last words
            return commit(c.text, predictions.phraseOf(c)?.let { textWords.lastTwo(it.whole) } ?: c.words)
        }
        pieces += Piece(c.text, c.words, readFrom() + c.end, if (user == null) null else entries(c), toLearn(c))
        if (pieces.last().end < input.length) {
            read()
            return snapshot()
        }
        val text = pieces.joinToString("") { it.text }
        record()
        // for 联想 after a word the model lacks
        lastEntry = if (learning) pieces.last().entries?.lastOrNull() else null
        return commit(text, pieces.flatMap { it.words.asList() }.toIntArray())
    }

    private fun pressed(action: Action) = when (action) {
        is Action.Forget -> forget(action.index)
        is Action.Pin -> pin(action.index)
        is Action.Unpin -> unpin(action.index)
        is Action.Block -> block(action.index)
        else -> error("not offered: $action")
    }

    // a prediction's code; a candidate the habits ranked, its tier and uses under its level's code
    override fun why(index: Int): Why? {
        val c = candidates.getOrNull(index) ?: return null
        val r = lastRead.ranks[c]
        if (predicting) return predictions.why(index)
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
        if (owned.common(c) != null) return setOf(Offer.DELETE_COMMON_WORD)
        val phrase = lastRead.placed[c.text]?.takeIf { NO_WORD in c.words }
        // a custom phrase is the settings', but deletable here too
        return OfferRules.offers(
            learning,
            forgets = { forgettable(c) },
            pins = { pinnable(c) },
            pinned = { pinnedHere(c) != null },
            blocks = { blockable(c) },
            phrase = phrase != null,
        )
    }

    // phrases hold nothing to forget; raw text the record learned is forgotten by its text. Not without learning:
    // forgetting records the word
    private fun forgettable(c: Candidate) =
        learning && !shownAside(c) && (recorder.habits.knows(c.text) || habitOrder.isAdded(c.text) || predictions.phraseOf(c) != null)

    private fun predictionOffers(c: Candidate): Set<Offer> {
        val word = c.words.singleOrNull()
        if (!learning || predictions.phraseOf(c) != null) return if (learning) setOf(Offer.FORGET) else emptySet()
        if (user == null || word == null || entries(c) == null) return emptySet()
        return OfferRules.offers(learning, forgets = { recorder.habits.knows(c.text) }, blocks = { block != null })
    }

    /** Whether [c] may be pinned under its own keys: a word or sentence, not a phrase, emoji or raw text, and not without learning (the keys would be a password's). */
    private fun pinnable(c: Candidate) =
        learning && recorder.learns && c.text !in lastRead.aside && NO_WORD !in c.words && entries(c) != null

    // the keys [c] reads as its pin is filed; a slipped key under the key meant, so a pin of 呀 read off `ta` is not
    // ta's. [read] is passed by callers that want the entries too, so readings are walked once
    private fun spanCode(c: Candidate, read: Pair<List<Entry>?, Map<Int, Char>> = entriesAndSlips(c)) = read.let { (entries, slips) -> recorder.code(readFrom(), readFrom() + c.end, entries?.let(CommitRecorder::words).orEmpty(), slips) }

    private fun pinnedHere(c: Candidate) = recorder.safely(null) { recorder.habits.pinned(spanCode(c))?.takeIf { it.text == c.text } }

    private fun pin(index: Int): Snapshot {
        val c = candidates.getOrNull(index)
        if (c == null || predicting || !pinnable(c)) return snapshot()
        // pins are the user's say, recorded wherever made
        recorder.pin(c.text, on = true) { entriesAndSlips(c).let { read -> spanCode(c, read) to read.first } }
        // a pinned word is one typed: the decoder's collected words are read again, as unpin's and forget's
        decoder.reset()
        return again()
    }

    /** Unpins the candidate at [index], or deletes it from the custom phrases ([Offer.DELETE_PHRASE]); then rereads. */
    private fun unpin(index: Int): Snapshot {
        val c = candidates.getOrNull(index)
        val pinned = c?.let(::pinnedHere)
        owned.remove(c, pinned != null)?.let { return it }
        if (pinned == null || predicting) return snapshot()
        recorder.pin(pinned.text, on = false) { spanCode(c) to null }
        decoder.reset()
        return again()
    }

    /** Forgets what was learned of the candidate at [index], by its text; an own word stops being one, an added one leaves its list. */
    private fun forget(index: Int): Snapshot {
        val c = candidates.getOrNull(index)
        if (!learning || c == null || !forgettable(c)) return snapshot()
        // a whole phrase: its count alone; it has no words
        predictions.phraseOf(c)?.let { recorder.forgetPhrase(it.whole) } ?: recorder.forget(c.text)
        // or the next pick learns it again, as the word before it
        val words = entries(c)
        if (words != null && before() in words) dropContext()
        decoder.reset()
        return again()
    }

    private fun again(): Snapshot {
        if (predicting) {
            lastRead = predict()
            predicting = candidates.isNotEmpty()
            pager.reset()
            return snapshot()
        }
        val shown = pager.page
        read()
        readUntil((shown + 1) * pageSize + 1)
        pager.keep(shown, candidates.size)
        return snapshot()
    }

    // a sentence is blocked whole (啊之): blocking each word would block common ones
    private fun blockable(c: Candidate) = learning && block != null && user != null && blocked(c) != null

    // an emoji or Chaizi character is blocked by its text in the reading it stands for
    private fun blocked(c: Candidate): Entry? = lastRead.aside[c.text]?.let { aside -> AsideBlocks.entry(c.text, AsideBlocks.shown(c.text, aside) { chaizi.shownIn(it, pieces.joinToString("") { p -> p.text }, aside) }) { lastRead.candidates.firstOrNull { it.text !in lastRead.aside && c.text in emoji(it.text) }?.let(::entries) } } ?: entries(c)?.let { it.singleOrNull() ?: Entry.of(c.text, it) }

    private fun block(index: Int): Snapshot {
        val word = candidates.getOrNull(index)?.takeIf { user != null && learning }?.let(::blocked)
        if (word == null || block == null) return snapshot()
        block.invoke(word)
        if (before() == word) dropContext()
        decoder.reset()
        return again()
    }

    /** How each word of [c] reads; null if some is raw text. A prediction: its likeliest reading. */
    private fun entries(c: Candidate, graph: SyllableGraph? = lastRead.decoded?.graph): List<Entry>? {
        if (predicting) return predictions.entryOf(c)?.let(::listOf)
        return shows.readings(c, graph ?: return null)?.mapIndexed { i, reading -> Entry(text(c.words[i]), reading.syllables) }
    }

    // [entries] and slips off one walk: each walked them, a trie walk a word, at every pin and pick
    private fun entriesAndSlips(c: Candidate): Pair<List<Entry>?, Map<Int, Char>> {
        val graph = lastRead.decoded?.graph?.takeUnless { predicting } ?: return entries(c) to emptyMap()
        return shows.readings(c, graph).let { r -> r?.mapIndexed { i, reading -> Entry(text(c.words[i]), reading.syllables) } to shows.slips(graph, r) }
    }

    private fun text(word: Int) = wordText(data, user, word)

    /** The word a commit now follows: the last committed here, else the app's text's last word (else 点了 after the field's 我 never learned). */
    private fun before(): Entry? =
        lastEntry ?: context.lastOrNull()?.takeIf { it != NO_WORD && contextLearnable }?.let { w -> predictor.reading(w)?.let { Entry(text(w), it) } }

    private fun learnPrediction(c: Candidate): Entry? {
        if (!learning) return null
        recorder.prediction(c.text, predictions.after, candidates.firstOrNull()?.text.orEmpty(), predictions.phraseOf(c)?.whole.orEmpty())
        return if (user == null) null else entries(c)?.singleOrNull()
    }

    /** The word the level-1 rows are filed after; null where nothing ranks. Without learning habits still rank, after no word. */
    private fun rankedAfter(): String? {
        if (!recorder.learns) return null
        if (!learning) return ""
        return CommitRows.prevAfter(recorder.prev(), pieces.map { it.learned ?: return null })
    }

    /** Writes the pieces, then [rest], as one commit; a piece picked while learning was off has no rows. */
    private fun record(rest: CommitRows.Pick? = null) { if (learning && recorder.learns) recorder.commit(pieces.map { it.learned ?: return } + listOfNotNull(rest), nineKeys) }

    // a phrase, emoji or Chaizi character is no word picked: its commit learns nothing
    private fun toLearn(c: Candidate) = if (!learning || !recorder.learns || shownAside(c)) null else entriesAndSlips(c).let { (entries, slips) ->
        recorder.pick(c, readFrom(), input.length - readFrom(), entries, candidates, shownAside, ::text, nineKeys?.anyTaken == true, pieces.isEmpty(), lastRead.ranks[c]?.original == true, slips)
    }

    /** For tests: the kept keys must cover the input a place each. */
    internal val pressedInStep get() = recorder.pressed.length == input.length

    // a phrase is its own candidate: one of the decoder's of the same text is a word
    private val shownAside = { c: Candidate -> c.text in lastRead.aside || NO_WORD in c.words && (c.text in lastRead.placed || c.text in lastRead.common) }

    /** [word], committed by a glide though held back here: learned as picked over [first], asked only where learned. */
    fun heldBack(word: String, reading: String, first: () -> String) = if (learning && recorder.learns && reading.isNotEmpty()) recorder.heldBack(Word(word, reading), first()) else Unit

    // nothing to pick past the page: the key (space, a digit) is the app's
    private fun select(onPage: Int): Snapshot = pager.at(onPage).let { if (it >= 0) pick(it) else snapshot(handled = candidates.isNotEmpty()) }

    private fun turn(to: Int): Snapshot {
        readUntil((to + 1) * pageSize + 1)
        return if (pager.turn(to, candidates.size)) snapshot() else snapshot(handled = candidates.isNotEmpty())
    }

    /** A key not read goes to the app: the input is committed as the first candidate reads it, the rest raw, nothing predicted (`nihao,` gives 你好，). */
    private fun leave(): Snapshot {
        val picked = if (input.isNotEmpty() && !predicting && candidates.isNotEmpty()) pick(0).commit else ""
        val rest = if (input.isNotEmpty()) commitRaw().commit else ""
        // what the record files next follows punctuation, no word
        dropContext()
        recorder.unverify()
        clear()
        return snapshot(commit = picked + rest, handled = false)
    }

    // a table's lookup turned off ([keysBack]): the pieces alone put in, the rest handed back
    private fun commitRaw(keysBack: Boolean = false): Snapshot {
        if (input.isEmpty()) {
            clear()
            return snapshot(handled = false)
        }
        // digits commit the pinyin shown for them, as 搜狗 does; ones no reading took stay as typed
        val shown = lastRead.restShown.filter { it != ' ' && it != SyllableGraph.SEPARATOR }
        // a letter typed as itself (九键) is upper case in the input
        val rest = if (nineKeys == null || shown.isEmpty()) keyed.typedOrSelf(input.substring(readFrom())) else shown
        val text = pieces.joinToString("") { it.text } + if (keysBack) "" else rest
        record(CommitRows.Pick(readFrom(), listOf(Word(rest, "")), intArrayOf(input.length), 'F', "", null).takeUnless { keysBack })
        dropContext()
        recorder.committed(text, learning)
        clear()
        return snapshot(commit = text)
    }

    override fun handBack(): HandedBack {
        val keys = keyed.typedOrSelf(input.substring(readFrom()))
        if (pieces.isNotEmpty()) return HandedBack(commitRaw(keysBack = true).commit, lastCommit, keys)
        clear()
        return HandedBack("", null, keys)
    }

    private fun commit(text: String, words: IntArray): Snapshot {
        context = (context + words).takeLast(2).toIntArray()
        contextLearnable = false
        recent = if (learning) recentTail(recent + text) else ""
        recorder.committed(text, learning)
        if (context.lastOrNull() == NO_WORD) dropContext()
        clear()
        // what may follow a password is no one's business
        if (prediction && learning) lastRead = predict()
        predicting = candidates.isNotEmpty()
        return snapshot(commit = text)
    }

    /** What may follow the last commit; after a word the model never saw anything after, what follows its end (再 of 拟再). */
    private fun predict(): Read {
        // a search field: no 联想
        if (recorder.learnFlags and Commit.NO_PREDICT != 0) return Read().also { predictions.unoffer() }
        val (prev2, prev) = TextWords.previous(context)
        val last = lastEntry
        val tail = { last?.let { predictor.tail(it.text, it.syllables) } ?: NO_WORD }
        val learned = recorder.prev()
        // a model word the decoder read in pieces (好的 as 好 的): the tail finds it
        val own = if (prev == NO_WORD || data.wordIndex.find(learned) != NO_WORD) NO_WORD else ownWord(user, text(prev), learned)
        if (own == NO_WORD) {
            val decoded = if (prev == NO_WORD) "" else text(prev)
            val whole = if (decoded.isEmpty() || learned.length <= decoded.length || !learned.endsWith(decoded)) NO_WORD else data.wordIndex.find(learned)
            predictions.offer(prev2, prev, if (whole == NO_WORD) prev else whole, tail, predictions.continued(recorder.textBefore(), learned), learned)
        } else {
            predictions.offer(NO_WORD, own, tail = { if (prev < data.vocabulary.size) prev else tail() }, continued = predictions.continued(recorder.textBefore(), learned), last = learned)
        }
        return Read(candidates = predictions.offered)
    }

    // a key the app took (an arrow, Enter) moved the cursor or changed the text with no Context following: what was
    // committed here is context no more
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
        // what another input method learned since would not reach the scores the decoder keeps key to key
        decoder.reset()
        nineKeys?.clear()
        recorder.clear()
        pieces.clear()
        pager.reset()
        predicting = false
    }

    private fun readFrom() = pieces.lastOrNull()?.end ?: 0

    private fun read() {
        predicting = false
        pager.reset()
        cursor = cursor.coerceIn(readFrom(), input.length)
        if (input.isEmpty()) {
            lastRead = Read()
            nineKeys?.clear()
            return
        }
        // tap places as the input stands: the 14 keys' letters weighed by them and neighbour keys corrected
        val taps = if (keyed?.letters?.isGrouped == true) recorder.pressed.taps(readFrom(), input.length) else null
        val graph = segmenter.segment(input.substring(readFrom()), taps, recorder.pressed.touches(readFrom(), input.length))
        val (prev2, prev) = TextWords.previous(context + pieces.flatMap { it.words.asList() })
        val decoding = decoder.decode(graph, prev2, prev)
        val after = rankedAfter()
        val sentences = habitOrder.enforce(graph, readFrom(), after, decoding.sentences)
        // a syllable still being typed is no reading to weigh: the refiner's pick there only flashed before the next key
        if (refiner != null && sentences.size > 1 && endsOnSyllable(graph)) {
            unrefined = sentences
            refined = 0
        }
        nineKeys?.forgetBest()
        val decoderFirst = decoding.sentences.firstOrNull() ?: decoding.words.firstOrNull()
        val more = decoding.words.size >= PinyinDecoder.DEFAULT_WORDS
        // level 2 by the decoder's own first, before anything was put in
        val levels = habitOrder.levels(graph, readFrom(), after, decoderFirst, sentences + decoding.words)
        place(Decoded(graph, rerank(sentences), decoding.words, more = more, first = decoderFirst, levels = levels))
        // a phrase goes where its order says only in a list that long: read that far, or it would move as more are read
        lastRead.placed.values.maxOfOrNull { it.order }?.let { readUntil(it + lastRead.placed.size + 1) }
        while (lastRead.unsettled && lastRead.moreWords) readUntil(candidates.size + 1)
        // offered here only: the refiner reordering them would drop a tap on the list shown
        nineKeys?.offer(input, readFrom(), graph)
    }

    private fun place(decoded: Decoded, time: Calendar? = null) {
        val rest = input.length - readFrom()
        val key = input.substring(readFrom())
        // built-ins under their keys only on full pinyin: 双拼's sj, xq spell syllables
        val (phrases, keys) = phraseBook.typed(key, builtIns = segmenter.spellsFullPinyin && builtInPhrases, keyed?.letters, { keyed?.phraseWays(it) }) { keyed?.takenAs(it).orEmpty() }
        // asked once, so what is shown and what is kept agree
        val time = time ?: if (keys.isNotEmpty()) now() else null
        val placed: Map<String, Phrase> = if (time == null) emptyMap() else phrases.offered(keys, time).associate { it.text to it.phrase }
        // phrases, emoji and Chaizi characters are text, no word; placed before the phrases so each is where its order says
        val asText = { text: String -> Candidate(text, rest, 0f, intArrayOf(NO_WORD), intArrayOf(rest)) }
        // a sentence blocked whole is left out of what habits put in too
        val sentenceBlocked = { c: Candidate -> c.words.size > 1 && user?.blocksSentence(c.text) { entries(c, decoded.graph) } == true }
        val ordered = habitOrder.order(decoded.graph, (decoded.sentences + decoded.words).distinctBy { it.text }.filterNot(sentenceBlocked), decoded.sentences, decoded.levels.map { l -> HabitOrder.Level(l.end, l.code, l.prev, l.injected.filterNot(sentenceBlocked), l.habitsHere, l.recorded, l.gate) }, pageSize)
        val read = ordered.list
        // a phrase of order k a place down where a pin leads: the pin stays first
        val withPhrases = { list: List<Candidate> ->
            val pin = ordered.pinned?.takeIf { list.firstOrNull() === it }
            val unpinned = if (pin == null) list else list.drop(1)
            val placed = phrases.place(keys, { checkNotNull(time) }, unpinned, text = { it.text }, all = { it.end == rest }) { asText(it) }
            // a phrase of the pin's text is the pin, shown once
            listOfNotNull(pin) + if (pin == null) placed else placed.filter { it.text != pin.text }
        }
        // what a habit put first or used, and own words, are never held back as coarse
        val kept = { c: Candidate -> ordered.tier(c).let { it != null && it <= Tier.MINE } }
        val readPlaced by lazy { withPhrases(read) }
        val readHeld by lazy { coarseCandidates.held(readPlaced, pageSize, kept) }
        // the emoji go with the reading shown first; built-ins of a word only where the input is not their key
        val wordPhrases = { word: String -> if (builtInPhrases && placed.isEmpty()) phraseBook.builtInsOf(word) { time ?: now() } else emptyList() }
        val withAside = AsideCandidates.place(read, key, AsideBlocks.emoji(emoji, user, read, { it.text }) { entries(it, decoded.graph) }, chaizi.takeUnless { spell }, { it.text }, { it.end == rest }, asText, wordPhrases, first = { coarseCandidates.order(readPlaced, pageSize, readHeld).firstOrNull() }) { ordered.tier(it) == Tier.LIFTED }
        val listed = if (withAside.candidates === read) readPlaced else withPhrases(withAside.candidates)
        val held = if (withAside.candidates === read) readHeld else coarseCandidates.held(listed, pageSize, kept)
        // the common words' replacements first, a pin after them
        val (shownOrder, shownCommon) = owned.first(decoded.graph, key, coarseCandidates.order(listed, pageSize, held), asText)
        // the first reading of all the input shown: preedit, offered syllables and Enter's text go by it; with every one
        // held, the first candidate shown and the rest raw
        val best = read.firstOrNull { it.end == rest && it !in held }
            ?: shownOrder.firstOrNull { it.text !in shownCommon }?.takeIf { decoded.sentences.isNotEmpty() }?.let { throughEnd(it, decoded.graph.end) }
        val shown = if (best == null) PreeditPlaces.Builder().add(keyed.typedOrSelf(decoded.graph.input), readFrom(), input.length) else shows.preedit(decoded.graph, best)
        val picked = pieces.joinToString("") { it.text }
        val restShown = shown.rest
        lastRead = Read(
            candidates = shownOrder,
            decoded = decoded,
            time = time,
            placed = placed,
            common = shownCommon,
            preedit = picked + restShown,
            restShown = restShown,
            places = shown.places(picked.length, input.length),
            unsettled = held.isNotEmpty() && listed.size - held.size < pageSize,
            aside = withAside.aside,
            ranks = ordered.ranks,
        )
    }

    private fun textBefore() = (if (learning) recent else "") + pieces.joinToString("") { it.text }

    private fun rerank(sentences: List<Candidate>): List<Candidate> {
        val reranker = reranker ?: return sentences
        return first(sentences, reranker.pick(textBefore(), sentences.map { it.text }, sentences.map { it.score }))
    }

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
        val shown = pager.of(candidates)
        return Snapshot(
            commit = commit,
            preedit = lastRead.preedit,
            candidates = shown.map { it.text },
            page = pager.page,
            hasPreviousPage = pager.hasPrevious,
            hasNextPage = pager.hasNext(candidates.size) || lastRead.moreWords,
            handled = handled,
            predicting = predicting,
            hints = shown.map(::hint).takeIf { h -> h.any { it.isNotEmpty() } }.orEmpty(),
            // not counted while the decoder has words not yet read
            total = if (lastRead.moreWords) -1 else candidates.size,
            first = pager.from,
            actionable = candidates.isNotEmpty(),
            refines = unrefined != null,
            // zhuyin's in symbols
            syllables = nineKeys?.offered.orEmpty().map { keyed?.shownOffered(it) ?: it },
            syllablesId = nineKeys?.id ?: 0,
            cursor = if (cursor >= input.length) -1 else lastRead.places.preeditAt(cursor),
        )
    }

    // the texts the settings own among the candidates (an inner class, for the session's size)
    private val owned = Owned()

    private inner class Owned {
        // the replacements found per reading, as a later page or a refined pick places it again
        private var foundOf: SyllableGraph? = null
        private var foundFor = ""
        private var foundIn: CommonWords? = null
        private var found: List<CommonWords.Found> = emptyList()

        /** [list] with the replacements whose key is what is left of the input first, those by their text; none for a glide, whose letters are no key typed. */
        fun first(graph: SyllableGraph, key: String, list: List<Candidate>, asText: (String) -> Candidate): Pair<List<Candidate>, Map<String, CommonWords.Found>> {
            if (foundOf !== graph || foundFor != key || foundIn !== commonWords.words) {
                foundOf = graph
                foundFor = key
                foundIn = commonWords.words
                found = if (recorder.pressed.glided(readFrom(), input.length)) {
                    emptyList()
                } else {
                    commonWords.typed(key, segmenter.typing, segmenter::typedAs, keyed?.letters, { keyed?.phraseWays(it) }) { keyed?.takenAs(it).orEmpty() }
                }
            }
            return CommonWordBook.first(found, list, { it.text }, asText) to found.associateBy { it.text }
        }

        fun common(c: Candidate?) = c?.takeIf { NO_WORD in it.words && !predicting }?.let { lastRead.common[it.text] }

        /** [c] deleted from the settings, as a common word's replacement or else (not [pinned]) a custom phrase, and the input reread; null if neither. */
        fun remove(c: Candidate?, pinned: Boolean): Snapshot? {
            common(c)?.let { return readAfter { commonWords.remove(it.words, it.text) } }
            val phrase = c?.takeIf { !pinned && !predicting && NO_WORD in it.words }?.let { lastRead.placed[it.text] } ?: return null
            return readAfter { phraseBook.remove(phrase.key, phrase.value) }
        }

        private fun readAfter(change: () -> Unit): Snapshot {
            change()
            read()
            return snapshot()
        }
    }

    // an inner class, for the session's size
    private val shows = Shows()

    private inner class Shows {
        fun readings(c: Candidate, graph: SyllableGraph): List<Reading>? {
            var from = graph.start
            return c.words.indices.map { i ->
                val word = c.words[i]
                val reading = (if (word == NO_WORD) null else reading(graph, from, c.ends[i], word)) ?: return null
                from = c.ends[i]
                reading
            }
        }

        // the keys of [c] read as their neighbours, each place to the key meant: learned so
        fun slips(graph: SyllableGraph, readings: List<Reading>?): Map<Int, Char> {
            if (!graph.hasOtherReadings) return emptyMap()
            val out = HashMap<Int, Char>()
            readings?.forEach { r -> r.slips.forEach { (at, key) -> out[readFrom() + at] = key } }
            return out
        }

        /** The rest of the preedit for [best], with each place's input position: syllables spaced, or `'` where typed (`ni hao`, `xi'an`). */
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
                    parts += reading.shown.joinToString("")
                    ends += reading.ends.last()
                    raw = false
                } else if (reading != null) {
                    parts += reading.shown
                    ends += reading.ends
                    nineKeys?.note(readFrom() + from, reading.ends.map { readFrom() + it }, reading.syllables)
                    raw = false
                } else if (raw) {
                    parts[parts.size - 1] = parts.last() + keyed.typedOrSelf(graph.text(from, to))
                    ends[ends.size - 1] = to
                } else {
                    parts += keyed.typedOrSelf(graph.text(from, to))
                    ends += to
                    raw = true
                }
                from = to
            }
            // a taken syllable or initial (its letters in the input) shown as its keys is fewer letters than it has: one piece,
            // unless an edit left the cursor inside it, which must show where it is
            val at = cursor - readFrom()
            return joined(graph, parts, ends, readFrom()) { start, end, shown ->
                keysShown && keyed != null && graph.input[start] in 'a'..'z' && shown.length < end - start && at !in start + 1 until end
            }
        }

        private fun shown(graph: SyllableGraph, edge: Int, syllable: Int): String = when {
            keysShown -> keysOf(graph, edge, syllable)
            // digits say nothing: the syllable read (fuzzy too), and of one still being typed as many letters as were typed
            keyed != null -> if (graph.kind(edge) == Kind.SYLLABLE) keyed.spelt(syllable) else keyed.started(syllable, graph.text(edge).length)
            spell && graph.kind(edge) == Kind.SYLLABLE -> Syllables.spelling(syllable)
            // a slipped key shows as meant: `nihap` as ni hao
            graph.isSlip(edge) -> Syllables.spelling(syllable)
            else -> graph.text(edge)
        }

        private fun keysOf(graph: SyllableGraph, edge: Int, syllable: Int): String {
            val text = graph.text(edge)
            val slip = graph.slips(edge)
            val i = if (slip == null) -1 else graph.matches(edge).indexOf(syllable)
            val meant = if (slip == null || i < 0) text else text.replaceRange(slip.at(i), slip.at(i) + 1, slip.meant(i).toString())
            return keyed?.keysShown(meant, syllable) ?: keyed.typedOrSelf(meant)
        }

        fun reading(graph: SyllableGraph, from: Int, to: Int, word: Int): Reading? =
            reading(graph, from, to, word, data.dictionary, user?.takeIf { word >= data.vocabulary.size }) { e, syllable -> shown(graph, e, syllable) }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 5

        /** The nine keys' 分词 key: a separator while something is typed, the app's 1 otherwise. */
        const val NINE_KEYS_SEPARATOR = '1'

        /** The [refiner]'s work at most per [Action.Refine]: one large-model position; a key pressed meanwhile waits for it. */
        const val REFINE_BUDGET = 1
    }
}

// as long as the text the context is read from, so a match is not chance
private const val OVERLAP = 24

// well over what the reranker has room for, so a commit drops only text it no longer reads
private const val RECENT = 128

/** The last [RECENT] chars of [text], not starting in the middle of a pair. */
internal fun recentTail(text: String): String {
    if (text.length <= RECENT) return text
    val from = text.length - RECENT
    return text.substring(if (Character.isLowSurrogate(text[from])) from + 1 else from)
}

/**
 * Whether the input's longest last syllable is whole: zhong, not zho, nor an initial. n, m, ng, r, a, o and e
 * at the end are likelier the start of a longer one. Read in whole syllables: 小鹤 `nih` ends on h begun.
 */
fun endsOnSyllable(graph: SyllableGraph): Boolean {
    val whole = graph.withoutOtherReadings()
    val last = (0 until whole.edgeCount).filter { whole.to(it) == whole.end }
    val from = last.minOfOrNull { whole.from(it) } ?: return false
    val longest = last.filter { whole.from(it) == from }.map { whole.kind(it) }
    return Kind.SYLLABLE in longest && Kind.INITIAL !in longest
}

/**
 * A word often misread ([PinyinData.misreadings]) typed by a misreading: the true reading with tones (般若 banruo:
 * bō rě), else "". Syllables may be typed by their start, the last ones not at all, but the typing must not fit the
 * true reading too. Labels only, ranks nothing.
 */
private fun misreading(data: PinyinData, text: String, typed: String): String {
    val word = data.misreadings[text] ?: return ""
    if (typed.isEmpty() || fits(word.typed, typed)) return ""
    return if (word.misread.any { fits(it, typed) }) word.reading else ""
}

// whether [typed] is [spelling]'s syllables in order, each by a non-empty start; later ones may be untyped
private fun fits(spelling: String, typed: String): Boolean {
    val syllables = spelling.split('\'')
    var reached = BooleanArray(typed.length + 1).also { it[0] = true }
    for (syllable in syllables) {
        if (reached[typed.length]) return true
        val next = BooleanArray(typed.length + 1)
        for (i in typed.indices) {
            if (!reached[i]) continue
            var n = 0
            while (n < syllable.length && i + n < typed.length && typed[i + n] == syllable[n]) {
                n++
                next[i + n] = true
            }
        }
        reached = next
    }
    return reached[typed.length]
}

private fun throughEnd(c: Candidate, end: Int) = if (c.end >= end) c else Candidate(c.text, end, c.score, c.words + NO_WORD, c.ends + end)

private fun first(sentences: List<Candidate>, picked: Int) =
    if (picked == 0) sentences else listOf(sentences[picked]) + sentences.filterIndexed { i, _ -> i != picked }

/** [parts] of [graph]'s input joined for the preedit; [base] where the graph's input starts in the session's. */
private fun joined(graph: SyllableGraph, parts: List<String>, ends: List<Int>, base: Int, whole: (start: Int, end: Int, shown: String) -> Boolean = { _, _, _ -> false }): PreeditPlaces.Builder {
    val out = PreeditPlaces.Builder().add(graph.input.substring(0, graph.start), base, base + graph.start)
    for (k in parts.indices) {
        val start = if (k == 0) graph.start else ends[k - 1]
        if (k > 0 && graph.input[start - 1] == SyllableGraph.SEPARATOR) {
            out.add(SyllableGraph.SEPARATOR.toString(), base + start - 1, base + start)
        } else if (k > 0) {
            out.add(" ", base + start, base + start)
        }
        // a separator ending the part shows as the next joiner, or, last, not at all; a zhuyin tone as its mark
        val last = graph.input[ends[k] - 1]
        val end = if (last == SyllableGraph.SEPARATOR) ends[k] - 1 else ends[k]
        val tone = ZhuyinKeys.toneOf(last)
        out.add(if (tone > 0) parts[k] + ZhuyinKeys.MARKS[tone - 1] else parts[k], base + start, base + end, whole(start, end, parts[k]))
    }
    return out
}

// the user's own word [learned] that the decoder's last word [decoded] ends, made of several (你好 of 你 好): 联想
// goes on from it. NO_WORD where the decoder's is it
private fun ownWord(user: UserModel?, decoded: String, learned: String): Int =
    if (learned.length <= decoded.length || !learned.endsWith(decoded)) NO_WORD else user?.idsOfText(learned)?.firstOrNull() ?: NO_WORD

private fun isOneHan(text: String) = text.codePointCount(0, text.length) == 1 && Character.isIdeographic(text.codePointAt(0))

private fun separates(c: Char, nineKeysSeparate: Boolean) = c == SyllableGraph.SEPARATOR || c == PinyinSession.NINE_KEYS_SEPARATOR && nineKeysSeparate

/** What [c] types at [cursor] on [keyed]: 九键双拼's 1 sends 微软's `;` as a final where the key before makes a syllable with it, else the separator (nothing with no key before); any other key itself. */
internal fun semicolonRead(c: Char, keyed: KeyedSegmenter?, input: CharSequence, from: Int, cursor: Int): Char? {
    if (c != NineKeyLetters.NINE_SP_SEMICOLON || keyed?.letters != NineKeyLetters.NINE_SP) return c
    val lead = leadOf(input, from, cursor, keyed::isKey)
    return when {
        lead != null && keyed.endsSyllable(lead, c) -> c
        cursor == from -> null
        else -> SyllableGraph.SEPARATOR
    }
}

/** The key before [cursor] that the next key would end a syllable with (an even count of keys since a separator, a take or [from]), or a taken initial; null where an initial goes. */
private fun leadOf(input: CharSequence, from: Int, cursor: Int, isKey: (Char) -> Boolean): String? {
    var i = cursor
    while (i > from && isKey(input[i - 1])) i--
    var start = i - 1
    if (start > from && input[start] == SyllableGraph.SEPARATOR && input[start - 1] in 'a'..'z') {
        while (start > from && input[start - 1] in 'a'..'z') start--
    }
    val taken = if (start < i - 1) input.substring(start, i - 1).takeIf { Syllables.id(it) < 0 || Syllables.split(it) == null } else null
    val keys = cursor - i + (if (taken != null) 1 else 0)
    return when {
        keys % 2 == 0 -> null
        cursor > i -> input[cursor - 1].toString()
        else -> taken
    }
}

// a grouped key's code never reaches the field
private fun KeyedSegmenter?.typedOrSelf(text: String) = this?.asTyped(text) ?: text
