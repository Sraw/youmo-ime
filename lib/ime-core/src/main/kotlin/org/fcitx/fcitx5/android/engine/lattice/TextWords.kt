/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.WordIndex

/**
 * The model's words that text the engine did not commit ends with, such as what the editor has
 * before the cursor: its last chars split as the model finds them likeliest, word by word (the
 * sum of their unigram scores), a char no word of the model's covers counting as `<unk>`.
 */
class TextWords(private val model: NgramModel, private val index: WordIndex) {

    /**
     * The last two words [text] ends with, the last one last: fewer when it has fewer, none when
     * it ends with a char the model has no word for (a space, a letter), as after text kept as
     * typed nothing is gone on from. [NO_WORD] for a first that is no word.
     */
    fun lastTwo(text: CharSequence): IntArray {
        // enough for two words and what decides where they begin
        val from = maxOf(0, text.length - WINDOW)
        val n = text.length - from
        if (n == 0) return IntArray(0)
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val word = IntArray(n + 1) { NO_WORD }
        val start = IntArray(n + 1)
        best[0] = 0f
        val unknown = model.score(NO_WORD)
        for (end in 1..n) {
            for (length in 1..minOf(MAX_WORD, end)) {
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
        val last = word[n]
        if (last == NO_WORD) return IntArray(0)
        val begin = start[n]
        // the window holds more than two words can: neither is cut short
        return if (begin == 0) intArrayOf(last) else intArrayOf(word[begin], last)
    }

    private companion object {
        // the longest word looked for: the model's are shorter, but for a few names
        const val MAX_WORD = 8
        const val WINDOW = 3 * MAX_WORD
    }
}
