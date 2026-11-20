/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import kotlin.math.log10

/** A dictionary's counts on the model's unigram scale, log10 P = [a] + [b] · log10(count + 1), fitted by least squares. */
class CountFit private constructor(val a: Double, val b: Double) {

    fun prob(count: Long): Float = (a + b * log10(count + 1.0)).toFloat()

    companion object {
        /** Over (count, log10 P) pairs; needs two with different counts, their logs apart too. */
        fun of(pairs: List<Pair<Long, Float>>): CountFit {
            // on the counts, not on d: all at one count, rounding can leave d a hair above 0
            require(pairs.any { it.first != pairs[0].first }) { "${pairs.size} pairs, not enough different counts to fit" }
            val n = pairs.size.toDouble()
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            pairs.forEach { (count, prob) ->
                val x = log10(count + 1.0)
                sx += x; sy += prob; sxx += x * x; sxy += x * prob
            }
            val d = n * sxx - sx * sx
            val b = (n * sxy - sx * sy) / d
            // counts apart whose logs (nearly) agree: d cancels to 0 or below, the slope to ±Inf or NaN
            require(d > 0.0 && b.isFinite()) { "${pairs.size} pairs, counts too close together to fit" }
            return CountFit((sy - b * sx) / n, b)
        }
    }
}
