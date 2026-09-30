/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import org.fcitx.fcitx5.android.engine.data.Vocabulary

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
 */
class Predictor(
    private val model: NgramModel,
    private val vocabulary: Vocabulary,
    private val dictionary: PinyinDictionary? = null,
) {

    /**
     * @param prev2 the word before [prev], or [NO_WORD]
     * @param prev the last word committed; nothing is predicted without one
     * @return words with an [end][Candidate.end] of 0, as they read no input
     */
    fun predict(prev2: Int, prev: Int, limit: Int = DEFAULT_LIMIT): List<Candidate> {
        // a word the model lacks (the user's own, say) would get the followers of <unk>
        if (prev !in 0 until model.vocabularySize || limit <= 0) return emptyList()
        val words = IntArray(limit)
        val scores = FloatArray(limit)
        var size = 0
        model.forEachAfter(model.context(prev2, prev)) { word, score ->
            // ties go to the word seen first, being the lower id
            if (size == limit && score <= scores[size - 1]) return@forEachAfter
            if (!offered(word)) return@forEachAfter
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
        return List(size) { Candidate(vocabulary.word(words[it]), 0, scores[it], intArrayOf(words[it]), intArrayOf(0)) }
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

    private fun offered(word: Int): Boolean {
        val text = vocabulary.word(word)
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
        private const val TAIL = 2
        private const val MIDDLE_DOT = 0xb7
    }
}
