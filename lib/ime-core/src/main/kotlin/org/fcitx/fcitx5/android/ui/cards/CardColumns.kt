/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.cards

import kotlin.math.floor

/** The settings cards' columns, a keyboard card's name size, whether group cards draw samples; dp, system font scale. */
object CardColumns {

    const val EDGE_DP = 16
    const val GAP_DP = 8

    /** None of these counts leaves one of 8 alone on the last row. */
    val CHOICE: List<Int> = listOf(8, 4, 3, 2)
    const val CHOICE_MIN_DP = 76

    val GROUP: List<Int> = listOf(4, 2, 1)
    const val GROUP_MIN_DP = 132

    /** × the font scale. */
    const val NAME_WIDE_DP = 82
    const val NAME_SP = 13
    const val NAME_SMALL_SP = 12

    /** So all four still show. */
    const val SAMPLES_MIN_HEIGHT_DP = 600

    /** The largest of [candidates] not over ⌊(width − 2·edge + gap) ÷ (min × scale + gap)⌋, else the smallest. The rare page passes min(width, 600). */
    fun columns(widthDp: Float, fontScale: Float, minDp: Int, candidates: List<Int>): Int {
        require(candidates.isNotEmpty() && candidates.all { it > 0 }) { "candidates $candidates" }
        require(minDp > 0 && fontScale > 0f) { "min $minDp dp × $fontScale" }
        val fit = floor((widthDp - 2 * EDGE_DP + GAP_DP) / (minDp * fontScale + GAP_DP)).toInt()
        return candidates.firstOrNull { it <= fit } ?: candidates.last()
    }

    fun cardWidth(widthDp: Float, columns: Int): Float {
        require(columns > 0) { "columns $columns" }
        return (widthDp - 2 * EDGE_DP - GAP_DP * (columns - 1)) / columns
    }

    fun nameSp(cardWidthDp: Float, fontScale: Float): Int =
        if (cardWidthDp >= NAME_WIDE_DP * fontScale) NAME_SP else NAME_SMALL_SP

    fun samples(windowHeightDp: Float): Boolean = windowHeightDp >= SAMPLES_MIN_HEIGHT_DP
}
