/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.NgramModel.Trigrams
import org.fcitx.fcitx5.android.engine.lattice.Lattice.Companion.SHORT
import org.fcitx.fcitx5.android.engine.lattice.Lattice.Companion.START_ONLY
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph

/**
 * [PinyinDecoder]'s search over a [Lattice]: per position up to [beam] word sequences, best first, each its last
 * word and a link to its predecessor's slot, fixed once its position is searched. Scored by [scorer], or for some
 * arcs [whole] or [uncounted] ([scoring]). Reuses its buffers: one search at a time.
 */
internal class BeamSearch(
    private val beam: Int,
    private val scorer: WordScorer,
    private val whole: WordScorer?,
    private val uncounted: WordScorer?,
) {
    private var beamSize = IntArray(0)
    private var stateScore = FloatArray(0)
    private var stateWord = IntArray(0)
    private var statePrev = IntArray(0)
    private var stateBack = IntArray(0) // -1 for the empty sequence at the start

    private val contexts = LongArray(beam) // of the beam being extended, from scorer
    private val runs = LongArray(beam) // the scorer's trigrams for each of contexts

    /** Words scored after a sequence in the last search: [PinyinDecoder.Work.scored]. */
    var scored = 0
        private set

    /** A sequence's word from one position to another as text, for [sentences]. */
    fun interface Text {
        fun of(graph: SyllableGraph, word: Int, from: Int, to: Int): String
    }

    /** No search done: [scored] is not the last one's. */
    fun clearWork() {
        scored = 0
    }

    /**
     * Searches [arcs] of [graph] after the words [prev2] and [prev]. [last] is the end of the last search,
     * of [lastArcs] in the same context, or -1: the beams before the first position it may differ at are kept.
     */
    fun search(arcs: Lattice, lastArcs: Lattice, graph: SyllableGraph, prev2: Int, prev: Int, last: Int) {
        val n = graph.end
        if (beamSize.size < n + 1) {
            beamSize = beamSize.copyOf(n + 1)
            stateScore = stateScore.copyOf((n + 1) * beam)
            stateWord = stateWord.copyOf((n + 1) * beam)
            statePrev = statePrev.copyOf((n + 1) * beam)
            stateBack = stateBack.copyOf((n + 1) * beam)
        }
        val from = if (last >= 0) unchangedUntil(arcs, lastArcs, graph, last) else graph.start
        beamSize.fill(0, from, n + 1)
        if (from == graph.start) insert(graph.start, 0f, prev, prev2, -1)
        scored = 0
        for (at in graph.start until n) {
            if (beamSize[at] > 0) extend(arcs, at, from, if (at == graph.start) n else -1)
        }
    }

    /** Extends sequences ending at [at] by arcs from there ending at [from] or later; one ending at [wholeEnd] reads all the input, scored by [whole] if given. */
    private fun extend(arcs: Lattice, at: Int, from: Int, wholeEnd: Int) {
        val first = at * beam
        val size = beamSize[at]
        var looked = false
        var asked = 0
        // those ending before [from] come first, and their beams are kept
        for (a in arcs.firstEndingAt(at, from) until arcs.start[at + 1]) {
            val to = arcs.to[a]
            if (arcs.flags[a] and START_ONLY != 0) continue // a start-only word begins no sentence
            if (!looked) {
                // once per state, not per arc: most of a word's scoring cost. Arcs only go forward, so this beam is stable
                for (k in 0 until size) {
                    contexts[k] = scorer.context(statePrev[first + k], stateWord[first + k])
                    runs[k] = scorer.trigrams(contexts[k]).bits
                }
                looked = true
            }
            val word = arcs.word[a]
            val scored = arcs.scored[a]
            val scoring = scoring(arcs, a, to == wholeEnd)
            for (k in 0 until size) {
                val s = first + k
                val base = stateScore[s] + arcs.cost[a]
                // scores only fall from here, and the states come best first
                if (beamSize[to] == beam && base <= stateScore[to * beam + beam - 1]) break
                val lm = if (word == NO_WORD) 0f else scoring.scoreAfter(contexts[k], Trigrams(runs[k]), scored)
                insert(to, base + lm, word, stateWord[s], s)
                asked++
            }
        }
        this.scored += asked
    }

    /** Scores arc [a] of [arcs]: no user counts over a short syllable, or (MULTI) when reading all the input alone. */
    fun scoring(arcs: Lattice, a: Int, wholeInput: Boolean): WordScorer = when {
        arcs.flags[a] and SHORT != 0 -> uncounted ?: scorer
        wholeInput -> whole ?: scorer
        else -> scorer
    }

    /**
     * The first position whose beam may differ from the last decode's, or [SyllableGraph.start]. A beam
     * depends only on the arcs ending there, in order, and the beams they start from. Typed text is not
     * part of it: text kept as typed is read from the input only when candidates are made.
     */
    private fun unchangedUntil(arcs: Lattice, lastArcs: Lattice, graph: SyllableGraph, last: Int): Int =
        maxOf(arcs.sameUntil(lastArcs, graph.start, minOf(last, graph.end)), graph.start)

    /** Unless the beam is full of better ones. */
    private fun insert(at: Int, score: Float, word: Int, prev: Int, back: Int) {
        val offset = at * beam
        var size = beamSize[at]
        // most offers to a full beam fall below its last, and so below the same word from the same state:
        // rejected before looking for that
        if (size == beam && score <= stateScore[offset + beam - 1]) return
        // the same word from the same state by another reading of the same input: keep the best
        for (s in offset until offset + size) {
            if (stateBack[s] == back && stateWord[s] == word) {
                if (score <= stateScore[s]) return
                for (t in s until offset + size - 1) move(t + 1, t)
                size--
                break
            }
        }
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

    /** Up to [limit] sequences reading all of [graph], best first, each text once. */
    fun sentences(graph: SyllableGraph, limit: Int, textOf: Text): List<Candidate> {
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
            val text = buildString { for (state in path) append(textOf.of(graph, stateWord[state], stateBack[state] / beam, state / beam)) }
            if (seen.add(text)) {
                out += Candidate(text, end, stateScore[last], IntArray(path.size) { stateWord[path[it]] }, IntArray(path.size) { path[it] / beam })
            }
        }
        return out
    }
}
