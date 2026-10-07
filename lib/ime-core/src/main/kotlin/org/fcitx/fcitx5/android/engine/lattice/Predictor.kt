/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import org.fcitx.fcitx5.android.engine.data.Vocabulary
import kotlin.math.log10
import kotlin.math.pow

/**
 * Words to offer after text is committed, before anything is typed (联想): those the model saw
 * after the last word, or the last two, most probable first. Plain probability ranks best: on
 * the words of the evaluation set's sentences, favouring words tied to the context (PMI) or
 * holding back single characters (的, 是) both found the next word less often.
 *
 * Only words of letters are offered (〇 is one, and · between them as in 马克·吐温): the model also
 * has punctuation and `<unk>`, which a candidate bar has no use for. A common word is followed by tens of thousands (的 by 86,000),
 * so text is made only for words good enough to be among those returned.
 *
 * After a word the model has not seen (one the user put together, say) what may follow is what
 * follows its end: see [tail], which needs the [dictionary] to find it.
 *
 * What the [user] typed after the last word counts too, as the decoder counts it (see
 * [org.fcitx.fcitx5.android.engine.user.UserScorer]) but at [userWeight], higher: a word they
 * typed after this one a few times comes first, 中文 after 用 once they typed 用中文 again and
 * again. Their own words too, which the model has none of.
 */
class Predictor(
    private val model: NgramModel,
    private val vocabulary: Vocabulary,
    private val dictionary: PinyinDictionary? = null,
    private val user: UserWords? = null,
    private val userWeight: Float = USER_WEIGHT,
) {

    /**
     * @param prev2 the word before [prev], or [NO_WORD]
     * @param prev the last word committed; nothing is predicted without one
     * @param tail the model's word to go on from where it has nothing after [prev2] and [prev]:
     *   one of [prev]'s end ([tail]), or [NO_WORD]
     * @return words with an [end][Candidate.end] of 0, as they read no input
     */
    fun predict(prev2: Int, prev: Int, limit: Int = DEFAULT_LIMIT, tail: () -> Int = { NO_WORD }): List<Candidate> {
        if (prev == NO_WORD || limit <= 0) return emptyList()
        val typed = HashMap<Int, Float>()
        user?.forEachAfter(prev) { word -> typed[word] = userWeight * user.probability(prev, word) }
        val best = Best(limit)
        // a word the model lacks (the user's own, say) would get the followers of <unk>
        var context = if (prev < model.vocabularySize) model.context(prev2, prev) else NO_CONTEXT
        if (context != NO_CONTEXT && !offerFollowers(context, typed, best)) context = NO_CONTEXT
        if (context == NO_CONTEXT) {
            val end = tail()
            if (end != NO_WORD) {
                context = model.context(NO_WORD, end)
                offerFollowers(context, typed, best)
            }
        }
        // typed after prev, and not among the model's words after it
        for ((word, p) in typed) {
            if (p <= 0f || !offered(word)) continue
            val base = if (context != NO_CONTEXT && word < model.vocabularySize) 10f.pow(model.scoreAfter(context, word)) else 0f
            best.offer(word, minOf(0f, log10(base + p)))
        }
        return best.candidates()
    }

    // the model's words after context, each with what the user typed of it there: whether it has any to offer
    private fun offerFollowers(context: Long, typed: HashMap<Int, Float>, best: Best): Boolean {
        var any = false
        model.forEachAfter(context) { word, modelScore ->
            // what the model has after it may be only punctuation, or what the user blocked
            if (!any && offered(word)) any = true
            val p = typed.remove(word)
            val score = if (p == null || p <= 0f) modelScore else minOf(0f, log10(10f.pow(modelScore) + p))
            // ties go to the word seen first, being the lower id
            if (!best.takes(score) || !offered(word)) return@forEachAfter
            best.offer(word, score)
        }
        return any
    }

    /** The [limit] best words offered, best first. */
    private inner class Best(private val limit: Int) {
        private val words = IntArray(limit)
        private val scores = FloatArray(limit)
        private var size = 0

        fun takes(score: Float) = size < limit || score > scores[size - 1]

        fun offer(word: Int, score: Float) {
            if (!takes(score)) return
            var k = minOf(size, limit - 1)
            while (k > 0 && scores[k - 1] < score) {
                words[k] = words[k - 1]
                scores[k] = scores[k - 1]
                k--
            }
            words[k] = word
            scores[k] = score
            if (size < limit) size++
        }

        // a word of the user's may be written as one of the dictionary's, read otherwise (银行 as yin xing)
        fun candidates() = List(size) { Candidate(textOf(words[it]), 0, scores[it], intArrayOf(words[it]), intArrayOf(0)) }.distinctBy { it.text }
    }

    /**
     * The model's word for the end of [text], read as [syllables]: its last two characters, else
     * its last; [NO_WORD] if neither is a word the model has, or [text] is no longer than that
     * (it is then the word itself), or its characters and syllables do not pair up.
     */
    fun tail(text: String, syllables: IntArray): Int {
        val dictionary = dictionary ?: return NO_WORD
        val chars = text.codePointCount(0, text.length)
        if (chars != syllables.size) return NO_WORD
        for (n in minOf(TAIL, chars - 1) downTo 1) {
            val node = dictionary.find(syllables.copyOfRange(chars - n, chars))
            if (node < 0) continue
            val end = text.substring(text.offsetByCodePoints(text.length, -n))
            for (i in 0 until dictionary.wordCount(node)) {
                val word = dictionary.word(node, i)
                if (word < model.vocabularySize && vocabulary.word(word) == end) return word
            }
        }
        return NO_WORD
    }

    /**
     * The syllables [word] is likeliest read as, to learn, forget or block it as a prediction:
     * as the user typed it if they did, which is how it was learned, else the dictionary's
     * reading of highest weight; null for a word neither reads.
     */
    fun reading(word: Int): IntArray? {
        user?.reading(word)?.let { return it }
        if (word >= vocabulary.size) return null
        val dictionary = dictionary ?: return null
        val text = vocabulary.word(word)
        val options = ArrayList<IntArray>()
        var i = 0
        while (i < text.length) {
            val c = Character.codePointAt(text, i)
            options += charReadings[c] ?: return null
            i += Character.charCount(c)
        }
        var best: IntArray? = null
        var bestWeight = Float.NEGATIVE_INFINITY
        val path = IntArray(options.size)
        var tried = 0
        fun visit(depth: Int, node: Int) {
            if (tried >= MAX_READINGS) return
            if (depth == path.size) {
                tried++
                for (k in 0 until dictionary.wordCount(node)) {
                    if (dictionary.word(node, k) == word && dictionary.weight(node, k) > bestWeight) {
                        bestWeight = dictionary.weight(node, k)
                        best = path.copyOf()
                    }
                }
                return
            }
            for (s in options[depth]) {
                val child = dictionary.child(node, s)
                if (child < 0) continue
                path[depth] = s
                visit(depth + 1, child)
            }
        }
        visit(0, dictionary.root)
        return best
    }

    // the syllables each character of the dictionary is read as, from its words of one character
    private val charReadings: Map<Int, IntArray> by lazy(LazyThreadSafetyMode.NONE) {
        val dictionary = dictionary ?: return@lazy emptyMap()
        val readings = HashMap<Int, MutableList<Int>>()
        val first = dictionary.firstChild(dictionary.root)
        for (node in first until first + dictionary.childCount(dictionary.root)) {
            for (k in 0 until dictionary.wordCount(node)) {
                val text = vocabulary.word(dictionary.word(node, k))
                if (text.isEmpty() || Character.charCount(text.codePointAt(0)) != text.length) continue
                readings.getOrPut(text.codePointAt(0)) { ArrayList() } += dictionary.syllable(node)
            }
        }
        readings.mapValues { (_, list) -> list.toIntArray() }
    }

    private fun textOf(word: Int) = if (word < vocabulary.size) vocabulary.word(word) else user?.text(word).orEmpty()

    private fun offered(word: Int): Boolean {
        if (user?.blockedAnyhow(word) == true) return false
        val text = textOf(word)
        var i = 0
        while (i < text.length) {
            val c = Character.codePointAt(text, i)
            if (!isLetter(c) && !joins(text, i, c)) return false
            i += Character.charCount(c)
        }
        return text.isNotEmpty()
    }

    private fun isLetter(c: Int) = Character.isLetter(c) || Character.getType(c) == Character.LETTER_NUMBER.toInt()

    /** A middle dot [c] at [i] between two parts of a name. */
    private fun joins(text: String, i: Int, c: Int) = c == MIDDLE_DOT && i > 0 && i < text.length - 1

    companion object {
        const val DEFAULT_LIMIT = 20
        /**
         * Under `ime-eval predict-learn` on the chat set, the half typed once then predicted
         * again: top1 13.0% learning nothing, 19.6 / 30.9 / 34.1 / 36.4% at 0.1 / 0.3 / 0.5 / 1;
         * the other half, never typed, 13.0% falling to 13.0 / 12.7 / 12.6 / 12.5%.
         */
        const val USER_WEIGHT = 0.5f
        private const val NO_CONTEXT = Long.MIN_VALUE
        // readings of a word tried at most: 7 characters of 3 readings each
        private const val MAX_READINGS = 2187
        private const val TAIL = 2
        private const val MIDDLE_DOT = 0xb7
    }
}
