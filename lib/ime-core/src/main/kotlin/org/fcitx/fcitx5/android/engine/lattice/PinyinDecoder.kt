/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import org.fcitx.fcitx5.android.engine.data.Vocabulary
import org.fcitx.fcitx5.android.engine.lattice.Lattice.Companion.ALONE
import org.fcitx.fcitx5.android.engine.lattice.Lattice.Companion.SLIPPED
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import java.util.function.IntPredicate

/**
 * Reads a [SyllableGraph] as Chinese: every dictionary word along every path is a lattice arc; a beam
 * search keeps the [beam] best word sequences ending at each position, scored by [scorer] plus [penalties].
 *
 * Sequences are not merged on their last two words as exact trigram Viterbi would: that finds the same
 * best sentence but leaves runners-up differing only at the end, while users need ones differing anywhere
 * (在/再 early on). Whole sequences give a real N-best for candidates and rerankers, at the price of one
 * text via different words (你好, 你 + 好) taking several beam places.
 *
 * Each decode builds on the last: beams before the first position a changed arc ends at are kept, so a key
 * at the end of long input searches only its last syllables. Without this, nearly all of it [scorer]
 * time, a key's cost would grow with input length.
 *
 * Reuses its buffers between calls: not thread-safe, and meant to live as long as the engine.
 */
class PinyinDecoder(
    dictionary: PinyinDictionary,
    private val vocabulary: Vocabulary,
    private val scorer: WordScorer,
    penalties: Penalties = Penalties(),
    private val beam: Int = DEFAULT_BEAM,
    /** Per reading past the first word, most probable first; the first word gets them all. */
    wordsPerReading: Int = DEFAULT_WORDS_PER_READING,
    /** Any change to them (learned, added, blocked, imported) needs a [reset]. */
    private val user: UserWords? = null,
    /** lookAhead's longer words per [dictionary] node, lazily: shared by decoders of the same dictionary and scores. */
    extensions: () -> Array<IntArray?> = { arrayOfNulls<IntArray>(dictionary.nodeCount) },
    /**
     * Whether words reading all the input go first; else all by score. Not on the nine keys: there digits
     * read as many meaningless whole-input words, and a syllable picked in the column narrows them anyway.
     */
    private val wholeFirst: Boolean = true,
    /** Dictionary words ranked [CoarseWords.PENALTY] lower. */
    coarse: CoarseWords = CoarseWords.NONE,
    /** How text kept as typed shows: on the nine keys, [T9Segmenter.asTyped][org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter.asTyped]. */
    private val asTyped: (String) -> String = { it },
    /** Scores for a word reading the whole input alone, if not [scorer]'s: same contexts, no user counts (LearningUse.Counts.MULTI). */
    private val whole: WordScorer? = null,
    /**
     * Scores for a word over a syllable typed short (initial, partial, or whole read as the start of a
     * longer: 简拼), if not [scorer]'s: no user counts, same contexts, so full typing never moves short typing.
     */
    uncounted: WordScorer? = null,
    /**
     * Whether a character read as a syllable may have tone 1-5 (ReadingTable.allows): a word with a
     * character lacking the typed tone costs [Penalties.tone], kept. Null, or no tone typed (all but
     * zhuyin keyboards): never asked.
     */
    toneAllows: ((codePoint: Int, syllable: Int, tone: Int) -> Boolean)? = null,
    /**
     * False: over keys alone each match is looked up as everywhere else, which LatticeBuilder.collectTyped must equal
     * (tests only). Fixed for the decoder's life: arcs kept from an earlier decode were walked the other way.
     */
    internal val walksTyped: Boolean = true,
) {
    init {
        require(beam >= 1 && wordsPerReading >= 1) { "beam $beam, words per reading $wordsPerReading" }
    }

    private val lattice = LatticeBuilder(dictionary, vocabulary, scorer, penalties, wordsPerReading, user, extensions, coarse, toneAllows, walksTyped)
    private val search = BeamSearch(beam, scorer, whole, uncounted)
    // one instance taking ints: no lambda per decode, no boxing per word
    private val sentenceText = BeamSearch.Text { graph, word, from, to -> if (word == NO_WORD) asTyped(graph.text(from, to)) else text(word) }

    // the beams came from these arcs, or from nothing if lastEnd is -1
    private var lastEnd = -1

    /** For tests: positions the last decode collected from anew. */
    internal val collectedAnew get() = lattice.collectedAnew

    /** For tests: positions it continued from the last decode's steps, earlier arcs kept. */
    internal val collectedOn get() = lattice.collectedOn

    /**
     * Deterministic work counts of the last decode, so a test can bound a key where time would not hold
     * still. Each guards one saving: [steps] incremental collecting, [scored] the search, [firstScored]
     * the kept first-word scores; [arcs] is the size of what they work on.
     */
    class Work internal constructor(
        /** Including those kept from the last decode. */
        val arcs: Int,
        /** Trie steps taken anew, one syllable each. */
        val steps: Int,
        /** Words scored after a sequence in the search. */
        val scored: Int,
        /** Arcs from the start scored anew for the first words, not kept. */
        val firstScored: Int,
    )

    val work: Work get() = Work(lattice.arcs.size, lattice.steps, search.scored, firstAnew)

    private var firstAnew = 0

    // words a pass over the arcs has taken, each once ([startWords], [arcsBetween], [rankedWords])
    private val seen = SeenWords()
    private var lastStart = 0
    private var lastPrev2 = NO_WORD
    private var lastPrev = NO_WORD

    // firstWords' scores for the arcs from the start, for the next decode to keep shared arcs'; count valid,
    // 0 until firstWords is done
    private var firstScores = FloatArray(0)
    private var firstScored = 0

    // those arcs as firstWords ranked them, of rankedGraph (null until done): further words read with no decode
    private var firstRanked = LongArray(0)
    private var rankedGraph: SyllableGraph? = null

    /**
     * @param prev2 the word before [prev] in text already committed, or [NO_WORD]
     * @param prev the last word committed, or [NO_WORD]
     * @param sentences how many readings of the whole input to return at most
     * @param words how many starting words to return at most; where the graph reads 双拼 keys alone and [words]
     * reaches [DEFAULT_WORDS], up to [TYPED_SLOTS] more: the user's typed words so read, right after the first
     * [DEFAULT_WORDS] (rankedWords)
     */
    fun decode(
        graph: SyllableGraph,
        prev2: Int = NO_WORD,
        prev: Int = NO_WORD,
        sentences: Int = DEFAULT_SENTENCES,
        words: Int = DEFAULT_WORDS,
    ): Decoding {
        if (graph.start == graph.end) {
            reset()
            // nothing done yet: [work] is not the last decode's
            lattice.clearWork()
            search.clearWork()
            firstAnew = 0
            return Decoding(emptyList(), emptyList())
        }
        // the last decode is built on only in the same context: see search and firstWords
        val last = if (graph.start == lastStart && prev2 == lastPrev2 && prev == lastPrev) lastEnd else -1
        val firstKept = if (last >= 0) firstScored else 0
        // until the search is done: a throw would leave beams and arcs matching nothing
        lastEnd = -1
        firstScored = 0
        rankedGraph = null
        lattice.build(graph)
        search.search(lattice.arcs, lattice.lastArcs, graph, prev2, prev, last)
        lastEnd = graph.end
        lastStart = graph.start
        lastPrev2 = prev2
        lastPrev = prev
        return Decoding(sentences(graph, sentences), firstWords(graph, prev2, prev, words, firstKept, last))
    }

    /**
     * Forgets the last decode so the next starts from scratch: needed when [scorer]'s scores or [user]'s
     * words change (owned, added, blocked, imported). Arcs carried key to key hold the user's words as they
     * were: a missed reset shows a removed or blocked word, or hides a new one, till the input restarts.
     */
    fun reset() {
        lastEnd = -1
        lattice.reset()
    }

    /**
     * The first [words] starting words as [decode] gives them, where it gave fewer: continued from its
     * ranking if the last decode was of [graph] in this context, else decoded again. Plus up to
     * [TYPED_SLOTS] as [decode]; a shorter list is a prefix of a longer one.
     */
    fun words(graph: SyllableGraph, prev2: Int = NO_WORD, prev: Int = NO_WORD, words: Int): List<Candidate> {
        val ranked = graph === rankedGraph && lastEnd == graph.end && prev2 == lastPrev2 && prev == lastPrev
        return if (ranked) rankedWords(graph, words) else decode(graph, prev2, prev, words = words).words
    }

    // arc [a] is a word ending at [to], not over a slipped key where [holdsSlips]
    private fun takes(arcs: Lattice, a: Int, to: Int, holdsSlips: Boolean): Boolean =
        arcs.to[a] == to && arcs.word[a] != NO_WORD && !(holdsSlips && arcs.flags[a] and SLIPPED != 0)

    /**
     * The last decode's words from the start of [graph] to [to] that [take] accepts, each once, scored by
     * arc cost alone: for injecting user words a list may lack. Every word from the start is an arc, with
     * no limit. [holdsSlips]: none over a slipped key, for a code that [SyllableGraph.readsWhole] where the
     * graph has slips (HabitOrder); a lone key is no slip.
     */
    fun startWords(graph: SyllableGraph, to: Int, take: IntPredicate, holdsSlips: Boolean = false): List<Candidate> {
        if (lastEnd != graph.end || lastStart != graph.start) return emptyList()
        val arcs = lattice.arcs
        val out = ArrayList<Candidate>()
        seen.clear()
        for (a in arcs.start[graph.start] until arcs.start[graph.start + 1]) {
            val word = arcs.word[a]
            if (!takes(arcs, a, to, holdsSlips)) continue
            if (take.test(word) && seen.add(word)) out += Candidate(text(word), to, arcs.cost[a], intArrayOf(word), intArrayOf(to))
        }
        return out
    }

    /** Up to [limit] of the last decode's sentences, each text once: more than [decode] gave, so substituted words still leave enough. Backtracking only. */
    fun moreSentences(graph: SyllableGraph, limit: Int = beam): List<Candidate> =
        if (lastEnd != graph.end || lastStart != graph.start) emptyList() else sentences(graph, limit)

    /**
     * The last decode's words from [from] to [to], each once, as [startWords]: for an added word being the
     * only one. Read off the lattice, no search. [holdsSlips] as for [startWords]; [holdsAlone]: none over a
     * 双拼 key read alone where the keys read whole, else a typed word read so would displace the added one.
     */
    fun arcsBetween(graph: SyllableGraph, from: Int, to: Int, holdsSlips: Boolean = false, holdsAlone: Boolean = false): List<Candidate> {
        if (from !in graph.start until graph.end || lastEnd != graph.end || lastStart != graph.start) return emptyList()
        val arcs = lattice.arcs
        val out = ArrayList<Candidate>()
        seen.clear()
        for (a in arcs.start[from] until arcs.start[from + 1]) {
            val word = arcs.word[a]
            if (!takes(arcs, a, to, holdsSlips) || holdsAlone && arcs.flags[a] and ALONE != 0) continue
            if (seen.add(word)) out += Candidate(text(word), to - from, arcs.cost[a], intArrayOf(word), intArrayOf(to))
        }
        return out
    }

    private fun sentences(graph: SyllableGraph, limit: Int): List<Candidate> = search.sentences(graph, limit, sentenceText)

    private fun text(word: Int) = if (word < vocabulary.size || user == null) vocabulary.word(word) else user.text(word)

    /**
     * Starting words, each once, best first; with [wholeFirst], whole-input ones first. An initial may start
     * thousands (`s` is every sh and s syllable), so text is made only for those returned. Arcs from the
     * start as in the last decode, of the [kept] it scored, keep their scores: a key changes arcs near the end.
     */
    private fun firstWords(graph: SyllableGraph, prev2: Int, prev: Int, limit: Int, kept: Int, last: Int): List<Candidate> {
        val arcs = lattice.arcs
        val lastArcs = lattice.lastArcs
        val from = arcs.start[graph.start]
        val until = arcs.start[graph.start + 1]
        val context = scorer.context(prev2, prev)
        val trigrams = scorer.trigrams(context)
        // the arcs from the start are the last decode's and none reads to the end then or now: its scores and
        // order stand (thousands of arcs per 双拼 key at the end of long input)
        if (firstUntouched(graph, from, until, kept, last)) {
            firstScored = kept
            firstAnew = 0
            rankedGraph = graph
            return rankedWords(graph, limit)
        }
        if (firstScores.size < until - from) firstScores = firstScores.copyOf(until - from)
        val scores = firstScores
        var same = 0
        if (kept > 0) {
            val lastFrom = lastArcs.start[graph.start]
            // with [whole], an arc reading all the input then or now is rescored
            val sameUntil = if (whole == null) Int.MAX_VALUE else minOf(last, graph.end)
            while (same < minOf(kept, until - from) && lastArcs.same(lastFrom + same, arcs, from + same) && arcs.to[from + same] < sameUntil) same++
        }
        // score then arc in one sortable long: best first, ties in arc order
        val order = LongArray(until - from) { i ->
            val a = from + i
            val word = arcs.word[a]
            val scoring = search.scoring(arcs, a, arcs.to[a] == graph.end)
            if (i >= same) scores[i] = if (word == NO_WORD) Float.NEGATIVE_INFINITY else arcs.cost[a] + scoring.scoreAfter(context, trigrams, arcs.scored[a])
            (sortable(-scores[i]).toLong() shl Int.SIZE_BITS) or a.toLong()
        }
        firstScored = until - from
        firstAnew = until - from - same
        order.sort()
        // whole-input words first, then the rest by score: by score alone common characters of a part (几 家 加
        // of jiao) buried a rarer whole one (觉). Not longest first: a word picked piece by piece is mostly short
        firstRanked = if (!wholeFirst) order else LongArray(order.size).also { ranked ->
            var at = 0
            for (k in order) if (arcs.to[k.toInt()] == graph.end) ranked[at++] = k
            for (k in order) if (arcs.to[k.toInt()] != graph.end) ranked[at++] = k
        }
        rankedGraph = graph
        return rankedWords(graph, limit)
    }

    // whether arcs [from, until) from the start are the [kept] the last decode ranked, none reading to the end then or now
    private fun firstUntouched(graph: SyllableGraph, from: Int, until: Int, kept: Int, last: Int): Boolean =
        kept == until - from && firstRanked.size == kept && lattice.arcs.copiedAt(graph.start) && lattice.lastArcs.start[graph.start] == from &&
            (kept == 0 || lattice.arcs.to[until - 1] < minOf(last, graph.end))

    /**
     * The first [limit] words, each once, of the start arcs as [firstWords] ranked them. Where the graph
     * reads keys alone and [limit] reaches [DEFAULT_WORDS], up to [TYPED_SLOTS] of the user's typed whole-input,
     * slip-free words go right after the first [DEFAULT_WORDS]: on grouped keyboards a code of initials reads
     * hundreds of syllables' words that crowd out the meant one. There whatever [limit]: after the [limit]th,
     * a longer list read on (PinyinSession.readUntil) would move them.
     */
    private fun rankedWords(graph: SyllableGraph, limit: Int): List<Candidate> {
        val arcs = lattice.arcs
        val from = arcs.start[graph.start]
        var next = 0
        var kept = 0
        val out = ArrayList<Candidate>()
        seen.clear()
        fun rankTo(n: Int) {
            while (out.size - kept < n && next < firstRanked.size) {
                val a = firstRanked[next++].toInt()
                val word = arcs.word[a]
                if (word != NO_WORD && seen.add(word)) {
                    out += Candidate(text(word), arcs.to[a], firstScores[a - from], intArrayOf(word), intArrayOf(arcs.to[a]))
                }
            }
        }
        rankTo(minOf(limit, DEFAULT_WORDS))
        if (graph.hasAbbreviations && limit >= DEFAULT_WORDS) {
            // past the ranked words, which rankTo continues: those taken here are seen and skipped there
            var at = next
            while (kept < TYPED_SLOTS && at < firstRanked.size) {
                val a = firstRanked[at++].toInt()
                val to = arcs.to[a]
                // with [wholeFirst] whole-input words come first: stop at the first that is not. Grouped keyboards (not
                // wholeFirst) check every arc left: its end and flags each
                if (wholeFirst && to != graph.end) break
                val word = arcs.word[a]
                val alone = word != NO_WORD && to == graph.end && arcs.flags[a] and (ALONE or SLIPPED) == ALONE
                if (alone && user?.typed(word) == true && seen.add(word)) {
                    out += Candidate(text(word), to, firstScores[a - from], intArrayOf(word), intArrayOf(to))
                    kept++
                }
            }
        }
        rankTo(limit)
        return out
    }

    companion object {
        const val DEFAULT_BEAM = 16

        // children walked, not searched, up to this many per match: a search reads about log2 of them; Reading's walk the same
        internal const val WALKED_PER_MATCH = 2
        const val DEFAULT_WORDS = 64
        // user-typed lone-key words kept past the words asked for (rankedWords); 8 till measured on the 6T
        internal const val TYPED_SLOTS = 8
        // 16 words are as accurate as 256 at a third of the time; a beam of 64 gains little at four times the time
        const val DEFAULT_WORDS_PER_READING = 16
        const val DEFAULT_SENTENCES = 5
    }
}

/**
 * Input read as [text] from the start up to [end]; [words] are vocabulary ids ([NO_WORD] for text kept
 * as typed), [ends] where each ends in the input. A prediction reads no input: its ends are 0.
 */
class Candidate internal constructor(
    val text: String,
    val end: Int,
    val score: Float,
    val words: IntArray,
    val ends: IntArray,
) {
    override fun toString() = "$text($end, $score)"
}

/** [sentences] read the whole input; [words] are every word it may start with, for piece-by-piece picking. Best first. */
class Decoding internal constructor(val sentences: List<Candidate>, val words: List<Candidate>)

/** Bits that order as the floats do. */
private fun sortable(value: Float): Int {
    val bits = java.lang.Float.floatToIntBits(value)
    return if (bits < 0) bits xor Int.MAX_VALUE else bits
}
