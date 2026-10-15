/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import org.fcitx.fcitx5.android.engine.data.Vocabulary
import org.fcitx.fcitx5.android.engine.data.WordIndex
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.lattice.UserWords
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

/**
 * Reads characters written one at a time as a sentence, as pinyin reads syllables: each character
 * written is a position with its readings ([InkReading], best first), and the sentence is the
 * likeliest run of words through them, scored by [scorer] (the pinyin model, the user's counts
 * mixed in) plus [inkWeight] × the recognizer's log-probabilities. A later character can so put
 * right an earlier one: 淡 read alone, 谈 once 话 follows.
 *
 * The words are found as pinyin finds them, through [dictionary]'s syllable trie (and the [user]'s
 * new words through theirs), each character of a position's first [alternatives] readings followed
 * by its syllables ([readings]); a word is one whose text those characters spell ([index]). Every
 * reading of a position is a word of one character too, the model's unknown word where the model
 * lacks it. Words the user blocked are left out; a coarse word ([coarse]) takes [CoarseWords.PENALTY] more.
 *
 * Not thread-safe; cheap to make, but meant to live as long as the session.
 */
class HandwritingDecoder(
    private val dictionary: PinyinDictionary,
    private val vocabulary: Vocabulary,
    private val index: WordIndex,
    private val scorer: WordScorer,
    private val readings: () -> Map<Int, IntArray>,
    private val user: UserWords? = null,
    private val coarse: CoarseWords = CoarseWords.NONE,
    private val inkWeight: Double = INK_WEIGHT,
    private val alternatives: Int = ALTERNATIVES,
    private val beam: Int = BEAM,
) {
    init {
        require(inkWeight >= 0 && alternatives >= 1 && beam >= 1) { "ink weight $inkWeight, alternatives $alternatives, beam $beam" }
    }

    /**
     * What [positions] read as after the words [prev2] and [prev]: for each reading of the last
     * position, the best sentence ending with it, best first ([text] the first); none for no
     * positions. [words] has each sentence's words as the user model learns them, null where a
     * character has no reading. [best] are the best sentences whatever they end with, [bestScores]
     * theirs (the model's log10 plus the ink's weighed log-probabilities), for a sentence model to
     * weigh again, and [bestWords] their words as [words] has them: [best]'s first is [text].
     */
    class Decoding(
        val sentences: List<String>,
        val words: List<List<Entry>?> = sentences.map { null },
        val best: List<String> = sentences.take(1),
        val bestScores: List<Float> = best.map { 0f },
        val bestWords: List<List<Entry>?> = best.map { null },
    ) {
        val text: String get() = sentences.firstOrNull().orEmpty()
    }

    // [syllables]: how the word was found, its character's first reading for one alone; null for none
    private class Arc(val start: Int, val end: Int, val word: Int, val text: String, val ink: Double, val syllables: IntArray?)

    // a sentence so far: its score, its last two words, and the one it goes on from
    private class State(val score: Double, val prev: Int, val word: Int, val text: String, val syllables: IntArray?, val back: State?)

    fun decode(positions: List<InkReading>, prev2: Int = NO_WORD, prev: Int = NO_WORD): Decoding {
        val n = positions.size
        if (n == 0) return Decoding(emptyList())
        val ending = arcs(positions)
        val beams = arrayOfNulls<List<State>>(n + 1)
        beams[0] = listOf(State(0.0, prev2, prev, "", null, null))
        for (end in 1..n) {
            val next = ArrayList<State>()
            for (arc in ending[end]) {
                for (s in beams[arc.start] ?: continue) next += State(s.score + arc.ink + lm(s.prev, s.word, arc.word), s.word, arc.word, arc.text, arc.syllables, s)
            }
            next.sortByDescending { it.score }
            // the last position keeps them all: each of its readings is offered, as its best sentence has it
            beams[end] = if (end == n) next else distinct(next)
        }
        val ends = HashSet<String>()
        val all = beams[n].orEmpty()
        val kept = all.filter { ends.add(lastCharacter(it.text)) }
        val best = LinkedHashMap<String, State>()
        for (s in all) {
            if (best.size == BEST) break
            best.putIfAbsent(textOf(s), s)
        }
        return Decoding(
            kept.map(::textOf), kept.map(::wordsOf),
            best.keys.toList(), best.values.map { it.score.toFloat() }, best.values.map(::wordsOf),
        )
    }

    // the best [beam] sentences, one a pair of last words (a character the model lacks told by its text):
    // what goes on from either is scored alike, so the worse would never come first again
    private fun distinct(states: List<State>): List<State> {
        val seen = HashSet<String>()
        val kept = ArrayList<State>(beam)
        for (s in states) {
            if (kept.size == beam) break
            if (seen.add("${s.prev} ${s.word} ${if (s.word == NO_WORD) s.text else ""}")) kept += s
        }
        return kept
    }

    private fun textOf(state: State): String {
        val parts = ArrayList<String>()
        var s: State? = state
        while (s?.back != null) {
            parts += s.text
            s = s.back
        }
        return parts.asReversed().joinToString("")
    }

    private fun wordsOf(state: State): List<Entry>? {
        val words = ArrayList<Entry>()
        var s: State? = state
        while (s?.back != null) {
            words += Entry(s.text, s.syllables ?: return null)
            s = s.back
        }
        return words.asReversed()
    }

    private fun lm(prev2: Int, prev: Int, word: Int): Double {
        val score = scorer.score(prev2, prev, word).toDouble()
        return if (word != NO_WORD && coarse.unasked(word, user, vocabulary.size)) score + CoarseWords.PENALTY else score
    }

    // the positions last decoded, and the arcs from each: those of a start whose positions are
    // the same readings again (the characters before the one written) are found once
    private var known: List<InkReading> = emptyList()
    private var knownFrom: List<List<Arc>> = emptyList()

    // the arcs ending at each position: every reading as one character, and the words longer
    private fun arcs(positions: List<InkReading>): Array<ArrayList<Arc>> {
        val ending = Array(positions.size + 1) { ArrayList<Arc>() }
        val from = ArrayList<List<Arc>>(positions.size)
        val found = HashSet<String>()
        for (start in positions.indices) {
            val arcs = if (same(positions, start)) knownFrom[start] else arcsFrom(positions, start, found)
            from += arcs
            for (arc in arcs) ending[arc.end] += arc
        }
        known = positions.toList()
        knownFrom = from
        return ending
    }

    // whether the words from [start] are those found last time: the same readings, as many
    private fun same(positions: List<InkReading>, start: Int): Boolean {
        if (start >= known.size) return false
        val end = minOf(positions.size, start + MAX_WORD)
        if (minOf(known.size, start + MAX_WORD) != end) return false
        return (start until end).all { positions[it] === known[it] }
    }

    private fun arcsFrom(positions: List<InkReading>, start: Int, found: HashSet<String>): List<Arc> {
        val arcs = ArrayList<Arc>()
        val p = positions[start]
        val map = readings()
        for (i in p.texts.indices) {
            val c = p.texts[i]
            val syllable = map[c.codePointAt(0)]?.firstOrNull()
            arcs += Arc(start, start + 1, index.find(c), c, inkWeight * p.scores[i], syllable?.let { intArrayOf(it) })
        }
        found.clear()
        extend(positions, start, start, dictionary.root, user?.root ?: -1, StringBuilder(), 0.0, arcs, found)
        return arcs
    }

    // the syllables read from the start of the word being looked for to [extend]'s position
    private val path = IntArray(MAX_WORD)

    // the words of two characters or more going on from [text] (through [node] and [userNode]) at [at]
    private fun extend(
        positions: List<InkReading>, start: Int, at: Int, node: Int, userNode: Int, text: StringBuilder, ink: Double,
        arcs: ArrayList<Arc>, found: HashSet<String>,
    ) {
        if (at == positions.size || at - start == MAX_WORD) return
        val p = positions[at]
        val map = readings()
        for (i in 0 until minOf(alternatives, p.texts.size)) {
            val c = p.texts[i]
            val syllables = map[c.codePointAt(0)] ?: continue
            val scored = ink + inkWeight * p.scores[i]
            text.append(c)
            for (s in syllables) {
                val child = if (node < 0) -1 else dictionary.child(node, s)
                val userChild = if (userNode < 0 || user == null) -1 else user.child(userNode, s)
                if (child < 0 && userChild < 0) continue
                path[at - start] = s
                if (at > start) word(start, at + 1, text, child, userChild, scored, arcs, found)
                val more = (child >= 0 && dictionary.childCount(child) > 0) || (userChild >= 0 && user!!.childCount(userChild) > 0)
                if (more) extend(positions, start, at + 1, child, userChild, text, scored, arcs, found)
            }
            text.setLength(text.length - c.length)
        }
    }

    // [text], read through [node] or [userNode], as a word ending at [end]: once, whichever reading found it
    private fun word(start: Int, end: Int, text: StringBuilder, node: Int, userNode: Int, ink: Double, arcs: ArrayList<Arc>, found: HashSet<String>) {
        val t = text.toString()
        if (t in found) return
        if (node >= 0) {
            val id = index.find(t)
            // blocked in any reading: what is written has none. A word at the node, not one that only
            // shares its text with a word of the node's reading's children: 银行 through 银杏's
            // yin xing would be learned as read so, a word of the user's
            if (id != NO_WORD && user?.blockedAnyhow(id) != true && (0 until dictionary.wordCount(node)).any { dictionary.word(node, it) == id }) {
                found += t
                arcs += Arc(start, end, id, t, ink, path.copyOf(end - start))
                return
            }
        }
        if (userNode >= 0 && user != null) {
            for (k in 0 until user.wordCount(userNode)) {
                val id = user.word(userNode, k)
                if (user.text(id) == t && !user.blockedAnyhow(id)) {
                    found += t
                    arcs += Arc(start, end, id, t, ink, path.copyOf(end - start))
                    return
                }
            }
        }
    }

    companion object {
        /** The recognizer's log-probabilities against the model's log10 ones: see ime-eval handwriting. */
        const val INK_WEIGHT = 1.0

        /** Readings of a position words are looked for through. */
        const val ALTERNATIVES = 12

        const val BEAM = 16

        /** The best sentences a [Decoding] has, whatever they end with: as many as a refiner weighs. */
        const val BEST = 5

        // the longest word looked for, as TextWords
        private const val MAX_WORD = 8
    }
}

/** The last character of [text], whole where it is two chars (past the BMP). */
internal fun lastCharacter(text: String): String = text.substring(text.offsetByCodePoints(text.length, -1))
