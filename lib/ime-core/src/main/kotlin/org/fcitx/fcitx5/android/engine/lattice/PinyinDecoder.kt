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
) {
    init {
        require(beam >= 1 && wordsPerReading >= 1) { "beam $beam, words per reading $wordsPerReading" }
    }

    // arcs, grouped by where they start
    private var arcStart = IntArray(0)
    private val arcTo = IntList()
    private val arcWord = IntList() // NO_WORD for input kept as typed
    private val arcEdge = IntList() // for input kept as typed, the graph edge holding its text
    private val arcCost = FloatList()

    // word sequences, each one word on from its back state
    private val stateScore = FloatList()
    private val stateWord = IntList()
    private val statePrev = IntList()
    private val stateBack = IntList()
    private val stateArc = IntList()

    // per position, up to [beam] states, best first
    private var beams = IntArray(0)
    private var beamSize = IntArray(0)

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
        if (graph.start == graph.end) return Decoding(emptyList(), emptyList())
        buildLattice(graph)
        search(graph, prev2, prev)
        return Decoding(sentences(graph, sentences), firstWords(graph, prev2, prev, words))
    }

    private fun buildLattice(graph: SyllableGraph) {
        val n = graph.end
        if (arcStart.size < n + 2) arcStart = IntArray(n + 2)
        arcTo.clear()
        arcWord.clear()
        arcEdge.clear()
        arcCost.clear()
        for (at in 0..n) {
            arcStart[at] = arcTo.size
            if (graph.edges(at).isEmpty()) continue
            val limit = if (at == graph.start) Int.MAX_VALUE else wordsPerReading
            collect(graph, at, dictionary.root, 0f, limit)
            // nothing in the dictionary starts here: keep what was typed, so every path goes on
            if (arcTo.size == arcStart[at]) {
                for (e in graph.edges(at)) addArc(graph.to(e), NO_WORD, e, penalties.raw)
            }
        }
        arcStart[n + 1] = arcTo.size
    }

    /** Adds the words of every dictionary path from [node] that the graph spells from [at]. */
    private fun collect(graph: SyllableGraph, at: Int, node: Int, cost: Float, limit: Int) {
        for (e in graph.edges(at)) {
            val kind = graph.kind(e)
            if (kind == Kind.RAW) continue
            val to = graph.to(e)
            val matches = graph.matches(e)
            val edgeCost = cost + penalty(kind)
            for (i in 0 until matches.size) {
                val child = dictionary.child(node, matches.syllable(i))
                if (child < 0) continue
                val c = edgeCost + penalty(matches.flags(i))
                val words = minOf(dictionary.wordCount(child), limit)
                for (w in 0 until words) addArc(to, dictionary.word(child, w), -1, c + dictionary.weight(child, w))
                if (to < graph.end && dictionary.childCount(child) > 0) collect(graph, to, child, c, limit)
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

    private fun addArc(to: Int, word: Int, edge: Int, cost: Float) {
        arcTo += to
        arcWord += word
        arcEdge += edge
        arcCost += cost
    }

    private fun search(graph: SyllableGraph, prev2: Int, prev: Int) {
        val n = graph.end
        if (beamSize.size < n + 1) {
            beamSize = IntArray(n + 1)
            beams = IntArray((n + 1) * beam)
        } else {
            beamSize.fill(0, 0, n + 1)
        }
        stateScore.clear()
        stateWord.clear()
        statePrev.clear()
        stateBack.clear()
        stateArc.clear()
        insert(graph.start, 0f, prev, prev2, -1, -1)
        for (at in graph.start until n) {
            val size = beamSize[at]
            if (size == 0) continue
            for (a in arcStart[at] until arcStart[at + 1]) {
                val to = arcTo[a]
                val word = arcWord[a]
                for (k in 0 until size) {
                    val s = beams[at * beam + k]
                    val base = stateScore[s] + arcCost[a]
                    // scores only fall from here, and the states come best first
                    if (beamSize[to] == beam && base <= stateScore[beams[to * beam + beam - 1]]) break
                    val lm = if (word == NO_WORD) 0f else scorer.score(statePrev[s], stateWord[s], word)
                    insert(to, base + lm, word, stateWord[s], s, a)
                }
            }
        }
    }

    /** Puts a new state into the beam at [at], unless the beam is full of better ones. */
    private fun insert(at: Int, score: Float, word: Int, prev: Int, back: Int, arc: Int) {
        val offset = at * beam
        var size = beamSize[at]
        // the same word on from the same state, by another reading of the same input: keep the best
        for (k in 0 until size) {
            val s = beams[offset + k]
            if (stateBack[s] == back && stateWord[s] == word) {
                if (score <= stateScore[s]) return
                beams.copyInto(beams, offset + k, offset + k + 1, offset + size)
                size--
                break
            }
        }
        // only a full beam gets here, so nothing was removed above
        if (size == beam && score <= stateScore[beams[offset + size - 1]]) return
        val state = stateScore.size
        stateScore += score
        stateWord += word
        statePrev += prev
        stateBack += back
        stateArc += arc
        var k = minOf(size, beam - 1)
        while (k > 0 && stateScore[beams[offset + k - 1]] < score) {
            beams[offset + k] = beams[offset + k - 1]
            k--
        }
        beams[offset + k] = state
        beamSize[at] = minOf(size + 1, beam)
    }

    private fun sentences(graph: SyllableGraph, limit: Int): List<Candidate> {
        val end = graph.end
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        for (k in 0 until beamSize[end]) {
            if (out.size >= limit) break
            val last = beams[end * beam + k]
            val arcs = ArrayList<Int>()
            var s = last
            while (stateBack[s] >= 0) {
                arcs += stateArc[s]
                s = stateBack[s]
            }
            arcs.reverse()
            val text = arcs.joinToString("") { textOf(graph, it) }
            if (seen.add(text)) out += Candidate(text, end, stateScore[last], IntArray(arcs.size) { arcWord[arcs[it]] })
        }
        return out
    }

    /**
     * Words the input may start with, best first, each once. An initial alone may start with
     * thousands (`s` is every sh and s syllable), so text is made only for those returned.
     */
    private fun firstWords(graph: SyllableGraph, prev2: Int, prev: Int, limit: Int): List<Candidate> {
        val from = arcStart[graph.start]
        val until = arcStart[graph.start + 1]
        val scores = FloatArray(until - from)
        // score, then arc, in one sortable long: best first, ties in arc order
        val order = LongArray(until - from) { i ->
            val a = from + i
            val word = arcWord[a]
            scores[i] = if (word == NO_WORD) Float.NEGATIVE_INFINITY else arcCost[a] + scorer.score(prev2, prev, word)
            (sortable(-scores[i]).toLong() shl Int.SIZE_BITS) or a.toLong()
        }
        order.sort()
        val out = ArrayList<Candidate>()
        val seen = HashSet<Int>()
        var next = 0
        while (out.size < limit && next < order.size) {
            val a = order[next++].toInt()
            val word = arcWord[a]
            if (word != NO_WORD && seen.add(word)) out += Candidate(vocabulary.word(word), arcTo[a], scores[a - from], intArrayOf(word))
        }
        return out
    }

    private fun textOf(graph: SyllableGraph, arc: Int): String {
        val word = arcWord[arc]
        return if (word == NO_WORD) graph.text(arcEdge[arc]) else vocabulary.word(word)
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
 * [NO_WORD] for text kept as typed.
 */
class Candidate internal constructor(val text: String, val end: Int, val score: Float, val words: IntArray) {
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
