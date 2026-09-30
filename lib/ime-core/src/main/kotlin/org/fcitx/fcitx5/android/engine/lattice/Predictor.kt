/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
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
 */
class Predictor(private val model: NgramModel, private val vocabulary: Vocabulary) {

    /**
     * @param prev2 the word before [prev], or [NO_WORD]
     * @param prev the last word committed; nothing is predicted without one
     * @return words with an [end][Candidate.end] of 0, as they read no input
     */
    fun predict(prev2: Int, prev: Int, limit: Int = DEFAULT_LIMIT): List<Candidate> {
        if (prev == NO_WORD || limit <= 0) return emptyList()
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
        return List(size) { Candidate(vocabulary.word(words[it]), 0, scores[it], intArrayOf(words[it])) }
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
        private const val MIDDLE_DOT = 0xb7
    }
}
