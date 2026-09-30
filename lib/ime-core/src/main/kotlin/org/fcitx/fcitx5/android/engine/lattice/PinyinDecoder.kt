/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.FloatList
import org.fcitx.fcitx5.android.engine.data.IntList
import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import org.fcitx.fcitx5.android.engine.data.Vocabulary
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches

/**
 * Reads a [SyllableGraph] as Chinese. Every dictionary word along every path of the graph
 * becomes an arc of a word lattice; a beam search then keeps the [beam] best word sequences
 * ending at each position, scored by [scorer] plus the [penalties] of how the input was read.
 *
 * Sequences are not merged on their last two words, as exact trigram Viterbi would: that finds
 * the same best sentence but leaves the runners-up differing only at the end, while the ones a
 * user needs differ anywhere (在/再 early on). Keeping whole sequences gives a real N-best for the
 * candidate list and for a reranker to rescore. The price: one text reached through different
 * words (你好, 你 + 好) takes several places in the beam, so fewer distinct sentences come out.
 *
 * Each decode builds on the last one: the beams before the first position a changed arc ends at
 * are kept, so a key typed at the end of long input searches only its last syllables. Nearly
 * all the time goes to [scorer], and without this the cost of a key would grow with the length
 * of the input.
 *
 * Reuses its buffers between calls: not thread-safe, and meant to live as long as the engine.
 */
class PinyinDecoder(
    private val dictionary: PinyinDictionary,
    private val vocabulary: Vocabulary,
    private val scorer: WordScorer,
    private val penalties: Penalties = Penalties(),
    private val beam: Int = DEFAULT_BEAM,
    /** Words taken per reading past the first word, most probable first; the first gets them all. */
    private val wordsPerReading: Int = DEFAULT_WORDS_PER_READING,
    /** The user's own words, found along with the dictionary's; learning them needs a [reset]. */
    private val user: UserWords? = null,
) {
    init {
        require(beam >= 1 && wordsPerReading >= 1) { "beam $beam, words per reading $wordsPerReading" }
    }

    private var arcs = Lattice()

    // what the beams hold now came from these, or from nothing if lastEnd is -1
    private var lastArcs = Lattice()
    private var lastEnd = -1
    private var lastStart = 0
    private var lastPrev2 = NO_WORD
    private var lastPrev = NO_WORD

    // per position, up to [beam] word sequences, best first; a sequence is its last word and a
    // link to the slot of the one it goes on from, which stays put once its position is searched
    private var beamSize = IntArray(0)
    private var stateScore = FloatArray(0)
    private var stateWord = IntArray(0)
    private var statePrev = IntArray(0)
    private var stateBack = IntArray(0) // -1 for the empty sequence at the start
    private val contexts = LongArray(beam) // of the beam being extended, from scorer

    /**
     * @param prev2 the word before [prev] in text already committed, or [NO_WORD]
     * @param prev the last word committed, or [NO_WORD]
     * @param sentences how many readings of the whole input to return at most
     * @param words how many words the input may start with to return at most
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
            return Decoding(emptyList(), emptyList())
        }
        val last = lastEnd
        // until the search is done: one that threw would leave beams and arcs that match nothing
        lastEnd = -1
        lastArcs = arcs.also { arcs = lastArcs }
        buildLattice(graph)
        search(graph, prev2, prev, last)
        lastEnd = graph.end
        lastStart = graph.start
        lastPrev2 = prev2
        lastPrev = prev
        return Decoding(sentences(graph, sentences), firstWords(graph, prev2, prev, words))
    }

    /** Forgets the last decode, so the next searches from scratch; needed when [scorer]'s scores change. */
    fun reset() {
        lastEnd = -1
    }

    private fun buildLattice(graph: SyllableGraph) {
        val n = graph.end
        arcs.clear(n)
        for (at in 0..n) {
            arcs.start[at] = arcs.size
            if (graph.edges(at).isEmpty()) continue
            val limit = if (at == graph.start) Int.MAX_VALUE else wordsPerReading
            collect(graph, at, dictionary.root, user?.root ?: -1, 0f, limit)
            // nothing in the dictionary starts here: keep what was typed, so every path goes on
            if (arcs.size == arcs.start[at]) {
                for (e in graph.edges(at)) arcs.add(graph.to(e), NO_WORD, penalties.raw)
            }
            arcs.sortByEnd(arcs.start[at], n)
        }
        arcs.start[n + 1] = arcs.size
    }

    /**
     * Adds the words of every path from [node] in the dictionary, and [userNode] in the user's
     * words, that the graph spells from [at]; -1 for a trie the path has left.
     */
    private fun collect(graph: SyllableGraph, at: Int, node: Int, userNode: Int, cost: Float, limit: Int) {
        for (e in graph.edges(at)) {
            val kind = graph.kind(e)
            if (kind == Kind.RAW) continue
            val to = graph.to(e)
            val matches = graph.matches(e)
            val edgeCost = cost + penalty(kind)
            for (i in 0 until matches.size) {
                val child = if (node < 0) -1 else dictionary.child(node, matches.syllable(i))
                val userChild = if (userNode < 0 || user == null) -1 else user.child(userNode, matches.syllable(i))
                if (child < 0 && userChild < 0) continue
                val c = edgeCost + penalty(matches.flags(i))
                var more = false
                if (child >= 0) {
                    val words = minOf(dictionary.wordCount(child), limit)
                    for (w in 0 until words) arcs.add(to, dictionary.word(child, w), c + dictionary.weight(child, w))
                    more = dictionary.childCount(child) > 0
                }
                if (userChild >= 0 && user != null) {
                    // one reading each, so no weight; and few enough to take them all
                    for (w in 0 until user.wordCount(userChild)) arcs.add(to, user.word(userChild, w), c)
                    more = more || user.childCount(userChild) > 0
                }
                if (to < graph.end && more) collect(graph, to, child, userChild, c, limit)
            }
        }
    }

    private fun penalty(kind: Kind) = when (kind) {
        Kind.SYLLABLE -> 0f
        Kind.INITIAL -> penalties.initial
        Kind.PARTIAL -> penalties.partial
        Kind.RAW -> penalties.raw
    }

    private fun penalty(flags: Int): Float {
        var p = 0f
        if (flags and SyllableMatches.FUZZY != 0) p += penalties.fuzzy
        if (flags and SyllableMatches.TYPO != 0) p += penalties.typo
        return p
    }

    private fun search(graph: SyllableGraph, prev2: Int, prev: Int, last: Int) {
        val n = graph.end
        if (beamSize.size < n + 1) {
            beamSize = beamSize.copyOf(n + 1)
            stateScore = stateScore.copyOf((n + 1) * beam)
            stateWord = stateWord.copyOf((n + 1) * beam)
            statePrev = statePrev.copyOf((n + 1) * beam)
            stateBack = stateBack.copyOf((n + 1) * beam)
        }
        val sameContext = graph.start == lastStart && prev2 == lastPrev2 && prev == lastPrev
        val from = if (sameContext && last >= 0) unchangedUntil(graph, last) else graph.start
        beamSize.fill(0, from, n + 1)
        if (from == graph.start) insert(graph.start, 0f, prev, prev2, -1)
        for (at in graph.start until n) {
            if (beamSize[at] > 0) extend(at, from)
        }
    }

    /** Extends the sequences ending at [at] by the arcs from there, those ending at [from] or later. */
    private fun extend(at: Int, from: Int) {
        val first = at * beam
        val size = beamSize[at]
        var looked = false
        for (a in arcs.start[at] until arcs.start[at + 1]) {
            val to = arcs.to[a]
            if (to < from) continue // that beam is kept
            if (!looked) {
                // once per state rather than per arc: most of what scoring a word takes. Arcs only
                // go forward, so this beam does not change while it is extended
                for (k in 0 until size) contexts[k] = scorer.context(statePrev[first + k], stateWord[first + k])
                looked = true
            }
            val word = arcs.word[a]
            for (k in 0 until size) {
                val s = first + k
                val base = stateScore[s] + arcs.cost[a]
                // scores only fall from here, and the states come best first
                if (beamSize[to] == beam && base <= stateScore[to * beam + beam - 1]) break
                val lm = if (word == NO_WORD) 0f else scorer.scoreAfter(contexts[k], word)
                insert(to, base + lm, word, stateWord[s], s)
            }
        }
    }

    /**
     * The first position whose beam may differ from the last decode's, or [SyllableGraph.start]
     * to search it all. A beam depends only on the arcs ending there, in their order, and on the
     * beams they start from; so beams before the first arc that differs are as the last decode
     * left them. The typed text is not among that: text kept as typed is read from the input
     * only when the candidates are made.
     */
    private fun unchangedUntil(graph: SyllableGraph, last: Int): Int {
        var until = minOf(last, graph.end)
        var at = graph.start
        while (at < until) {
            var a = lastArcs.start[at]
            var b = arcs.start[at]
            val aEnd = lastArcs.start[at + 1]
            val bEnd = arcs.start[at + 1]
            while (a < aEnd && b < bEnd && lastArcs.same(a, arcs, b)) {
                a++
                b++
            }
            // the groups differ from here on; being sorted by end, every arc ending before the
            // first of these ends is in the same place in both
            for (i in a until aEnd) until = minOf(until, lastArcs.to[i])
            for (i in b until bEnd) until = minOf(until, arcs.to[i])
            at++
        }
        return maxOf(until, graph.start)
    }

    /** Puts a new state into the beam at [at], unless the beam is full of better ones. */
    private fun insert(at: Int, score: Float, word: Int, prev: Int, back: Int) {
        val offset = at * beam
        var size = beamSize[at]
        // the same word on from the same state, by another reading of the same input: keep the best
        for (s in offset until offset + size) {
            if (stateBack[s] == back && stateWord[s] == word) {
                if (score <= stateScore[s]) return
                for (t in s until offset + size - 1) move(t + 1, t)
                size--
                break
            }
        }
        // only a full beam gets here, so nothing was removed above
        if (size == beam && score <= stateScore[offset + size - 1]) return
        var s = offset + minOf(size, beam - 1)
        while (s > offset && stateScore[s - 1] < score) {
            move(s - 1, s)
            s--
        }
        stateScore[s] = score
        stateWord[s] = word
        statePrev[s] = prev
        stateBack[s] = back
        beamSize[at] = minOf(size + 1, beam)
    }

    private fun move(from: Int, to: Int) {
        stateScore[to] = stateScore[from]
        stateWord[to] = stateWord[from]
        statePrev[to] = statePrev[from]
        stateBack[to] = stateBack[from]
    }

    private fun sentences(graph: SyllableGraph, limit: Int): List<Candidate> {
        val end = graph.end
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        for (last in end * beam until end * beam + beamSize[end]) {
            if (out.size >= limit) break
            var count = 0
            var s = last
            while (stateBack[s] >= 0) {
                count++
                s = stateBack[s]
            }
            val path = IntArray(count)
            s = last
            while (count > 0) {
                path[--count] = s
                s = stateBack[s]
            }
            val text = path.joinToString("") { textOf(graph, it) }
            if (seen.add(text)) {
                out += Candidate(text, end, stateScore[last], IntArray(path.size) { stateWord[path[it]] }, IntArray(path.size) { path[it] / beam })
            }
        }
        return out
    }

    private fun textOf(graph: SyllableGraph, state: Int): String {
        val word = stateWord[state]
        return if (word == NO_WORD) graph.text(stateBack[state] / beam, state / beam) else text(word)
    }

    private fun text(word: Int) = if (word < vocabulary.size || user == null) vocabulary.word(word) else user.text(word)

    /**
     * Words the input may start with, best first, each once. An initial alone may start with
     * thousands (`s` is every sh and s syllable), so text is made only for those returned.
     */
    private fun firstWords(graph: SyllableGraph, prev2: Int, prev: Int, limit: Int): List<Candidate> {
        val from = arcs.start[graph.start]
        val until = arcs.start[graph.start + 1]
        val context = scorer.context(prev2, prev)
        val scores = FloatArray(until - from)
        // score, then arc, in one sortable long: best first, ties in arc order
        val order = LongArray(until - from) { i ->
            val a = from + i
            val word = arcs.word[a]
            scores[i] = if (word == NO_WORD) Float.NEGATIVE_INFINITY else arcs.cost[a] + scorer.scoreAfter(context, word)
            (sortable(-scores[i]).toLong() shl Int.SIZE_BITS) or a.toLong()
        }
        order.sort()
        val out = ArrayList<Candidate>()
        val seen = HashSet<Int>()
        var next = 0
        while (out.size < limit && next < order.size) {
            val a = order[next++].toInt()
            val word = arcs.word[a]
            if (word != NO_WORD && seen.add(word)) {
                out += Candidate(text(word), arcs.to[a], scores[a - from], intArrayOf(word), intArrayOf(arcs.to[a]))
            }
        }
        return out
    }

    /**
     * Arcs grouped by where they start: those from `at` are `start[at] until start[at + 1]`, in
     * the order they end. That puts what a key changes, the arcs ending near the end of the
     * input, last in each group; in the order the dictionary gives them, a change deep in a long
     * word would come before the short arcs from the same place and hide that they are the same.
     */
    private class Lattice {
        var start = IntArray(0)
        val to = IntList()
        val word = IntList() // NO_WORD for input kept as typed
        val cost = FloatList()
        val size get() = to.size

        // for sorting
        private var ends = IntArray(0)
        private val toCopy = IntList()
        private val wordCopy = IntList()
        private val costCopy = FloatList()

        fun clear(end: Int) {
            if (start.size < end + 2) {
                start = IntArray(end + 2)
                ends = IntArray(end + 2)
            }
            to.clear()
            word.clear()
            cost.clear()
        }

        /** Orders the arcs from [first] on, none ending past [end], by where they end, keeping the order of those ending together. */
        fun sortByEnd(first: Int, end: Int) {
            var sorted = true
            for (a in first + 1 until size) {
                if (to[a] < to[a - 1]) {
                    sorted = false
                    break
                }
            }
            if (sorted) return
            // a counting sort: ends[q] becomes where the arcs ending at q go
            toCopy.clear()
            wordCopy.clear()
            costCopy.clear()
            ends.fill(0, 0, end + 1)
            for (a in first until size) {
                toCopy += to[a]
                wordCopy += word[a]
                costCopy += cost[a]
                ends[to[a]]++
            }
            var next = first
            for (q in 0..end) next += ends[q].also { ends[q] = next }
            for (i in 0 until toCopy.size) {
                val a = ends[toCopy[i]]++
                to[a] = toCopy[i]
                word[a] = wordCopy[i]
                cost[a] = costCopy[i]
            }
        }

        fun add(to: Int, word: Int, cost: Float) {
            this.to += to
            this.word += word
            this.cost += cost
        }

        fun same(arc: Int, other: Lattice, otherArc: Int) =
            to[arc] == other.to[otherArc] && word[arc] == other.word[otherArc] && cost[arc] == other.cost[otherArc]
    }

    companion object {
        const val DEFAULT_BEAM = 16
        const val DEFAULT_WORDS = 64
        // on the 256-sample evaluation set, 16 words are as accurate as 256 at a third of the
        // time, and a beam of 64 gains one sample of 256 at four times the time
        const val DEFAULT_WORDS_PER_READING = 16
        const val DEFAULT_SENTENCES = 5
    }
}

/**
 * Input read as [text], from the start of the input up to [end]; [words] are its vocabulary ids,
 * [NO_WORD] for text kept as typed, and [ends] where in the input each of them ends. A prediction
 * reads no input: its end, and its word's, is 0.
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

/**
 * [sentences] read the whole input, best first; [words] are every word it may start with, best
 * first, each the start of a candidate list the user picks from piece by piece.
 */
class Decoding internal constructor(val sentences: List<Candidate>, val words: List<Candidate>)

/** [value]'s bits as an int that orders as the floats do. */
private fun sortable(value: Float): Int {
    val bits = java.lang.Float.floatToIntBits(value)
    return if (bits < 0) bits xor Int.MAX_VALUE else bits
}
