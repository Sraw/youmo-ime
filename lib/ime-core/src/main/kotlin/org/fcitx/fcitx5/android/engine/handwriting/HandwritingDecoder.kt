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
 * Reads characters written one at a time as a sentence, as pinyin reads syllables: the likeliest run of words by
 * [scorer] plus [inkWeight] × the recognizer's log-probabilities, so a later character can fix an earlier one
 * (淡 alone, 谈 once 话 follows). Words are found through the syllable tries from each position's first
 * [alternatives] readings. Not thread-safe; meant to live as long as the session.
 */
class HandwritingDecoder(
    private val dictionary: PinyinDictionary,
    private val vocabulary: Vocabulary,
    private val index: WordIndex,
    private val scorer: WordScorer,
    private val readings: () -> Map<Int, IntArray>,
    private val user: UserWords? = null,
    private val coarse: CoarseWords = CoarseWords.NONE,
    // 生僻字屏蔽 by text, characters with no word id too; unlike user blocks, a fully held position is not kept
    private val held: (String) -> Boolean = { false },
    private val inkWeight: Double = INK_WEIGHT,
    private val alternatives: Int = ALTERNATIVES,
    private val beam: Int = BEAM,
) {
    init {
        require(inkWeight >= 0 && alternatives >= 1 && beam >= 1) { "ink weight $inkWeight, alternatives $alternatives, beam $beam" }
    }

    class Sentence(val text: String, val words: List<Entry>? = null, val score: Float = 0f)

    /** [endings]: per reading of the last position, the best sentence ending with it; [top]: the best whatever they end with, for a sentence model. */
    class Decoding(val endings: List<Sentence>, val top: List<Sentence> = endings.take(1)) {
        val text: String get() = endings.firstOrNull()?.text.orEmpty()

        val sentences: List<String> get() = endings.map { it.text }

        val best: List<String> = top.map { it.text }
        val bestScores: List<Float> = top.map { it.score }
    }

    private class Arc(val start: Int, val end: Int, val word: Int, val text: String, val ink: Double, val syllables: IntArray?)

    private class State(val score: Double, val prev: Int, val word: Int, val text: String, val syllables: IntArray?, val back: State?)

    private val byScore = Comparator<State> { a, b -> b.score.compareTo(a.score) }

    /** Whether [word] has its only possible reading; 行 alone is a guess. */
    fun sure(word: Entry): Boolean =
        word.text.codePointCount(0, word.text.length) > 1 || readings()[word.text.codePointAt(0)]?.size == 1

    fun alone(c: String): Entry? = alone(c, index.find(c), readings())?.let { Entry(c, it) }

    /** Drops the cached search: it would still find a character the user has since blocked. */
    fun forgetSearch() {
        known = emptyList()
        knownFrom = emptyList()
        searchedBeams = emptyArray()
        beamsOf = emptyList()
    }

    fun decode(positions: List<InkReading>, prev2: Int = NO_WORD, prev: Int = NO_WORD): Decoding {
        // the same objects kept, so a stroke continues from the beams before it ([standing])
        val read = positions.filter(::reads)
        val n = read.size
        if (n == 0) return Decoding(emptyList())
        val beams = arrayOfNulls<List<State>>(n + 1)
        val from = keep(read, prev2, prev, beams)
        keptTo = from
        val ending = arcs(read)
        for (end in from + 1..n) {
            val next = ArrayList<State>()
            for (arc in ending[end]) {
                for (s in beams[arc.start] ?: continue) next += State(s.score + arc.ink + lm(s.prev, s.word, arc.word), s.word, arc.word, arc.text, arc.syllables, s)
            }
            next.sortWith(byScore)
            beams[end] = if (end == n) next else distinct(next)
        }
        searchedBeams = beams
        beamsOf = read.toList()
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

    fun reads(position: InkReading): Boolean = position.texts.any { !held(it) }

    internal var keptTo = -1
        private set

    private var searchedBeams: Array<List<State>?> = emptyArray()
    private var beamsOf: List<InkReading> = emptyList()
    private var beamsPrev2 = NO_WORD
    private var beamsPrev = NO_WORD

    // reuses [beams] where the positions before are the same objects after the same words, as pinyin's decoder keeps
    // its prefix: learning meanwhile counts from the next sentence
    private fun keep(positions: List<InkReading>, prev2: Int, prev: Int, beams: Array<List<State>?>): Int {
        val k = if (prev2 == beamsPrev2 && prev == beamsPrev) standing(positions) else -1
        if (k < 0) {
            beams[0] = listOf(State(0.0, prev2, prev, "", null, null))
            return 0
        }
        val last = beamsOf.size
        for (e in 0..k) beams[e] = searchedBeams[e]?.let { if (e == last && e < positions.size) distinct(it) else it }
        return k
    }

    private fun standing(positions: List<InkReading>): Int {
        val last = beamsOf.size
        if (searchedBeams.size != last + 1) return -1
        var k = 0
        while (k < minOf(positions.size, last) && positions[k] === beamsOf[k]) k++
        return if (k == positions.size && k < last) k - 1 else k
    }

    // one per last word pair: both continue alike, so the worse could never come first again
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
        val score = scorer.score(prev2, prev, word).toDouble()
        return if (word != NO_WORD && coarse.unasked(word, user, vocabulary.size)) score + CoarseWords.PENALTY else score
    }

    private var known: List<InkReading> = emptyList()
    private var knownFrom: List<List<Arc>> = emptyList()

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
        // a user-blocked character is left out as a word is, except where every reading is blocked: then the ink's best
        val shown = p.texts.indices.filter { !held(p.texts[it]) }
        val open = shown.filter { !blocked(ids[it]) }.ifEmpty { shown.take(1) }
        for (i in open) arcs += Arc(start, start + 1, ids[i], p.texts[i], inkWeight * p.scores[i], alone(p.texts[i], ids[i], map))
        searched = positions
        searchStart = start
        searchArcs = arcs
        // a blocked character is in no word either (人 blocked, 天人 would show it again)
        shut = Array(minOf(positions.size - start, MAX_WORD)) { k ->
            val texts = positions[start + k].texts
            val heldAt = BooleanArray(texts.size) { held(texts[it]) }
            val blockedAt = BooleanArray(texts.size) { heldAt[it] || blocked(if (k == 0) ids[it] else index.find(texts[it])) }
            val best = heldAt.indexOfFirst { !it }
            if (blockedAt.all { it }) BooleanArray(texts.size) { it != best } else blockedAt
        }
        foundTexts.clear()
        extend(start, dictionary.root, user?.root ?: -1, user?.mergedWords?.root ?: -1, StringBuilder(), 0.0)
        return arcs
    }

    private fun blocked(word: Int) = word != NO_WORD && user?.blockedAnyhow(word) == true

    // as the user typed it while they still have it, else the dictionary's likeliest (行 xing, as Predictor.reading)
    private fun alone(c: String, word: Int, map: Map<Int, IntArray>): IntArray? {
        if (word != NO_WORD) user?.reading(word)?.takeIf { it.size == 1 }?.let { return it }
        val cp = c.codePointAt(0)
        val syllables = map[cp]?.takeIf { it.isNotEmpty() } ?: return null
        return intArrayOf(likeliest.getOrPut(cp) { likeliestOf(c, syllables) })
    }

    private val likeliest = HashMap<Int, Int>()

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

    // one start's search state ([arcsFrom]); a reading the user still has outweighs the dictionary's
    private var searched: List<InkReading> = emptyList()
    private var searchStart = 0

    private var shut: Array<BooleanArray> = emptyArray()
    private var searchArcs = ArrayList<Arc>()
    private val foundTexts = HashMap<String, Pair<Int, Float>>()

    private val path = IntArray(MAX_WORD)

    private fun extend(at: Int, node: Int, userNode: Int, mergedNode: Int, text: StringBuilder, ink: Double) {
        val merged = user?.mergedWords
        if (at == searched.size || at - searchStart == MAX_WORD) return
        val p = searched[at]
        val map = readings()
        val shutHere = shut[at - searchStart]
        for (i in 0 until minOf(alternatives, p.texts.size)) {
            val c = p.texts[i]
            val syllables = map[c.codePointAt(0)]?.takeUnless { shutHere[i] } ?: continue
            val scored = ink + inkWeight * p.scores[i]
            text.append(c)
            for (s in syllables) {
                val child = if (node < 0) -1 else dictionary.child(node, s)
                val userChild = if (userNode < 0 || user == null) -1 else user.child(userNode, s)
                val mergedChild = if (mergedNode < 0 || merged == null) -1 else merged.child(mergedNode, s)
                if (child < 0 && userChild < 0 && mergedChild < 0) continue
                path[at - searchStart] = s
                if (at > searchStart) word(at + 1, text, child, userChild, mergedChild, scored)
                if (goesOn(child, userChild, mergedChild)) extend(at + 1, child, userChild, mergedChild, text, scored)
            }
            text.setLength(text.length - c.length)
        }
    }

    private fun goesOn(node: Int, userNode: Int, mergedNode: Int) =
        (node >= 0 && dictionary.childCount(node) > 0) || (userNode >= 0 && user != null && user.childCount(userNode) > 0) ||
            (mergedNode >= 0 && user?.mergedWords?.let { it.childCount(mergedNode) > 0 } == true)

    // of two readings the user has of one text, the more typed wins, not the first found
    private fun theirs(word: Int) = USER_READING + (user?.count(word) ?: 0f)

    // [text] as a word ending at [end], once: at the user's reading while their choice, else the dictionary's likeliest
    private fun word(end: Int, text: StringBuilder, node: Int, userNode: Int, mergedNode: Int, ink: Double) {
        val t = text.toString()
        val found = foundTexts[t]
        if (node >= 0) {
            val id = index.find(t)
            // only a word at the node itself, not one sharing text with a child's: 银行 via 银杏's yin xing would be learned so
            val k = if (id == NO_WORD || user?.blockedAnyhow(id) == true) -1 else wordAt(node, id)
            if (k >= 0) {
                val read = path.copyOf(end - searchStart)
                val weight = if (user?.reading(id)?.contentEquals(read) == true) theirs(id) else dictionary.weight(node, k)
                if (found == null || weight > found.second) found(t, Arc(searchStart, end, id, t, ink, read), weight, found?.first)
                return
            }
        }
        if (user == null) return
        if (userNode >= 0 && userWord(end, t, user, userNode, ink)) return
        val merged = user.mergedWords ?: return
        if (mergedNode < 0) return
        for (k in 0 until merged.wordCount(mergedNode)) {
            val id = merged.word(mergedNode, k)
            if (user.text(id) == t && !user.blockedAnyhow(id)) {
                val weight = if (user.reading(id) != null) theirs(id) else Float.NEGATIVE_INFINITY
                val now = foundTexts[t]
                if (now == null || weight > now.second) found(t, Arc(searchStart, end, id, t, ink, path.copyOf(end - searchStart)), weight, now?.first)
                return
            }
        }
    }

    private fun userWord(end: Int, t: String, user: UserWords, userNode: Int, ink: Double): Boolean {
        val found = foundTexts[t]
        for (k in 0 until user.wordCount(userNode)) {
            val id = user.word(userNode, k)
            // a word only imported is in no sentence till the user's, as the pinyin decoder holds it to the input's start
            if (user.text(id) == t && !user.blockedAnyhow(id) && !user.startOnly(id)) {
                // a reading the dictionary lacks replaces the dictionary's while the user's choice; else only where nothing else reads it
                val weight = if (user.reading(id) != null) theirs(id) else Float.NEGATIVE_INFINITY
                if (found == null || weight > found.second) found(t, Arc(searchStart, end, id, t, ink, path.copyOf(end - searchStart)), weight, found?.first)
                return true
            }
        }
        return false
    }

    private fun wordAt(node: Int, id: Int): Int = (0 until dictionary.wordCount(node)).firstOrNull { dictionary.word(node, it) == id } ?: -1

    private fun found(t: String, arc: Arc, weight: Float, at: Int?) {
        val i = at ?: searchArcs.size
        if (at == null) searchArcs += arc else searchArcs[at] = arc
        foundTexts[t] = i to weight
    }

    companion object {
        const val INK_WEIGHT = 1.0

        const val ALTERNATIVES = 12

        const val BEAM = 16

        const val BEST = 5

        // above any dictionary log10 weight; a float still tells counts a quarter apart up to 50 000
        private const val USER_READING = 1e6f

        private const val MAX_WORD = 8
    }
}

internal fun lastCharacter(text: String): String = text.substring(text.offsetByCodePoints(text.length, -1))
