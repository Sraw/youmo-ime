/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.data.runEnd
import org.fcitx.fcitx5.android.engine.pinyin.KeyNeighbours
import org.fcitx.fcitx5.android.engine.pinyin.SlipCost
import org.fcitx.fcitx5.android.engine.pinyin.Touch

/** Typing error costs in model log10: near/far key, missed, extra, swapped, doubled letter ([repeated]); past [limit] no match. Completions cost [complete] more. */
data class TypingCosts(
    val near: Float = 1.0f,
    val far: Float = 3.0f,
    val missed: Float = 2.0f,
    val extra: Float = 2.0f,
    val swapped: Float = 1.5f,
    val repeated: Float = 1.0f,
    val limit: Float = 4.0f,
    val complete: Float = 0.0f,
    val completeLimit: Float = 1.5f,
    val completeFrom: Int = 3,
)

/**
 * The words the keys spell, start, or spell within a few slips, scored by the model less the typing cost. Walks
 * [EnglishData]'s key order as a trie with a Damerau cost row, pruning past the limit; a prefix the keys spell
 * whole offers its words and is not searched deeper.
 */
class EnglishDecoder(
    private val data: EnglishData,
    private val costs: TypingCosts = TypingCosts(),
    private val keyCost: ((meant: Char, typed: Char) -> Float)? = null,
    private val tapCost: ((touch: Touch, typed: Char, meant: Char) -> Float)? = null,
    // extra cost of a slip reading when the keys as typed spell a word as long (as SlipCost.legal)
    private val legalSlip: Float = 0f,
    private val score: (context: Long, word: Int) -> Float = data.model::scoreAfter,
) {

    class Suggestion(val word: Int, val lm: Float, val cost: Float, val completes: Boolean) {
        val score: Float get() = lm - cost
    }

    fun context(words: List<String>): Long {
        val from = maxOf(0, words.size - 2)
        val ids = (from until words.size).map { wordId(words[it], first = it > 0 && words[it - 1] == EnglishText.START) }
        val prev = ids.lastOrNull() ?: NgramModel.NO_WORD
        // after a word the data lacks, start afresh, as such words broke the pages' runs
        val prev2 = if (ids.size == 2 && prev != NgramModel.NO_WORD) ids[0] else NgramModel.NO_WORD
        return data.model.context(prev2, prev)
    }

    // as EnglishVocabulary counted it: a sentence's first word as the commoner of it and its lower case; an unknown
    // spelling as its letters' commonest. The shipped model follows the apostrophe rules only from the next `english-data`
    private fun wordId(word: String, first: Boolean): Int {
        val exact = data.id(word)
        if (first) {
            val lower = data.id(word.replaceFirstChar { it.lowercaseChar() })
            if (lower != NgramModel.NO_WORD) return if (exact == NgramModel.NO_WORD) lower else minOf(exact, lower)
        }
        if (exact != NgramModel.NO_WORD) return exact
        val key = EnglishData.key(word)
        val apostrophe = '\'' in word
        val held = if (apostrophe || key in LIKE_CONTRACTIONS) commonestOf(key, apostrophe) else NgramModel.NO_WORD
        return if (held != NgramModel.NO_WORD) held else commonestOf(key, apostrophe = null)
    }

    private fun commonestOf(key: String, apostrophe: Boolean?): Int {
        var at = 0
        var hi = data.keyed
        while (at < hi) {
            val mid = (at + hi) ushr 1
            if (compareKey(mid, key) < 0) at = mid + 1 else hi = mid
        }
        var best = Int.MAX_VALUE
        while (at < data.keyed && compareKey(at, key) == 0) {
            val word = data.keyedWord(at++)
            if (apostrophe == null || ('\'' in data.word(word)) == apostrophe) best = minOf(best, word)
        }
        return if (best == Int.MAX_VALUE) NgramModel.NO_WORD else best
    }

    private fun compareKey(rank: Int, key: String): Int {
        val length = data.keyLength(rank)
        for (i in 0 until minOf(length, key.length)) {
            val d = data.keyChar(rank, i).compareTo(key[i])
            if (d != 0) return d
        }
        return length.compareTo(key.length)
    }

    /** The [limit] best words for [keys] after [context]; [exactFirst] (nine keys) puts words as long as the keys first; [touches] cost neighbours by tap distance. */
    fun suggest(keys: String, context: Long, limit: Int, exactFirst: Boolean = false, touches: Array<Touch?>? = null): List<Suggestion> {
        if (limit <= 0 || keys.isEmpty() || keys.length > MAX_KEYS) return emptyList()
        val tapped = touches?.takeIf { it.size == keys.length }
        val found = Search(keys, context, limit, tapped).run().values
        val best = compareByDescending<Suggestion> { it.score }.thenBy { it.word }
        val order = if (exactFirst) compareBy<Suggestion> { it.completes }.then(best) else best
        return (if (tapped != null && legalSlip < 0f) legal(found) else found).sortedWith(order).take(limit)
    }

    private fun legal(found: Collection<Suggestion>): Collection<Suggestion> {
        if (found.none { !it.completes && it.cost == 0f }) return found
        return found.mapNotNull { s ->
            if (s.cost == 0f) s else (s.cost - legalSlip).takeIf { it <= costs.limit }?.let { Suggestion(s.word, s.lm, it, s.completes) }
        }
    }

    fun predict(context: Long, limit: Int): List<Suggestion> {
        if (limit <= 0) return emptyList()
        val best = ArrayList<Suggestion>()
        data.model.forEachAfter(context) { word, score ->
            if (word == data.start || word == data.unknown) return@forEachAfter
            if (best.size == limit && score <= best.last().lm) return@forEachAfter
            val s = Suggestion(word, score, 0f, completes = true)
            val at = best.binarySearch { b -> if (b.lm > score) -1 else 1 }.let { -(it + 1) }
            best.add(at, s)
            if (best.size > limit) best.removeAt(best.size - 1)
        }
        return best
    }

    /** Cost of reading [typed] tapped at [touch] as [meant]: 0 for itself, a neighbour by [SlipCost] capped at [TypingCosts.far], far for any other. */
    private fun nearTap(touch: Touch, typed: Char, meant: Char): Float {
        if (meant == typed) return 0f
        val near = touch.near(meant) ?: return costs.far
        val cost = -SLIP.of(touch, near, legal = false)
        return if (cost.isNaN()) costs.far else minOf(costs.far, cost)
    }

    private inner class Search(private val keys: String, private val context: Long, private val limit: Int, private val touches: Array<Touch?>?) {
        private val n = keys.length
        private val rows = Array(MAX_KEYS + n + 2) { FloatArray(n + 1) }
        private val path = CharArray(MAX_KEYS + n + 2)
        private val found = HashMap<Int, Suggestion>()
        private val completeLimit = if (n < costs.completeFrom) 0f else costs.completeLimit

        fun run(): Map<Int, Suggestion> {
            val first = rows[0]
            first[0] = 0f
            for (j in 1..n) first[j] = first[j - 1] + costs.extra
            visit(0, 0, data.keyed)
            return found
        }

        private fun visit(depth: Int, from: Int, to: Int) {
            val row = rows[depth]
            var pos = from
            while (pos < to && data.keyLength(pos) == depth) {
                if (row[n] <= costs.limit) add(data.keyedWord(pos), row[n], completes = false)
                pos++
            }
            if (depth > 0 && completesHere(row, rows[depth - 1])) {
                complete(pos, to, row[n] + costs.complete)
                return
            }
            if (depth >= path.size - 1) return
            while (pos < to) {
                val c = data.keyChar(pos, depth)
                val end = runEnd(pos, to, c) { data.keyChar(it, depth) }
                if (step(depth, c)) visit(depth + 1, pos, end)
                pos = end
            }
        }

        // offered here only if spelt more cheaply than by its parent, not where a shorter prefix is spelt fairly (se for see)
        private fun completesHere(row: FloatArray, parent: FloatArray): Boolean =
            row[n] <= completeLimit && parent[n] > row[n] && row[n] <= row.min()

        private fun step(depth: Int, c: Char): Boolean {
            path[depth] = c
            val prev = rows[depth]
            val next = rows[depth + 1]
            // c after the same letter: missed, it is one of a doubled letter
            val missed = if (depth >= 1 && path[depth - 1] == c) costs.repeated else costs.missed
            next[0] = prev[0] + missed
            var least = next[0]
            for (j in 1..n) {
                val t = keys[j - 1]
                val extra = if (j >= 2 && keys[j - 2] == t) costs.repeated else costs.extra
                var v = prev[j - 1] + substitution(c, t, j - 1)
                v = minOf(v, prev[j] + missed, next[j - 1] + extra)
                if (swapped(depth, j, c)) v = minOf(v, rows[depth - 1][j - 2] + costs.swapped)
                next[j] = v
                if (v < least) least = v
            }
            return least <= costs.limit
        }

        private fun swapped(depth: Int, j: Int, c: Char): Boolean =
            depth >= 1 && j >= 2 && c == keys[j - 2] && path[depth - 1] == keys[j - 1]

        private fun substitution(meant: Char, typed: Char, at: Int): Float {
            val touch = touches?.get(at)
            return when {
                touch != null -> tapCost?.invoke(touch, typed, meant) ?: nearTap(touch, typed, meant)
                keyCost != null -> keyCost(meant, typed)
                else -> fixedCost(meant, typed)
            }
        }

        private fun fixedCost(meant: Char, typed: Char): Float = when {
            meant == typed -> 0f
            typed in KeyNeighbours.of(meant) -> costs.near
            else -> costs.far
        }

        private fun complete(from: Int, to: Int, cost: Float) {
            // a long run: only its common words, as ids go by frequency
            val frequentOnly = to - from > MAX_SCAN
            val best = ArrayList<Suggestion>(limit + 1)
            for (pos in from until to) {
                val word = data.keyedWord(pos)
                if (frequentOnly && word >= FREQUENT) continue
                keepIfAmongBest(best, word, cost)
            }
            for (s in best) found[s.word] = s
        }

        private fun keepIfAmongBest(best: MutableList<Suggestion>, word: Int, cost: Float) {
            val old = found[word]
            if (old != null && old.cost <= cost) return
            val lm = score(context, word)
            if (best.size == limit && !outranks(lm, word, best.last())) return
            val at = best.binarySearch { b -> if (outranks(lm, word, b)) 1 else -1 }.let { -(it + 1) }
            best.add(at, Suggestion(word, lm, cost, completes = true))
            if (best.size > limit) best.removeAt(best.size - 1)
        }

        private fun outranks(lm: Float, word: Int, s: Suggestion) = lm > s.lm || lm == s.lm && word < s.word

        private fun add(word: Int, cost: Float, completes: Boolean) {
            val old = found[word]
            if (old != null && old.cost <= cost) return
            found[word] = Suggestion(word, score(context, word), cost, completes)
        }
    }

    companion object {
        private const val NEVER = 1000f

        /** The nine keys: any letter on the key typed, or the letter itself; nothing missed, extra or swapped. */
        val NINE_KEY_COSTS = TypingCosts(
            near = NEVER, far = NEVER, missed = NEVER, extra = NEVER, swapped = NEVER, repeated = NEVER,
            limit = 0f, complete = 0f, completeLimit = 0f, completeFrom = 1,
        )

        fun nineKeyCost(letters: (Char) -> String): (Char, Char) -> Float = { meant, typed ->
            val fits = if (typed in '2'..'9') meant in letters(typed) else meant == typed
            if (fits) 0f else NEVER
        }

        fun nineKeyTap(letters: (Char) -> String, slip: SlipCost): (Touch, Char, Char) -> Float {
            val fits = nineKeyCost(letters)
            return { touch, typed, meant ->
                val own = fits(meant, typed)
                if (own == 0f || typed !in '2'..'9') {
                    own
                } else {
                    // a plain loop: runs per touch, key and letter (custom keys may have a letter twice)
                    var least = Float.NaN
                    for (i in touch.near.indices) {
                        val near = touch.near[i]
                        if (near.char !in '2'..'9' || meant !in letters(near.char)) continue
                        val cost = -slip.of(touch, near, legal = false)
                        if (!cost.isNaN() && (least.isNaN() || cost < least)) least = cost
                    }
                    if (least.isNaN()) NEVER else least
                }
            }
        }

        private val SLIP = SlipCost()

        const val MAX_KEYS = EnglishText.MAX_WORD

        internal const val MAX_SCAN = 4000

        internal const val FREQUENT = 30_000

        /** Real words spelt like a contraction without its apostrophe (wed, we'd); not cant or wont, meant as can't and won't. */
        val LIKE_CONTRACTIONS: Set<String> = setOf("hell", "id", "ill", "its", "lets", "shed", "shell", "wed", "well", "were")
    }
}
