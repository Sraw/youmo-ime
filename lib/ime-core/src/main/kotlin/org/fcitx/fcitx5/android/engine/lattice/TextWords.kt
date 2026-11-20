/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.WordIndex

/**
 * The model's words at the end of text the engine did not commit (the editor's text before the cursor):
 * its last chars split as the model finds likeliest (sum of unigrams), an uncovered char counting as
 * `<unk>`. [scorer] weighs text for [logProb]: the model, or with the user's words mixed in.
 */
class TextWords(
    private val model: NgramModel,
    private val index: WordIndex,
    private val scorer: WordScorer = WordScorer { prev2, prev, word -> model.score(prev2, prev, word) },
) {

    /** [NO_WORD] for none. */
    fun id(text: CharSequence): Int = index.find(text)

    /**
     * The last two words [text] ends with, last last: fewer if it has fewer; none if it ends with a char no
     * model word covers (space, letter), as nothing continues from text kept as typed. [NO_WORD] for a non-word first.
     */
    fun lastTwo(text: CharSequence): IntArray {
        // enough for two words and what decides where they begin
        val from = maxOf(0, text.length - WINDOW)
        val n = text.length - from
        if (n == 0) return IntArray(0)
        val split = split(text, from)
        val last = split.word[n]
        if (last == NO_WORD) return IntArray(0)
        val begin = split.start[n]
        // the window holds more than two words can: neither is cut short
        return if (begin == 0) intArrayOf(last) else intArrayOf(split.word[begin], last)
    }

    /**
     * Word boundaries of [text], split as [lastTwo] splits its end: offsets ascending, 0 and length
     * included; an uncovered char is its own word. With [splitLong] (voice blocking) a word of 3+ chars is
     * split again down to twos, since the model has phrases as words (今天天气 as 今天 天气, so a blocked
     * 天气 is caught). Without, the model's words as they are (ime-eval's 联想, comparable with earlier runs).
     */
    fun boundaries(text: CharSequence, splitLong: Boolean = false): IntArray {
        val ends = ArrayList<Int>()
        ends += 0
        within(text, 0, text.length, MAX_WORD, ends, splitLong)
        return ends.toIntArray()
    }

    // the ends of the words in [begin, end), at most [longest] chars, 3+ char ones split again with [splitLong]
    private fun within(text: CharSequence, begin: Int, end: Int, longest: Int, ends: MutableList<Int>, splitLong: Boolean) {
        if (end == begin) return
        val split = split(text, begin, end, longest)
        val starts = ArrayList<Int>()
        var at = end - begin
        while (at > 0) {
            starts += at
            at = split.start[at]
        }
        var from = begin
        for (wordEnd in starts.asReversed()) {
            val to = begin + wordEnd
            if (splitLong && to - from >= MIN_SPLIT) within(text, from, to, to - from - 1, ends, true) else ends += to
            from = to
        }
    }

    /**
     * log10 P of [text] by [scorer] after [before]'s last words ([lastTwo]), split to be likeliest, each word
     * after the two before it; an uncovered char is `<unk>`. For ranking homophones or a code's 重码.
     */
    fun logProb(text: CharSequence, before: CharSequence = ""): Float = previous(lastTwo(before)).let { (prev2, prev) -> logProb(text, prev2, prev) }

    /** After [prev2] and [prev] (from [previous]): for many texts after the same. */
    fun logProb(text: CharSequence, prev2: Int, prev: Int): Float {
        val n = text.length
        if (n == 0) return 0f
        // per offset, the words a split can end on there by length (0: the word before the text, at 0 only), with
        // best score and predecessor: flat, as the decoder's lattice, since this runs for each 重码 on each key
        val score = FloatArray((n + 1) * STATES) { Float.NEGATIVE_INFINITY }
        val last = IntArray(score.size)
        val before = IntArray(score.size)
        score[0] = 0f
        last[0] = prev
        before[0] = prev2
        val ids = IntArray(MAX_WORD + 1)
        for (begin in 0 until n) {
            val longest = minOf(MAX_WORD, n - begin)
            for (length in 1..longest) ids[length] = index.find(text, begin, begin + length)
            for (s in begin * STATES until (begin + 1) * STATES) {
                if (score[s] == Float.NEGATIVE_INFINITY) continue
                // looked up once for every word that may follow, as the decoder does
                val context = scorer.context(before[s], last[s])
                val trigrams = scorer.trigrams(context)
                for (length in 1..longest) {
                    val id = ids[length]
                    if (id == NO_WORD && length > 1) continue
                    val to = (begin + length) * STATES + length
                    val next = score[s] + scorer.scoreAfter(context, trigrams, id)
                    if (next > score[to]) {
                        score[to] = next
                        last[to] = id
                        before[to] = last[s]
                    }
                }
            }
        }
        var best = Float.NEGATIVE_INFINITY
        for (s in n * STATES until (n + 1) * STATES) best = maxOf(best, score[s])
        return best
    }

    // the likeliest split from [from] on: the word ending at each offset (relative to from) and its start
    private class Split(val word: IntArray, val start: IntArray)

    private fun split(text: CharSequence, from: Int, until: Int = text.length, longest: Int = MAX_WORD): Split {
        val n = until - from
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val word = IntArray(n + 1) { NO_WORD }
        val start = IntArray(n + 1)
        best[0] = 0f
        val unknown = model.score(NO_WORD)
        for (end in 1..n) {
            for (length in 1..minOf(longest, end)) {
                val begin = end - length
                val id = index.find(text, from + begin, from + end)
                val score = when {
                    id != NO_WORD -> model.score(id)
                    length == 1 -> unknown
                    else -> continue
                }
                if (best[begin] + score > best[end]) {
                    best[end] = best[begin] + score
                    word[end] = id
                    start[end] = begin
                }
            }
        }
        return Split(word, start)
    }

    companion object {
        // the longest word looked for: the model's are shorter, but for a few names
        const val MAX_WORD = 8

        // a word this long is split again for boundaries: one of two holds no other of two
        private const val MIN_SPLIT = 3
        private const val WINDOW = 3 * MAX_WORD

        // logProb's states at an offset: a word ending there per length, or the word before
        private const val STATES = MAX_WORD + 1

        /** The last two of [words] ([lastTwo]) as the decoder takes them, second-last first: [NO_WORD] for each missing, and before a non-word last. */
        fun previous(words: IntArray): Pair<Int, Int> {
            val n = words.size
            val prev = if (n >= 1) words[n - 1] else NO_WORD
            val prev2 = if (n >= 2 && prev != NO_WORD) words[n - 2] else NO_WORD
            return prev2 to prev
        }
    }
}
