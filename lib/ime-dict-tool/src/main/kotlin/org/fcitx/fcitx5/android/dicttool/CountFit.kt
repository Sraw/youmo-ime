/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import kotlin.math.log10

/**
 * A dictionary's counts put on the model's unigram scale: log10 P = [a] + [b] · log10(count + 1),
 * fitted by least squares over the words in both. A word the model lacks is then scored by its
 * count rather than as the unknown word, which puts a word the dictionary saw a thousand times
 * above one it saw ten times (万象's counts against the mixed model: a = -7.0, b = 0.41, so a
 * thousand gives -5.8 and ten -6.6, under the unknown word's -6.5).
 */
class CountFit private constructor(val a: Double, val b: Double) {

    fun prob(count: Long): Float = (a + b * log10(count + 1.0)).toFloat()

    companion object {
        /** Over (count, log10 P) pairs; needs two with different counts. */
        fun of(pairs: List<Pair<Long, Float>>): CountFit {
            val n = pairs.size.toDouble()
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            pairs.forEach { (count, prob) ->
                val x = log10(count + 1.0)
                sx += x; sy += prob; sxx += x * x; sxy += x * prob
            }
            val d = n * sxx - sx * sx
            require(d > 0.0) { "${pairs.size} pairs, not enough different counts to fit" }
            val b = (n * sxy - sx * sy) / d
            return CountFit((sy - b * sx) / n, b)
        }
    }
}
