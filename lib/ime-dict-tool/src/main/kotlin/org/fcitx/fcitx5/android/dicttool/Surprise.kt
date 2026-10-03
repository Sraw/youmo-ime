/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import kotlin.math.log10

/**
 * How much more often a run of characters occurs than the language model predicts: log10 of its
 * frequency (count over the corpus's characters) over the probability of its likeliest reading
 * as the model's words, a character of no word of the model's counting as `<unk>`. A phrase of
 * old words (的事情, 一个人) is as frequent as the model says, however well it holds together;
 * a word the model lacks, or has no idea follows what it follows, is not.
 */
class Surprise(data: PinyinData) {
    private val model = data.model
    private val index = data.wordIndex

    fun of(text: String, count: Int, chars: Long): Double = log10(count.toDouble() / chars) - predicted(text)

    /** log10 P of [text]'s likeliest split into the model's words, from no context. */
    fun predicted(text: String): Double {
        var best = Double.NEGATIVE_INFINITY
        // every split: the runs are short (NewWords.MAX_LENGTH)
        for (mask in 0 until (1 shl (text.length - 1))) best = maxOf(best, split(text, mask))
        return best
    }

    /** log10 P of [text] cut where [mask] has a bit; -∞ if a piece of more than one char is no word of the model's. */
    private fun split(text: String, mask: Int): Double {
        var score = 0.0
        var prev2 = NO_WORD
        var prev = NO_WORD
        var begin = 0
        for (end in 1..text.length) {
            if (end < text.length && mask and (1 shl (end - 1)) == 0) continue
            val id = index.find(text, begin, end)
            if (id == NO_WORD && end - begin > 1) return Double.NEGATIVE_INFINITY
            score += model.score(prev2, prev, id)
            prev2 = prev
            prev = id
            begin = end
        }
        return score
    }
}
