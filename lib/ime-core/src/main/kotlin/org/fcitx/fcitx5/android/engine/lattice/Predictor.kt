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
 * What the [user] put in right after the last word counts too, by any input method, once they
 * did [PAIRS] times (U-71): at [userWeight] times its share of what followed that word, so a word
 * put in after it again and again comes first, 中文 after 用 once they typed 用中文 often. Their
 * own words too, which the model has none of. A word they turned off after it (U-72) is never
 * offered there. The order given is the original one the session ranks by their picks
 * ([org.fcitx.fcitx5.android.engine.session.PredictionOrder]).
 */
class Predictor(
    private val model: NgramModel,
    private val vocabulary: Vocabulary,
    private val dictionary: PinyinDictionary? = null,
    private val user: UserWords? = null,
    private val userWeight: Float = USER_WEIGHT,
    /** Never offered: see [CoarseWords]. */
    private val coarse: CoarseWords = CoarseWords.NONE,
    /** [charReadingsOf] the [dictionary], asked for when [reading] first needs it: one map for the predictors over it to share. */
    charReadings: () -> Map<Int, IntArray> = { dictionary?.let { charReadingsOf(it, vocabulary) }.orEmpty() },
) {

    /**
     * @param prev2 the word before [prev], or [NO_WORD]
     * @param prev the last word committed; nothing is predicted without one
     * @param tail the model's word to go on from where it has nothing after [prev2] and [prev]:
     *   one of [prev]'s end ([tail]), or [NO_WORD]
     * @param learnedAfter the word what the user put in after is counted after (U-71): [prev], or the
     *   word of the model's the text ends with where the decoder read it in pieces (好的 as 好 的)
     * @return words with an [end][Candidate.end] of 0, as they read no input
     */
    fun predict(prev2: Int, prev: Int, limit: Int = DEFAULT_LIMIT, learnedAfter: Int = prev, tail: () -> Int = { NO_WORD }): List<Candidate> {
        if (prev == NO_WORD || limit <= 0) return emptyList()
        // what 不再联想 is filed under: the word as put in, not the piece the decoder ended it with (review-uat-fixes S11)
        // and what the user put in after is counted under the same word (F2996)
        val key = if (learnedAfter == NO_WORD) prev else learnedAfter
        after = textOf(key)
        val typed = learned(key)
        val best = Best(limit)
        // the words as the model has them (UserScorer's rule); typed above keeps the user's prev. A
        // word the model lacks (the user's own, say) would get the followers of <unk>
        val modelPrev = user?.modelWord(prev) ?: prev
        var context = if (modelPrev < model.vocabularySize) model.context(user?.modelWord(prev2) ?: prev2, modelPrev) else NO_CONTEXT
        if (context != NO_CONTEXT && !offerFollowers(context, typed, best)) context = NO_CONTEXT
        if (context == NO_CONTEXT) {
            val end = tail()
            if (end != NO_WORD) {
                context = model.context(NO_WORD, end)
                offerFollowers(context, typed, best)
            }
        }
        // typed after prev, and not among the model's words after it: one of the user's the model has
        // the text of (UserScorer's rule) gains the model's probability of it, as the context does
        for ((word, p) in typed) {
            if (p <= 0f || !offered(word, after)) continue
            val modelWord = user?.modelWord(word) ?: word
            val base = if (context != NO_CONTEXT && modelWord < model.vocabularySize) 10f.pow(model.scoreAfter(context, modelWord)) else 0f
            best.offer(word, minOf(0f, log10(base + p)))
        }
        return best.candidates()
    }

    // what the user put in right after prev often enough (U-71), at userWeight times its share of all
    // that followed prev, PRIOR more: one word put in 3 times after prev and nothing else is not certain
    private fun learned(prev: Int): HashMap<Int, Float> {
        val typed = HashMap<Int, Float>()
        val user = user ?: return typed
        var total = 0f
        user.forEachPredicted(prev) { _, count -> total += count }
        // a word only imported is not predicted till the user's (U-101: two commits), however often put in after prev
        if (total > 0f) user.forEachPredicted(prev) { word, count -> if (count >= PAIRS && !user.startOnly(word)) typed[word] = userWeight * count / (total + PRIOR) }
        return typed
    }

    // the model's words after context, each with what the user typed of it there: whether it has any to offer
    private fun offerFollowers(context: Long, typed: HashMap<Int, Float>, best: Best): Boolean {
        var any = false
        model.forEachAfter(context) { word, modelScore ->
            // most often empty, and a common word has tens of thousands of followers to box
            val p = if (typed.isEmpty()) null else typed.remove(word)
            val score = if (p == null || p <= 0f) modelScore else minOf(0f, log10(10f.pow(modelScore) + p))
            // ties go to the word seen first, being the lower id
            val takes = best.takes(score)
            if (any && !takes) return@forEachAfter
            // what the model has after it may be only punctuation, or what the user blocked
            val offerable = offered(word, after)
            any = any || offerable
            if (takes && offerable) best.offer(word, score)
        }
        return any
    }

    /** The [limit] best words offered, best first, a text once. */
    private inner class Best(private val limit: Int) {
        private val words = IntArray(limit)
        private val texts = arrayOfNulls<String>(limit)
        private val scores = FloatArray(limit)
        private var size = 0

        fun takes(score: Float) = size < limit || score > scores[size - 1]

        // a word of the user's may be written as one of the dictionary's, read otherwise (银行 as yin
        // xing, 再 typed as ce): one text takes one place, at the higher score, so limit words are offered
        fun offer(word: Int, score: Float) {
            if (!takes(score)) return
            val text = textOf(word)
            val same = (0 until size).firstOrNull { texts[it] == text }
            if (same != null) {
                if (scores[same] >= score) return
                for (j in same until size - 1) {
                    words[j] = words[j + 1]
                    texts[j] = texts[j + 1]
                    scores[j] = scores[j + 1]
                }
                size--
            }
            var k = minOf(size, limit - 1)
            while (k > 0 && scores[k - 1] < score) {
                words[k] = words[k - 1]
                texts[k] = texts[k - 1]
                scores[k] = scores[k - 1]
                k--
            }
            words[k] = word
            texts[k] = text
            scores[k] = score
            if (size < limit) size++
        }

        fun candidates() = List(size) { Candidate(texts[it]!!, 0, scores[it], intArrayOf(words[it]), intArrayOf(0)) }
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
     * as the user typed it if they still have it, which is how it was learned, else the dictionary's
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

    // the syllables each character of the dictionary is read as: see charReadingsOf
    private val charReadings: Map<Int, IntArray> by lazy(LazyThreadSafetyMode.NONE, charReadings)

    /** The text of [word], the model's or the user's. */
    fun textOf(word: Int) = if (word < vocabulary.size) vocabulary.word(word) else user?.text(word).orEmpty()

    /**
     * [word] as a prediction after [prev], though not among the best [predict] gave: one the user
     * picked there before (U-70); null if it is not offered (blocked, coarse, not words, turned off
     * after [prev]). Leaves [after] as [predict] set it: resetting it here filed 不再联想 under the
     * decoder's piece rather than the word as put in (F2657).
     */
    fun candidate(prev: Int, word: Int): Candidate? {
        if (prev == NO_WORD) return null
        if (!offered(word, textOf(prev))) return null
        return Candidate(textOf(word), 0, Float.NEGATIVE_INFINITY, intArrayOf(word), intArrayOf(0))
    }

    /** The text of the word [predict] last predicted after: what a prediction is turned off after (U-72). */
    var after = ""
        private set

    private fun offered(word: Int, prevText: String): Boolean {
        if (user?.blockedAnyhow(word) == true || coarse.unasked(word, user, vocabulary.size)) return false
        val text = textOf(word)
        if (user?.notPredicted(prevText, text) == true) return false
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

        /** Put in after a word this often, by any input method, a word is offered after it (U-71). */
        const val PAIRS = 3
        /**
         * Under `ime-eval predict-learn` on pinyin.tsv, the half typed three times then predicted
         * again: top1 19.9% learning nothing, 58.9 / 65.4 / 66.1% at 0.25 / 0.5 / 1; the other half,
         * never typed, 18.3% learning nothing, 20.4 / 19.9 / 19.4% (top5 40.9 / 39.6 / 39.2%): the
         * most of the first that keeps the second up (impl/streams/H-report.md).
         */
        const val USER_WEIGHT = 0.25f

        // what follows a word counted as this many more than were put in after it (UserModel's PRIOR)
        internal const val PRIOR = 2f
        private const val NO_CONTEXT = Long.MIN_VALUE
        // readings of a word tried at most: 7 characters of 3 readings each
        private const val MAX_READINGS = 2187
        private const val TAIL = 2
        private const val MIDDLE_DOT = 0xb7

        /**
         * The syllables each character of [dictionary] is read as, from its words of one character,
         * for [Predictor.reading]: some 20,000 characters, so the pinyins' sessions share one map.
         */
        fun charReadingsOf(dictionary: PinyinDictionary, vocabulary: Vocabulary): Map<Int, IntArray> {
            val readings = HashMap<Int, MutableList<Int>>()
            val first = dictionary.firstChild(dictionary.root)
            for (node in first until first + dictionary.childCount(dictionary.root)) {
                for (k in 0 until dictionary.wordCount(node)) {
                    val text = vocabulary.word(dictionary.word(node, k))
                    if (text.isEmpty() || Character.charCount(text.codePointAt(0)) != text.length) continue
                    readings.getOrPut(text.codePointAt(0)) { ArrayList() } += dictionary.syllable(node)
                }
            }
            return readings.mapValues { (_, list) -> list.toIntArray() }
        }
    }
}
