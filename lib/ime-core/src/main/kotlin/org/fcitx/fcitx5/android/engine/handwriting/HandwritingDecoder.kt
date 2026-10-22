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
 * lacks it, read as the user typed it, else as the dictionary likeliest reads it (行 xing, not
 * hang, the first by syllable id). Words the user blocked are left out, a character too unless it
 * is all a position reads; a coarse word ([coarse]) takes [CoarseWords.PENALTY] more.
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
     * A sentence read: its [text]; its [words] as the user model learns them, null where a
     * character has no reading; its [score], the model's log10 plus the ink's weighed
     * log-probabilities.
     */
    class Sentence(val text: String, val words: List<Entry>? = null, val score: Float = 0f)

    /**
     * What [positions] read as after the words [prev2] and [prev]: [endings], for each reading of
     * the last position the best sentence ending with it, best first ([text] the first's); none
     * for no positions. [top] are the best sentences whatever they end with, for a sentence model
     * to weigh again, which reads them as [best] and [bestScores]: [top]'s first is [endings]'.
     */
    class Decoding(val endings: List<Sentence>, val top: List<Sentence> = endings.take(1)) {
        val text: String get() = endings.firstOrNull()?.text.orEmpty()

        /** Each of [endings]' texts. */
        val sentences: List<String> get() = endings.map { it.text }

        val best: List<String> = top.map { it.text }
        val bestScores: List<Float> = top.map { it.score }
    }

    // [syllables]: how the word was found, its character's reading for one alone; null for none
    private class Arc(val start: Int, val end: Int, val word: Int, val text: String, val ink: Double, val syllables: IntArray?)

    // a sentence so far: its score, its last two words, and the one it goes on from
    private class State(val score: Double, val prev: Int, val word: Int, val text: String, val syllables: IntArray?, val back: State?)

    // best first, as sortByDescending had them, without boxing each score compared
    private val byScore = Comparator<State> { a, b -> b.score.compareTo(a.score) }

    /**
     * Whether [word], as [decode] read it, has the only reading it could: a word of several
     * characters, read as the dictionary or the user has it, or a character of one reading. A
     * character of several (行) is read as the likeliest, a guess.
     */
    fun sure(word: Entry): Boolean =
        word.text.codePointCount(0, word.text.length) > 1 || readings()[word.text.codePointAt(0)]?.size == 1

    /** Character [c] as a word of its own, read as [decode] reads it alone (see [alone]); null where it has no reading. */
    fun alone(c: String): Entry? = alone(c, index.find(c), readings())?.let { Entry(c, it) }

    /** What the last search found forgotten: the user blocked a character since, which a search kept would still find. */
    fun forgetSearch() {
        known = emptyList()
        knownFrom = emptyList()
        searchedBeams = emptyArray()
        beamsOf = emptyList()
    }

    fun decode(positions: List<InkReading>, prev2: Int = NO_WORD, prev: Int = NO_WORD): Decoding {
        val n = positions.size
        if (n == 0) return Decoding(emptyList())
        val beams = arrayOfNulls<List<State>>(n + 1)
        val from = keep(positions, prev2, prev, beams)
        val ending = arcs(positions)
        for (end in from + 1..n) {
            val next = ArrayList<State>()
            for (arc in ending[end]) {
                for (s in beams[arc.start] ?: continue) next += State(s.score + arc.ink + lm(s.prev, s.word, arc.word), s.word, arc.word, arc.text, arc.syllables, s)
            }
            next.sortWith(byScore)
            // the last position keeps them all: each of its readings is offered, as its best sentence has it
            beams[end] = if (end == n) next else distinct(next)
        }
        searchedBeams = beams
        beamsOf = positions.toList()
        beamsPrev2 = prev2
        beamsPrev = prev
        val ends = HashSet<String>()
        val all = beams[n].orEmpty()
        val endings = all.filter { ends.add(lastCharacter(it.text)) }
        val best = LinkedHashMap<String, State>()
        for (s in all) {
            if (best.size == BEST) break
            best.putIfAbsent(textOf(s), s)
        }
        return Decoding(endings.map(::sentenceOf), best.values.map(::sentenceOf))
    }

    // the beams last searched, of the positions they read, after the words they went on from
    private var searchedBeams: Array<List<State>?> = emptyArray()
    private var beamsOf: List<InkReading> = emptyList()
    private var beamsPrev2 = NO_WORD
    private var beamsPrev = NO_WORD

    // [beams] as last searched up to the position returned, where the positions before it are the
    // same readings again (the same objects, as [same] has them) after the same words; else the
    // start alone, 0. A stroke so searches on from the characters it changed, as pinyin's decoder
    // goes on from the prefix it keeps, its scores too: what is learned meanwhile counts from the
    // next sentence, whose characters are other readings
    private fun keep(positions: List<InkReading>, prev2: Int, prev: Int, beams: Array<List<State>?>): Int {
        val k = if (prev2 == beamsPrev2 && prev == beamsPrev) standing(positions) else -1
        if (k < 0) {
            beams[0] = listOf(State(0.0, prev2, prev, "", null, null))
            return 0
        }
        val last = beamsOf.size
        // the last position's beam was kept whole: one a pair of words as any other's, now one comes after it
        for (e in 0..k) beams[e] = searchedBeams[e]?.let { if (e == last && e < positions.size) distinct(it) else it }
        return k
    }

    // the last position whose beam as last searched stands for [positions]; -1 for none
    private fun standing(positions: List<InkReading>): Int {
        val last = beamsOf.size
        if (searchedBeams.size != last + 1) return -1
        var k = 0
        while (k < minOf(positions.size, last) && positions[k] === beamsOf[k]) k++
        // the last position now, one that was not: its beam kept too few
        return if (k == positions.size && k < last) k - 1 else k
    }

    // the best [beam] sentences, one a pair of last words (a character the model lacks told by its text):
    // what goes on from either is scored alike, so the worse would never come first again
    private fun distinct(states: List<State>): List<State> {
        val pairs = HashSet<Long>()
        val unknown = HashSet<String>()
        val kept = ArrayList<State>(beam)
        for (s in states) {
            if (kept.size == beam) break
            val first = if (s.word == NO_WORD) unknown.add("${s.prev} ${s.text}") else pairs.add((s.prev.toLong() shl Int.SIZE_BITS) or s.word.toLong())
            if (first) kept += s
        }
        return kept
    }

    private fun sentenceOf(state: State) = Sentence(textOf(state), wordsOf(state), state.score.toFloat())

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
        // a word of the dictionary's the user has at a reading of their own scores as the dictionary's (UserScorer)
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
        for (start in positions.indices) {
            val arcs = if (same(positions, start)) knownFrom[start] else arcsFrom(positions, start)
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

    private fun arcsFrom(positions: List<InkReading>, start: Int): List<Arc> {
        val arcs = ArrayList<Arc>()
        val p = positions[start]
        val map = readings()
        val ids = IntArray(p.texts.size) { index.find(p.texts[it]) }
        // a character the user blocked, in any reading, is left out as a word is (see word); the
        // ink's best stays where every reading is, or nothing would read the position to put it in
        val open = p.texts.indices.filter { !blocked(ids[it]) }.ifEmpty { p.texts.indices.take(1) }
        for (i in open) arcs += Arc(start, start + 1, ids[i], p.texts[i], inkWeight * p.scores[i], alone(p.texts[i], ids[i], map))
        searched = positions
        searchStart = start
        searchArcs = arcs
        foundTexts.clear()
        extend(start, dictionary.root, user?.root ?: -1, StringBuilder(), 0.0)
        return arcs
    }

    private fun blocked(word: Int) = word != NO_WORD && user?.blockedAnyhow(word) == true

    // the syllables character [c] ([word]) alone is learned as: as the user typed it while they still
    // have it (UserModel.reading), else the
    // dictionary's likeliest reading of it, as Predictor.reading has a word's (行 xing, 着 zhe: the
    // first by syllable id, hang and zhao, is the user's 行 and 着 from then on); null for none
    private fun alone(c: String, word: Int, map: Map<Int, IntArray>): IntArray? {
        if (word != NO_WORD) user?.reading(word)?.takeIf { it.size == 1 }?.let { return it }
        val cp = c.codePointAt(0)
        val syllables = map[cp]?.takeIf { it.isNotEmpty() } ?: return null
        return intArrayOf(likeliest.getOrPut(cp) { likeliestOf(c, syllables) })
    }

    // the dictionary's likeliest syllable of each character alone, as [alone] first needs it
    private val likeliest = HashMap<Int, Int>()

    // which of [syllables] the dictionary weighs highest for [c] alone: the first of them on a tie
    private fun likeliestOf(c: String, syllables: IntArray): Int {
        var best = syllables[0]
        var weight = Float.NEGATIVE_INFINITY
        for (s in syllables) {
            val node = dictionary.child(dictionary.root, s)
            if (node < 0) continue
            for (k in 0 until dictionary.wordCount(node)) {
                if (dictionary.weight(node, k) > weight && spells(dictionary.word(node, k), c)) {
                    weight = dictionary.weight(node, k)
                    best = s
                }
            }
        }
        return best
    }

    private fun spells(word: Int, text: String) = vocabulary.length(word) == text.length && text.indices.all { vocabulary.char(word, it) == text[it] }

    // the search for the words from one start ([arcsFrom]): its positions, the start, the arcs it
    // adds to, and the texts of the words found, each once: where its arc is in searchArcs, and the
    // dictionary's weight of the reading it was found at (a word the user still has: above any of
    // the dictionary's; of a reading of the user's own and a dictionary one, the more typed the heavier:
    // two dictionary readings share one id, whose reading is the last typed)
    private var searched: List<InkReading> = emptyList()
    private var searchStart = 0
    private var searchArcs = ArrayList<Arc>()
    private val foundTexts = HashMap<String, Pair<Int, Float>>()

    // the syllables read from the start of the word being looked for to [extend]'s position
    private val path = IntArray(MAX_WORD)

    // the words of two characters or more going on from [text] (through [node] and [userNode]) at [at]
    private fun extend(at: Int, node: Int, userNode: Int, text: StringBuilder, ink: Double) {
        if (at == searched.size || at - searchStart == MAX_WORD) return
        val p = searched[at]
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
                path[at - searchStart] = s
                if (at > searchStart) word(at + 1, text, child, userChild, scored)
                if (goesOn(child, userChild)) extend(at + 1, child, userChild, text, scored)
            }
            text.setLength(text.length - c.length)
        }
    }

    // whether longer words go on through [node] or [userNode]
    private fun goesOn(node: Int, userNode: Int) =
        (node >= 0 && dictionary.childCount(node) > 0) || (userNode >= 0 && user != null && user.childCount(userNode) > 0)

    // the weight of a reading the user still has [word] at: above the dictionary's (log10, at most 0),
    // and of two such readings of one text (the user's own and the dictionary's, two ids) the more typed
    // wins, not the first found
    private fun theirs(word: Int) = USER_READING + (user?.count(word) ?: 0f)

    // [text], read through [node] or [userNode], as a word ending at [end]: once, at the reading the
    // user learned it at while it is still their choice (typed or added by hand), whichever order the
    // readings are found in; else the one the dictionary weighs highest of those found (learned so, as
    // alone reads a character), else the first
    private fun word(end: Int, text: StringBuilder, node: Int, userNode: Int, ink: Double) {
        val t = text.toString()
        val found = foundTexts[t]
        if (node >= 0) {
            val id = index.find(t)
            // blocked in any reading: what is written has none. A word at the node, not one that only
            // shares its text with a word of the node's reading's children: 银行 through 银杏's
            // yin xing would be learned as read so, a word of the user's
            val k = if (id == NO_WORD || user?.blockedAnyhow(id) == true) -1 else wordAt(node, id)
            if (k >= 0) {
                val read = path.copyOf(end - searchStart)
                val weight = if (user?.reading(id)?.contentEquals(read) == true) theirs(id) else dictionary.weight(node, k)
                if (found == null || weight > found.second) found(t, Arc(searchStart, end, id, t, ink, read), weight, found?.first)
                return
            }
        }
        if (userNode < 0 || user == null) return
        for (k in 0 until user.wordCount(userNode)) {
            val id = user.word(userNode, k)
            // a word only imported is none of a sentence till the user's, as the pinyin decoder holds it to the input's start (U-101, C19)
            if (user.text(id) == t && !user.blockedAnyhow(id) && !user.startOnly(id)) {
                // a reading the dictionary lacks: in place of the dictionary's found before it while the
                // user's choice; forgotten, or only imported and never picked, only where nothing else
                // reads the word
                val weight = if (user.reading(id) != null) theirs(id) else Float.NEGATIVE_INFINITY
                if (found == null || weight > found.second) found(t, Arc(searchStart, end, id, t, ink, path.copyOf(end - searchStart)), weight, found?.first)
                return
            }
        }
    }

    // which of [node]'s words is [id]; -1 for none
    private fun wordAt(node: Int, id: Int): Int = (0 until dictionary.wordCount(node)).firstOrNull { dictionary.word(node, it) == id } ?: -1

    // [arc] the word [t] found, at [weight]: in place of the one found before at [at], else added
    private fun found(t: String, arc: Arc, weight: Float, at: Int?) {
        val i = at ?: searchArcs.size
        if (at == null) searchArcs += arc else searchArcs[at] = arc
        foundTexts[t] = i to weight
    }

    companion object {
        /** The recognizer's log-probabilities against the model's log10 ones: see ime-eval handwriting. */
        const val INK_WEIGHT = 1.0

        /** Readings of a position words are looked for through. */
        const val ALTERNATIVES = 12

        const val BEAM = 16

        /** The best sentences a [Decoding] has, whatever they end with: as many as a refiner weighs. */
        const val BEST = 5

        // the weight a reading the user has starts at, above any dictionary weight, a log10 one; a
        // float still tells counts apart a quarter (UserModel's least) away up to the limit's 50 000
        private const val USER_READING = 1e6f

        // the longest word looked for, as TextWords
        private const val MAX_WORD = 8
    }
}

/** The last character of [text], whole where it is two chars (past the BMP). */
internal fun lastCharacter(text: String): String = text.substring(text.offsetByCodePoints(text.length, -1))
