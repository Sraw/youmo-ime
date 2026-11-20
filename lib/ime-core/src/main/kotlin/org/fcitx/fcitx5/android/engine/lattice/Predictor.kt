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
 * Words to offer after a commit, before anything is typed (联想): the model's followers of the last one or
 * two words, most probable first. Plain probability beat PMI and holding back single characters (的, 是).
 *
 * Only words of letters are offered (〇 and the · in 马克·吐温 count): the model also has punctuation and
 * `<unk>`. A common word has tens of thousands of followers (的: 86,000), so text is made only for those
 * good enough to return.
 *
 * After a word the model never saw (one the user put together), what follows its end: see [tail], which
 * needs [dictionary].
 *
 * What the [user] put in right after the last word, by any input method, counts once done [PAIRS] times:
 * at [userWeight] times its share of that word's followers, so a frequent follower comes first; their own
 * words too. A word turned off after it (不再联想) is never offered there. The order returned is the base
 * the session reorders by picks ([org.fcitx.fcitx5.android.engine.session.PredictionOrder]).
 */
class Predictor(
    private val model: NgramModel,
    private val vocabulary: Vocabulary,
    private val dictionary: PinyinDictionary? = null,
    private val user: UserWords? = null,
    private val userWeight: Float = USER_WEIGHT,
    /** Never offered: see [CoarseWords]. */
    private val coarse: CoarseWords = CoarseWords.NONE,
    /** Lazily, one map shared by the predictors over a dictionary. */
    charReadings: () -> Map<Int, IntArray> = { dictionary?.let { charReadingsOf(it, vocabulary) }.orEmpty() },
) {

    /**
     * @param prev2 the word before [prev], or [NO_WORD]
     * @param prev the last word committed; nothing is predicted without one
     * @param tail the model word to continue from where it has nothing after [prev2] and [prev]: [prev]'s
     *   end ([tail]), or [NO_WORD]
     * @param learnedAfter the word user follow-ups are counted after: [prev], or the model word the text ends
     *   with where the decoder read it in pieces (好的 as 好 的)
     * @return words with an [end][Candidate.end] of 0, as they read no input
     */
    fun predict(prev2: Int, prev: Int, limit: Int = DEFAULT_LIMIT, learnedAfter: Int = prev, tail: () -> Int = { NO_WORD }): List<Candidate> {
        if (prev == NO_WORD || limit <= 0) return emptyList()
        // 不再联想 and user follow-ups are filed under the word as put in, not the decoder's last piece
        val key = if (learnedAfter == NO_WORD) prev else learnedAfter
        after = textOf(key)
        val typed = learned(key)
        val best = Best(limit)
        // the words as the model has them (UserScorer's rule); typed above keeps the user's prev. A word the model
        // lacks would get <unk>'s followers
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
        // user follow-ups not among the model's: a user word with a model word's text gains its probability, as the context does
        for ((word, p) in typed) {
            if (p <= 0f || !offered(word, after)) continue
            val modelWord = user?.modelWord(word) ?: word
            val base = if (context != NO_CONTEXT && modelWord < model.vocabularySize) 10f.pow(model.scoreAfter(context, modelWord)) else 0f
            best.offer(word, minOf(0f, log10(base + p)))
        }
        return best.candidates()
    }

    // what the user put in right after prev often enough, at userWeight times its share of prev's followers,
    // PRIOR added: 3 times after prev and nothing else is not certainty
    private fun learned(prev: Int): HashMap<Int, Float> {
        val typed = HashMap<Int, Float>()
        val user = user ?: return typed
        var total = 0f
        user.forEachPredicted(prev) { _, count -> total += count }
        // an imported-only word is not predicted till the user's, however often put in after prev
        if (total > 0f) user.forEachPredicted(prev) { word, count -> if (count >= PAIRS && !user.startOnly(word)) typed[word] = userWeight * count / (total + PRIOR) }
        return typed
    }

    // the model's followers of context, each with the user's typing of it there; whether any is offerable
    private fun offerFollowers(context: Long, typed: HashMap<Int, Float>, best: Best): Boolean {
        var any = false
        model.forEachAfter(context) { word, modelScore ->
            // mostly empty, and a common word has tens of thousands of followers to box
            val p = if (typed.isEmpty()) null else typed.remove(word)
            val score = if (p == null || p <= 0f) modelScore else minOf(0f, log10(10f.pow(modelScore) + p))
            // ties go to the word seen first, the lower id
            val takes = best.takes(score)
            if (any && !takes) return@forEachAfter
            // the model's followers may be only punctuation, or what the user blocked
            val offerable = offered(word, after)
            any = any || offerable
            if (takes && offerable) best.offer(word, score)
        }
        return any
    }

    /** Best first, a text once. */
    private inner class Best(private val limit: Int) {
        private val words = IntArray(limit)
        private val texts = arrayOfNulls<String>(limit)
        private val scores = FloatArray(limit)
        private var size = 0

        fun takes(score: Float) = size < limit || score > scores[size - 1]

        // a user word may share a dictionary word's text under another reading (银行 as yin xing): one text, one
        // place, at the higher score, so [limit] distinct words are offered
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
     * The model's word for the end of [text] read as [syllables]: its last two characters, else its last;
     * [NO_WORD] if neither is a model word, [text] is no longer (it is the word itself), or characters and
     * syllables don't pair up.
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
     * The syllables to learn, forget or block [word] as a prediction under: as the user typed it if they
     * still have it, else the dictionary's highest-weight reading; null if neither.
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

    private val charReadings: Map<Int, IntArray> by lazy(LazyThreadSafetyMode.NONE, charReadings)

    /** The model's or the user's. */
    fun textOf(word: Int) = if (word < vocabulary.size) vocabulary.word(word) else user?.text(word).orEmpty()

    /**
     * [word] as a prediction after [prev] though not among [predict]'s best: one the user picked there
     * before; null if not offerable (blocked, coarse, not words, turned off after [prev]). Leaves [after] as
     * [predict] set it: resetting it here filed 不再联想 under the decoder's piece.
     */
    fun candidate(prev: Int, word: Int): Candidate? {
        if (prev == NO_WORD) return null
        if (!offered(word, textOf(prev))) return null
        return Candidate(textOf(word), 0, Float.NEGATIVE_INFINITY, intArrayOf(word), intArrayOf(0))
    }

    /** The text [predict] last predicted after: what a prediction is turned off after. */
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

    /** A middle dot between two parts of a name. */
    private fun joins(text: String, i: Int, c: Int) = c == MIDDLE_DOT && i > 0 && i < text.length - 1

    companion object {
        const val DEFAULT_LIMIT = 20

        /** Put in after a word this often, by any input method, a word is offered after it. */
        const val PAIRS = 3
        /** The most that helps words typed after a context without hurting the rest (`ime-eval predict-learn`). */
        const val USER_WEIGHT = 0.25f

        // what follows a word counts as this many more than were put in after it (UserModel's PRIOR)
        internal const val PRIOR = 2f
        private const val NO_CONTEXT = Long.MIN_VALUE
        // readings of a word tried at most: 7 characters of 3 readings each
        private const val MAX_READINGS = 2187
        private const val TAIL = 2
        private const val MIDDLE_DOT = 0xb7

        /** Per character, its readings from the dictionary's one-character words; ~20,000 characters, so the sessions share one map. */
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
