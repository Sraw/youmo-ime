/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
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
 * The words committed stay the context of what is typed next, as they would be read together,
 * until [Action.Reset] or text committed as typed; [Action.Context] makes the words the app's text
 * ends with the context, as if committed (see [TextWords]).
 *
 * With a [user] model, what is committed is learned: its words and their order. Where the user
 * corrected the engine, putting the text together from pieces or picking a reading of several
 * words other than the first, the text is learned as one word, found whole the next time. A
 * prediction picked is not learned, as nothing says how it reads; text kept as typed is not either.
 *
 * With a [reranker], the best readings of the input are weighed again by how they read as a
 * sentence after the text committed before, by the same context, or after the text the host
 * says is before the cursor ([Action.Context]). With a [refiner] too, a better and slower one,
 * the readings are weighed again by it while the user pauses ([Action.Refine]): the [reranker]
 * decides as each key is typed, within a few milliseconds, the [refiner] then in slices of
 * [REFINE_BUDGET] between them, till the next key.
 *
 * @param spell show each syllable spelt out, not as typed: for 双拼, whose keys say little
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
    /** Offered where their order says when what is left of the input is their key. */
    private val phrases: CustomPhrases = CustomPhrases.EMPTY,
    /** The time a dynamic phrase is filled in with. */
    private val now: () -> Calendar = { Calendar.getInstance() },
    private val refiner: SentenceRefiner? = null,
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

    private val decoder = PinyinDecoder(
        data.dictionary,
        data.vocabulary,
        if (user == null) WordScorer.of(data.model) else UserScorer(data.model, user),
        penalties,
        user = user,
    )
    private val predictor = Predictor(data.model, data.vocabulary, data.dictionary)
    private val textWords by lazy { TextWords(data.model, data.wordIndex) }

    private val input = StringBuilder()
    private val pieces = ArrayList<Piece>()
    private var context = IntArray(0) // the words committed last, at most two
    private var lastEntry: Entry? = null // the last of them, if known how it reads
    private var recent = "" // the text they end, for the reranker
    private var graph: SyllableGraph? = null // of the input read last

    private var candidates: List<Candidate> = emptyList()
    private var decoderWords: List<Candidate> = emptyList() // the decoder's words, after its sentences
    private var unrefined: List<Candidate>? = null // the decoder's sentences, till the refiner picks one
    private var refined = 0 // slices of refining spent on them
    private var decoderFirst: Candidate? = null // the first before the reranker and phrases moved it
    private var preedit = ""
    private var page = 0
    private var predicting = false

    override var learning = true

    override fun apply(action: Action): Snapshot {
        // what the refiner weighs is the input as last read, on the first page
        if (action != Action.Refine) unrefined = null
        return act(action)
    }

    private fun act(action: Action): Snapshot = when (action) {
        Action.Refine -> refine()
        is Action.Key -> type(action.char)
        Action.Backspace -> backspace()
        // nothing to pick past the page: the key (space, a digit) is the app's
        is Action.Select -> if (action.index in 0 until pageSize) pick(page * pageSize + action.index) else snapshot(handled = candidates.isNotEmpty())
        is Action.Pick -> pick(action.index)
        is Action.Forget -> forget(action.index)
        Action.NextPage -> turn(page + 1)
        Action.PreviousPage -> turn(page - 1)
        Action.CommitRaw -> commitRaw()
        Action.Reset -> {
            dropContext()
            clear()
            snapshot()
        }
        is Action.Context -> {
            // the text still ends with what was committed here: the cursor did not go anywhere
            // (an editor reporting it where the service did not expect it), and the words the
            // user picked say more than the model's reading of the text. The host reads less than
            // is kept, so its text may be the end of it, long enough not to be so by chance
            val before = action.before
            val same = before.endsWith(recent) || before.length >= OVERLAP && recent.endsWith(before)
            if (learning && recent.isNotEmpty() && same) {
                clear()
                return snapshot()
            }
            dropContext()
            clear()
            // what the app has is kept no longer than the input, and not at all where the user
            // learns nothing, as what they commit; text ending in no word (a space, a letter) is
            // no context for the reranker either, as after text committed as typed
            if (learning) {
                context = textWords.lastTwo(before)
                if (context.isNotEmpty()) recent = tail(before)
            }
            snapshot()
        }
    }

    private fun type(c: Char): Snapshot {
        if (!reads(c)) return leave()
        input.append(c)
        read()
        return snapshot()
    }

    private fun backspace(): Snapshot {
        if (input.isEmpty()) {
            // the app deletes; a prediction shown goes away
            clear()
            return snapshot(handled = false)
        }
        input.setLength(input.length - 1)
        while (pieces.isNotEmpty() && pieces.last().end >= input.length) pieces.removeAt(pieces.size - 1)
        read()
        return snapshot()
    }

    override fun reads(c: Char): Boolean = segmenter.reads(c) || (c == SyllableGraph.SEPARATOR && input.isNotEmpty())

    override fun candidates(from: Int, count: Int): List<Choice> =
        candidates.subList(minOf(from, candidates.size), minOf(from + count, candidates.size)).map { Choice(it.text) }

    private fun pick(index: Int): Snapshot {
        val c = candidates.getOrNull(index) ?: return snapshot(handled = candidates.isNotEmpty())
        if (predicting) {
            lastEntry = null
            return commit(c.text, c.words)
        }
        pieces += Piece(c.text, c.words, readFrom() + c.end, if (user == null) null else entries(c), c !== decoderFirst)
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

    /**
     * Forgets what the user model learned of the words of the candidate at [index], then reads
     * the input again, on the page shown if it still has candidates. Text kept as typed and
     * phrases hold no words to forget.
     */
    private fun forget(index: Int): Snapshot {
        val c = candidates.getOrNull(index)
        val words = if (user == null || predicting || c == null) null else entries(c)
        if (words == null) return snapshot()
        user?.forget(words)
        // or the next pick learns it again, as the word before it
        if (lastEntry in words) dropContext()
        decoder.reset()
        val shown = page
        read()
        if (shown * pageSize < candidates.size) page = shown
        return snapshot()
    }

    /** How each word of [c], of the input read last, reads; null if some is text kept as typed. */
    private fun entries(c: Candidate): List<Entry>? {
        val graph = graph ?: return null
        var from = graph.start
        return c.words.indices.map { i ->
            val word = c.words[i]
            val reading = (if (word == NO_WORD) null else reading(graph, from, c.ends[i], word)) ?: return null
            from = c.ends[i]
            Entry(text(word), reading.syllables)
        }
    }

    private fun text(word: Int) = if (word < data.vocabulary.size || user == null) data.vocabulary.word(word) else user.text(word)

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

    private fun turn(to: Int): Snapshot {
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
        val text = pieces.joinToString("") { it.text } + input.substring(readFrom())
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
        candidates = if (prediction && learning) predict() else emptyList()
        predicting = candidates.isNotEmpty()
        return snapshot(commit = text)
    }

    /**
     * What may follow the words committed last. After one the model never saw (the user's own,
     * put together from pieces, or a dictionary word it lacks) that is what follows its end, 再
     * of 拟再: the model's context would be its unknown word, followed by anything.
     */
    private fun predict(): List<Candidate> {
        val (prev2, prev) = lastTwo(context)
        val last = lastEntry
        if (prev == NO_WORD || prev < data.model.vocabularySize || last == null) return predictor.predict(prev2, prev)
        return predictor.predict(NO_WORD, predictor.tail(last.text, last.syllables))
    }

    private fun dropContext() {
        context = IntArray(0)
        lastEntry = null
        recent = ""
    }

    private fun clear() {
        input.setLength(0)
        graph = null
        pieces.clear()
        candidates = emptyList()
        decoderWords = emptyList()
        preedit = ""
        page = 0
        predicting = false
    }

    private fun readFrom() = pieces.lastOrNull()?.end ?: 0

    /** Reads the input after the pieces picked. */
    private fun read() {
        predicting = false
        page = 0
        if (input.isEmpty()) {
            candidates = emptyList()
            decoderWords = emptyList()
            preedit = ""
            graph = null
            return
        }
        val graph = segmenter.segment(input.substring(readFrom()))
        this.graph = graph
        val (prev2, prev) = lastTwo(context + pieces.flatMap { it.words.asList() })
        val decoding = decoder.decode(graph, prev2, prev)
        decoderFirst = decoding.sentences.firstOrNull() ?: decoding.words.firstOrNull()
        decoderWords = decoding.words
        if (refiner != null && decoding.sentences.size > 1) {
            unrefined = decoding.sentences
            refined = 0
        }
        place(graph, rerank(decoding.sentences))
    }

    /** Lists [sentences], then the decoder's words, with the phrases the input has among them. */
    private fun place(graph: SyllableGraph, sentences: List<Candidate>) {
        val rest = input.length - readFrom()
        // a phrase is text, no word: nothing to learn, and what follows it reads after nothing
        candidates = phrases.place(
            input.substring(readFrom()), now, (sentences + decoderWords).distinctBy { it.text },
            text = { it.text }, all = { it.end == rest },
        ) { Candidate(it, rest, 0f, intArrayOf(NO_WORD), intArrayOf(rest)) }
        val best = sentences.firstOrNull()
        preedit = pieces.joinToString("") { it.text } + (if (best == null) graph.input else preedit(graph, best))
    }

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
        if (refined++ >= REFINE_SLICES) {
            unrefined = null
            return snapshot()
        }
        val picked = refiner!!.refine(textBefore(), sentences.map { it.text }, sentences.map { it.score }, REFINE_BUDGET)
            ?: return snapshot()
        unrefined = null
        if (picked != SentenceRefiner.NONE) place(graph!!, first(sentences, picked))
        return snapshot()
    }

    private fun lastTwo(words: IntArray): Pair<Int, Int> {
        val n = words.size
        val prev = if (n >= 1) words[n - 1] else NO_WORD
        val prev2 = if (n >= 2 && prev != NO_WORD) words[n - 2] else NO_WORD
        return prev2 to prev
    }

    private fun snapshot(commit: String = "", handled: Boolean = true): Snapshot {
        val from = page * pageSize
        val shown = candidates.subList(minOf(from, candidates.size), minOf(from + pageSize, candidates.size))
        return Snapshot(
            commit = commit,
            preedit = preedit,
            candidates = shown.map { it.text },
            page = page,
            hasPreviousPage = page > 0,
            hasNextPage = from + pageSize < candidates.size,
            handled = handled,
            predicting = predicting,
            total = candidates.size,
            first = from,
            forgets = user != null && !predicting && candidates.isNotEmpty(),
            refines = unrefined != null,
        )
    }

    /**
     * [best] as the syllables it reads, a space between them, or `'` where one was typed: `ni hao`,
     * `xi'an`; text kept as typed shows as typed.
     */
    private fun preedit(graph: SyllableGraph, best: Candidate): String {
        val parts = ArrayList<String>()
        val ends = ArrayList<Int>()
        var from = graph.start
        var raw = false
        for (i in best.words.indices) {
            val to = best.ends[i]
            val word = best.words[i]
            val reading = if (word == NO_WORD) null else reading(graph, from, to, word)
            if (reading != null) {
                parts += reading.shown
                ends += reading.ends
                raw = false
            } else if (raw) {
                parts[parts.size - 1] = parts.last() + graph.text(from, to)
                ends[ends.size - 1] = to
            } else {
                parts += graph.text(from, to)
                ends += to
                raw = true
            }
            from = to
        }
        val out = StringBuilder(graph.input.substring(0, graph.start))
        for (k in parts.indices) {
            if (k > 0) out.append(if (graph.input[ends[k - 1] - 1] == SyllableGraph.SEPARATOR) SyllableGraph.SEPARATOR else ' ')
            out.append(parts[k])
        }
        return out.toString()
    }

    /** How [word] reads from [from] to [to] of [graph]: what each syllable shows, where it ends, and its id. */
    private class Reading(val shown: List<String>, val ends: List<Int>, val syllables: IntArray)

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
                    shown += if (spell && graph.kind(e) == Kind.SYLLABLE) Syllables.spelling(m.syllable(i)) else graph.text(e)
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
        /**
         * The [refiner]'s work at most per [Action.Refine], in [Reranker]'s units: one position of
         * the large model, a few milliseconds on a phone. A key pressed meanwhile waits for it.
         */
        const val REFINE_BUDGET = 1
        /**
         * Slices of refining at most per input read; past them its order stands. On the evaluation
         * set a pause takes 25 at the median, 60 at the 90th percentile and 137 at most: a reorder
         * half a second after the key would move what the user is already reaching for.
         */
        const val REFINE_SLICES = 64
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
    }
}
