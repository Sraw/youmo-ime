/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.data.runEnd
import org.fcitx.fcitx5.android.engine.pinyin.KeyNeighbours

/**
 * What typing a word's keys otherwise costs, in the model's log10: a key beside the one meant
 * ([near]) or any other ([far]), a key of the word not typed ([missed]), one typed it has not
 * ([extra]), two side by side the other way round ([swapped]); one of a doubled letter missed or
 * one doubled that is not, helo for hello, helllo ([repeated]). Past [limit] the keys are not the
 * word's. A word longer than the keys is offered as typed so far, at [complete] more, from keys
 * that cost no more than [completeLimit] to be its start (nothing short of [completeFrom] keys, for
 * which only the start they spell is any word's).
 */
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
 * The words of [data] for the keys typed: those they spell, those they start, and those a few
 * slips from them on the keyboard ([TypingCosts]), each scored by the model after the words before
 * it, less what typing it so cost.
 *
 * The words are searched as a trie of their keys, which [EnglishData]'s key order is: each prefix a
 * run of it, its children found by binary search. A row of edit costs (Damerau's, with the swap) is
 * carried down; a prefix past the limit ends the search there. A prefix the keys spell whole (at
 * no more than the completion limit, and more cheaply than its parent's) offers the words it
 * starts, and is searched no further: those words cost no less below it.
 */
class EnglishDecoder(
    private val data: EnglishData,
    private val costs: TypingCosts = TypingCosts(),
    // what a key typed costs for the letter meant: by default a key beside it [TypingCosts.near], any other far
    private val keyCost: ((meant: Char, typed: Char) -> Float)? = null,
    // a word's score after a context: the model's, or with what the user typed added
    private val score: (context: Long, word: Int) -> Float = data.model::scoreAfter,
) {

    /**
     * A word offered: its id, its model score after the context, what typing it so cost, and
     * whether it is longer than the keys typed (a completion).
     */
    class Suggestion(val word: Int, val lm: Float, val cost: Float, val completes: Boolean) {
        val score: Float get() = lm - cost
    }

    /**
     * The context of the next word after [words] (the nearest last): ids of the last two, each as the
     * dict tool counted it ([wordId]), a word the data lacks as none.
     */
    fun context(words: List<String>): Long {
        val from = maxOf(0, words.size - 2)
        val ids = (from until words.size).map { wordId(words[it], first = it > 0 && words[it - 1] == EnglishText.START) }
        val prev = ids.lastOrNull() ?: NgramModel.NO_WORD
        // a word the data lacks starts afresh, as such a word broke the pages' runs
        val prev2 = if (ids.size == 2 && prev != NgramModel.NO_WORD) ids[0] else NgramModel.NO_WORD
        return data.model.context(prev2, prev)
    }

    // [word] as EnglishVocabulary.Words.id counted it: a sentence's [first] as the commoner (the lower id)
    // of it and its lower-case form, The as the; a spelling the data lacks as its letters' commonest,
    // dont as don't, but one with an apostrophe as their commonest with one (WE'LL as we'll, not well)
    // and one without of [LIKE_CONTRACTIONS] as their commonest without (WED as wed). Looked up as
    // written, a sentence's first word was mostly none, and nothing followed it. A slip the pages had
    // is none (teh, which the dict tool counted as the): the data does not say which they were. The
    // rules for an apostrophe and for [LIKE_CONTRACTIONS] reach the shipped model only at the next
    // `english-data` release: it counted WE'LL and WED as their letters' commonest (well, we'd), not as read here
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

    // the lowest id of the words typed with [key] (their run in key order) with an [apostrophe] or
    // without, either for null; none for no such word. The keys are read in place, as the search reads them
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

    // the key at [rank] in key order against [key], as String.compareTo compares them
    private fun compareKey(rank: Int, key: String): Int {
        val length = data.keyLength(rank)
        for (i in 0 until minOf(length, key.length)) {
            val d = data.keyChar(rank, i).compareTo(key[i])
            if (d != 0) return d
        }
        return length.compareTo(key.length)
    }

    /**
     * The [limit] best words for [keys] (letters, lower case: [EnglishData.key]) after [context], best
     * first; [exactFirst], those as long as the keys before any longer (the nine keys', whose words
     * all cost nothing).
     */
    fun suggest(keys: String, context: Long, limit: Int, exactFirst: Boolean = false): List<Suggestion> {
        if (limit <= 0 || keys.isEmpty() || keys.length > MAX_KEYS) return emptyList()
        val found = Search(keys, context, limit).run()
        val best = compareByDescending<Suggestion> { it.score }.thenBy { it.word }
        val order = if (exactFirst) compareBy<Suggestion> { it.completes }.then(best) else best
        return found.values.sortedWith(order).take(limit)
    }

    /** The [limit] likeliest words after [context], best first: what may come next. */
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

    private inner class Search(private val keys: String, private val context: Long, private val limit: Int) {
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
            // the words whose keys end here come first in the run
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

        // whether the keys spell the prefix well enough for its words to be offered: more cheaply than
        // its parent, and best here, not where they spell a shorter one fairly (se for see)
        private fun completesHere(row: FloatArray, parent: FloatArray): Boolean =
            row[n] <= completeLimit && parent[n] > row[n] && row[n] <= row.min()

        // the row for the prefix with [c] after it; false where every cost is past the limit
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
                var v = prev[j - 1] + substitution(c, t)
                v = minOf(v, prev[j] + missed, next[j - 1] + extra)
                if (swapped(depth, j, c)) v = minOf(v, rows[depth - 1][j - 2] + costs.swapped)
                next[j] = v
                if (v < least) least = v
            }
            return least <= costs.limit
        }

        // the keys typed up to [j] end in the prefix's last two, [c] and the one before it, the other way round
        private fun swapped(depth: Int, j: Int, c: Char): Boolean =
            depth >= 1 && j >= 2 && c == keys[j - 2] && path[depth - 1] == keys[j - 1]

        private fun substitution(meant: Char, typed: Char): Float = when {
            keyCost != null -> keyCost(meant, typed)
            meant == typed -> 0f
            typed in KeyNeighbours.of(meant) -> costs.near
            else -> costs.far
        }

        private fun complete(from: Int, to: Int, cost: Float) {
            // a long run (a key or two typed): its commoner words alone, as ids are by frequency
            val frequentOnly = to - from > MAX_SCAN
            // all cost the same here: only the run's [limit] likeliest can be offered, of thousands for a key or two
            val best = ArrayList<Suggestion>(limit + 1)
            for (pos in from until to) {
                val word = data.keyedWord(pos)
                if (frequentOnly && word >= FREQUENT) continue
                keepIfAmongBest(best, word, cost)
            }
            for (s in best) found[s.word] = s
        }

        // [word] completed at [cost] into [best], the run's [limit] likeliest in the order offered, unless
        // it is found as cheaply already or ranks past them
        private fun keepIfAmongBest(best: MutableList<Suggestion>, word: Int, cost: Float) {
            val old = found[word]
            if (old != null && old.cost <= cost) return
            val lm = score(context, word)
            if (best.size == limit && !outranks(lm, word, best.last())) return
            val at = best.binarySearch { b -> if (outranks(lm, word, b)) 1 else -1 }.let { -(it + 1) }
            best.add(at, Suggestion(word, lm, cost, completes = true))
            if (best.size > limit) best.removeAt(best.size - 1)
        }

        // [word] scored [lm] before [s] as they are offered: likelier, or as likely and the lower id
        private fun outranks(lm: Float, word: Int, s: Suggestion) = lm > s.lm || lm == s.lm && word < s.word

        private fun add(word: Int, cost: Float, completes: Boolean) {
            val old = found[word]
            if (old != null && old.cost <= cost) return
            found[word] = Suggestion(word, score(context, word), cost, completes)
        }
    }

    companion object {
        // a cost past any limit: what cannot be
        private const val NEVER = 1000f

        /**
         * Typing on the nine keys: a word's letters each on the key typed, or the letter typed itself
         * (a hardware key's among the screen's); no key missed, extra or swapped, as each press is
         * meant. A word longer than the keys is offered from the first key.
         */
        val NINE_KEY_COSTS = TypingCosts(
            near = NEVER, far = NEVER, missed = NEVER, extra = NEVER, swapped = NEVER, repeated = NEVER,
            limit = 0f, complete = 0f, completeLimit = 0f, completeFrom = 1,
        )

        /** [NINE_KEY_COSTS]' key cost: a digit typed (2 to 9) is any of its [letters], a letter only itself. */
        fun nineKeyCost(letters: (Char) -> String): (Char, Char) -> Float = { meant, typed ->
            val fits = if (typed in '2'..'9') meant in letters(typed) else meant == typed
            if (fits) 0f else NEVER
        }

        /** Keys past this are no word the data has: nothing is searched for them. */
        const val MAX_KEYS = EnglishText.MAX_WORD

        // a run of completions longer than this is scored for its commoner words alone
        internal const val MAX_SCAN = 4000

        // the words below this id: the commonest, as EnglishVocabulary numbers them
        internal const val FREQUENT = 30_000

        /**
         * Real words with a contraction's letters (wed beside we'd), by key: a spelling of them without
         * an apostrophe is counted as their commonest without one, as EnglishVocabulary counts the pages,
         * and read so before the cursor. Not cant or wont, typed for can't and won't far more often than meant.
         */
        val LIKE_CONTRACTIONS: Set<String> = setOf("hell", "id", "ill", "its", "lets", "shed", "shell", "wed", "well", "were")
    }
}
